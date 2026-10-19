package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class YuketangApiTest {
    private open class ClosingListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
    }
    private lateinit var server: MockWebServer
    private lateinit var api: YuketangApi
    private val account = Account("123", "测试同学", "pro.yuketang.cn", "test-session")
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        api = YuketangApi(endpoint = { server.url("/") })
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun courseRequestsCarryTheRequiredSessionHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"list":[]}}"""))
        assertTrue(api.courses(account).isEmpty())
        val request = server.takeRequest()
        assertEquals("/v2/api/web/courses/list?identity=2", request.path)
        assertEquals("sessionid=test-session", request.getHeader("Cookie"))
        assertEquals("web", request.getHeader("xt-agent"))
        assertEquals("https://pro.yuketang.cn/", request.getHeader("Referer"))
    }
    @Test fun checkInExchangesTheLessonAndBearerCredentials() = runBlocking {
        server.enqueue(MockResponse().setHeader("Set-Auth", "test-bearer").setBody("""{"code":0,"data":{"lessonToken":"test-lesson"}}"""))
        val access = api.checkIn(account, ActiveLesson(42, "8", "课程"))
        assertEquals("test-lesson", access.lessonToken)
        assertEquals("test-bearer", access.bearer)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(21, body["source"]!!.jsonPrimitive.int)
        assertEquals(42, body["lessonId"]!!.jsonPrimitive.int)
    }
    @Test fun stringLessonIdsFromThePollReachCheckInWithoutNumericCoercion() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setBody(
                    """{"code":0,"data":{"onLessonClassrooms":[{"lessonId":"00042","classroomId":8,"courseName":"课程"}]}}""")
                val id = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["lessonId"]!!.jsonPrimitive
                return if (id.isString && id.content == "00042") MockResponse()
                    .setBody("""{"code":0,"data":{"lessonToken":"test-lesson"}}""")
                else MockResponse().setResponseCode(400).setBody("""{"code":50400,"msg":"invalid request"}""")
            }
        }
        val lesson = api.activeLessons(account).single()
        assertEquals(42L, lesson.id)
        assertEquals("test-lesson", api.checkIn(account, lesson).lessonToken)
        assertEquals(2, server.requestCount)
    }
    @Test fun numericEntryRejectedWith50400CanUseTheWebStringFormatOnce() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["lessonId"]!!.jsonPrimitive
                return if (id.isString) MockResponse().setHeader("Set-Auth", "string-bearer")
                    .setBody("""{"code":0,"data":{"lessonToken":"string-token"}}""")
                else MockResponse().setResponseCode(400).setBody("""{"code":50400,"msg":"invalid request"}""")
            }
        }
        val access = api.checkIn(account, ActiveLesson(42, "8", "课程"))
        assertEquals("string-token", access.lessonToken)
        assertEquals("string-bearer", access.bearer)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest(); val second = server.takeRequest()
        val original = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        val compatible = Json.parseToJsonElement(second.body.readUtf8()).jsonObject
        assertFalse(original["lessonId"]!!.jsonPrimitive.isString)
        assertEquals("42", compatible["lessonId"]!!.jsonPrimitive.content)
        assertTrue(compatible["lessonId"]!!.jsonPrimitive.isString)
        assertEquals(original["source"], compatible["source"])
        assertEquals(first.path, second.path)
        assertEquals(first.getHeader("Cookie"), second.getHeader("Cookie"))
        assertEquals(first.getHeader("Referer"), second.getHeader("Referer"))
        assertTrue(Protocol.hello(account, access).let { Json.parseToJsonElement(it).jsonObject["lessonid"]!!.jsonPrimitive.isString })
    }
    @Test fun aRepeated50400StopsAfterTheStringAttemptAndRetainsSafeDiagnostics() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"code":50400,"msg":"invalid request secret-cookie","data":{"token":"secret-token"}}""")) }
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected an entry rejection") }
        catch (e: LessonEntryFailure) {
            assertEquals(50400, e.code)
            assertEquals(true, e.requestIdIsString)
            assertEquals(LessonEntryDetail.PARAMETERS, e.detail)
            assertTrue(e.message!!.contains("字符串"))
            assertFalse(e.message!!.contains("secret"))
            assertFalse(e.advice.contains("重新登录"))
        }
        assertEquals(2, server.requestCount)
    }
    @Test fun otherRejectionsNeverTriggerTheStringCompatibilityAttempt() = runBlocking {
        for (code in listOf(50070, 50027, 50019, 98765)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":$code,"msg":"invalid request"}"""))
            try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected rejection") }
            catch (e: LessonEntryFailure) { assertEquals(code, e.code) }
            assertEquals(before + 1, server.requestCount)
        }
    }
    @Test fun aStringIdAlreadyMatchesTheWebFormatAndIsNotRetriedOn50400() = runBlocking {
        val lesson = Protocol.parseLessons(Json.parseToJsonElement(
            """{"code":0,"data":{"onLessonClassrooms":[{"lessonId":"42","classroomId":8}]}}""").jsonObject).single()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":50400,"msg":"invalid request"}"""))
        try { api.checkIn(account, lesson); fail("Expected rejection") }
        catch (e: LessonEntryFailure) { assertEquals(true, e.requestIdIsString) }
        assertEquals(1, server.requestCount)
    }
    @Test fun explicitCsrfOrOtherValidationRequirementsDoNotRetryWithAStringId() = runBlocking {
        val cases = mapOf("CSRF token missing secret" to LessonEntryDetail.CSRF,
            "missing required field secret" to LessonEntryDetail.REQUIRED,
            "invalid JSON secret" to LessonEntryDetail.JSON,
            "expected an integer secret" to LessonEntryDetail.NUMBER_TYPE)
        for ((message, detail) in cases) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(400).setBody(buildJsonObject {
                put("code", 50400); put("msg", message)
            }.toString()))
            try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected rejection") }
            catch (e: LessonEntryFailure) {
                assertEquals(detail, e.detail)
                assertEquals(false, e.requestIdIsString)
                assertFalse(e.message!!.contains("secret"))
            }
            assertEquals(before + 1, server.requestCount)
        }
    }
    @Test fun http200Business50400IsNotAnHttp400CompatibilityTrigger() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":50400,"msg":"invalid request"}"""))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected rejection") }
        catch (e: LessonEntryFailure) { assertEquals(200, e.httpStatus) }
        assertEquals(1, server.requestCount)
    }
    @Test fun compatibilityAttemptDoesNotMaskSubsequentSessionExpiry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":50400,"msg":"invalid request"}"""))
        server.enqueue(MockResponse().setBody("""{"code":50000,"msg":"UNAUTHENTICATED","data":""}"""))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected session expiry") }
        catch (_: SessionExpired) { }
        assertEquals(2, server.requestCount)
    }
    @Test fun standardUnauthenticatedEntryErrorsRequireLoginWithoutFormatRetries() = runBlocking {
        for (status in listOf(200, 400)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("""{"code":50000,"msg":"UNAUTHENTICATED","data":""}"""))
            try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected session expiry") }
            catch (_: SessionExpired) { }
            assertEquals(before + 1, server.requestCount)
        }
    }
    @Test fun checkInPreservesTheSiteSessionAndLargeLessonIdOnEverySupportedSite() = runBlocking {
        val lesson = ActiveLesson(9_007_199_254_740_991L, "8", "课程")
        for ((index, domain) in Domain.all.withIndex()) {
            val siteAccount = Account("123", "同学", domain.host, "site-session-$index")
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"lessonToken":"site-token-$index"}}"""))
            assertEquals("site-token-$index", api.checkIn(siteAccount, lesson).lessonToken)
            val sent = server.takeRequest()
            assertEquals("/api/v3/lesson/checkin", sent.path)
            assertEquals("https://${domain.host}/", sent.getHeader("Referer"))
            assertEquals("sessionid=site-session-$index", sent.getHeader("Cookie"))
            assertNull(sent.getHeader("Authorization"))
            val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals(lesson.id, body["lessonId"]!!.jsonPrimitive.long)
            assertEquals(21, body["source"]!!.jsonPrimitive.int)
        }
    }
    @Test fun entryHttp400RetainsOnlyTheNumericBusinessCodeAndFixedReason() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Set-Auth", "secret-bearer")
            .setBody("""{"code":50070,"msg":"secret-cookie https://private.invalid","data":{"lessonToken":"secret-token"}}"""))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected an entry rejection") }
        catch (e: LessonEntryFailure) {
            assertEquals(400, e.httpStatus)
            assertEquals(50070, e.code)
            assertEquals(LessonEntryIssue.DYNAMIC_QR, e.issue)
            assertFalse(e.toString().contains("secret"))
            assertFalse(e.toString().contains("private.invalid"))
        }
        assertEquals(1, server.requestCount)
    }
    @Test fun knownEntryRejectionsHaveTheSameMeaningForHttp200And400() = runBlocking {
        val cases = mapOf(50002 to LessonEntryIssue.ACCESS, 50027 to LessonEntryIssue.MEMBERSHIP,
            50019 to LessonEntryIssue.IDENTITY, 50017 to LessonEntryIssue.RESTRICTED,
            50004 to LessonEntryIssue.FINISHED, 50025 to LessonEntryIssue.RATE_LIMIT)
        for (status in listOf(200, 400)) for ((code, issue) in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":$code,"msg":"secret","data":{}}"""))
            try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected an entry rejection") }
            catch (e: LessonEntryFailure) {
                assertEquals(status, e.httpStatus)
                assertEquals(code, e.code)
                assertEquals(issue, e.issue)
                assertFalse(e.message!!.contains("secret"))
            }
        }
    }
    @Test fun unknownEntryCodesRemainUnknownAndDoNotExposeServerMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"code":98765,"message":"Bearer secret-bearer","data":{"private":"secret"}}"""))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected an entry rejection") }
        catch (e: LessonEntryFailure) {
            assertEquals(98765, e.code)
            assertEquals(LessonEntryIssue.REJECTED, e.issue)
            assertFalse(e.message!!.contains("secret"))
        }
    }
    @Test fun anHttp400NeverBecomesSuccessfulEvenWhenTheBodyContainsAToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Set-Auth", "secret-bearer")
            .setBody("""{"code":0,"data":{"lessonToken":"secret-token"}}"""))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected HTTP rejection") }
        catch (e: LessonEntryFailure) {
            assertEquals(400, e.httpStatus)
            assertEquals(LessonEntryIssue.REJECTED, e.issue)
            assertFalse(e.message!!.contains("secret"))
        }
    }
    @Test fun htmlAndOversizedEntryErrorsHaveASafeUnknownFallback() = runBlocking {
        for (body in listOf("<html>sessionid=secret-cookie</html>",
            """{"code":50070,"msg":"${"secret".repeat(5_000)}"}""")) {
            server.enqueue(MockResponse().setResponseCode(400).setBody(body))
            try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected entry rejection") }
            catch (e: LessonEntryFailure) {
                assertEquals(400, e.httpStatus)
                assertNull(e.code)
                assertEquals(LessonEntryIssue.REJECTED, e.issue)
                assertFalse(e.message!!.contains("secret"))
            }
        }
    }
    @Test fun explicitLoginExpiryInsideHttp400StillExpiresTheAccount() = runBlocking {
        for (body in listOf("""{"code":401,"msg":"secret"}""", """{"code":-1,"msg":"登录过期 secret"}""")) {
            server.enqueue(MockResponse().setResponseCode(400).setBody(body))
            try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected session expiry") }
            catch (e: SessionExpired) { assertFalse(e.message!!.contains("secret")) }
        }
    }
    @Test fun resourcePermissionCodeDoesNotByItselfDiscardAWorkingAccount() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":50000,"data":{}}"""))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected resource denial") }
        catch (e: LessonEntryFailure) { assertEquals(LessonEntryIssue.ACCESS, e.issue) }
    }
    @Test fun serverFailuresDoNotDiscardTheSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        try { api.activeLessons(account); fail("Expected failure") }
        catch (error: Exception) { assertTrue(error is NetworkFailure) }
    }
    @Test fun httpUnauthorizedIsReportedAsAnExpiredSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.courses(account); fail("Expected expired session") }
        catch (error: Exception) { assertTrue(error is SessionExpired) }
    }
    @Test fun qrSuccessExchangesTheCookieAndValidatesTheUser() = runBlocking {
        val requestLogin = CompletableDeferred<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/wsapp/" -> MockResponse().withWebSocketUpgrade(object : ClosingListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        requestLogin.complete(text)
                        webSocket.send("""{"op":"loginsuccess","UserID":123,"Auth":"test-auth"}""")
                    }
                })
                "/pc/web_login" -> MockResponse().setHeader("Set-Cookie", "sessionid=new-session; Path=/; HttpOnly").setBody("{}")
                "/api/v3/user/basic-info" -> MockResponse().setBody("""{"code":0,"data":{"id":123,"name":"测试同学"}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val state = withTimeout(5_000) { api.qrLogin("pro.yuketang.cn").first { it is LoginState.Success } } as LoginState.Success
        assertEquals("new-session", state.account.sessionId)
        assertEquals("123", state.account.userId)
        val sent = Json.parseToJsonElement(requestLogin.await()).jsonObject
        assertEquals("requestlogin", sent["op"]!!.jsonPrimitive.content)
    }
    @Test fun classroomSocketUsesBothCredentialsAndParsesEvents() = runBlocking {
        val hello = CompletableDeferred<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                hello.complete(text)
                webSocket.send("""{"op":"hello"}""")
                webSocket.send("""{"op":"unlockproblem","problem":{"prob":1}}""")
                webSocket.send("""{"op":"lessonfinished"}""")
            }
        }))
        val access = LessonAccess(ActiveLesson(42, "8", "课程"), "test-lesson", "test-bearer")
        val frames = withTimeout(5_000) { api.frames(account, access).toList() }
        assertTrue(frames.contains(LessonFrame.Connected))
        assertTrue(frames.filterIsInstance<LessonFrame.Event>().any { it.event.kind == EventKind.QUESTION })
        assertEquals(LessonFrame.Finished, frames.last())
        val request = server.takeRequest()
        assertEquals("Bearer test-bearer", request.getHeader("Authorization"))
        assertEquals("sessionid=test-session", request.getHeader("Cookie"))
        val sent = Json.parseToJsonElement(hello.await()).jsonObject
        assertEquals(123, sent["userid"]!!.jsonPrimitive.int)
        assertEquals("test-lesson", sent["auth"]!!.jsonPrimitive.content)
    }
    @Test fun serverInitiatedCloseTerminatesTheClientFlow() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1000, "finished") }
        }))
        val frames = withTimeout(5_000) {
            api.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).toList()
        }
        assertTrue(frames.isEmpty())
    }
    @Test fun cancellingTheCollectorClosesTheActualSocket() = runBlocking {
        val connected = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { connected.complete(Unit) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
        }))
        val job = launch { api.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).collect {} }
        withTimeout(5_000) { connected.await() }
        job.cancelAndJoin()
        withTimeout(5_000) { closed.await() }
    }
    @Test fun missingLessonHelloTimesOutAndClosesTheActualSocket() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
        }))
        val timedApi = YuketangApi(endpoint = { server.url("/") }, handshakeTimeoutMillis = 300)
        try {
            withTimeout(5_000) { timedApi.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).toList() }
            fail("Expected unconfirmed hello timeout")
        } catch (e: ClassroomConnectionFailure) { assertEquals(ConnectionIssue.HANDSHAKE, e.issue) }
        withTimeout(5_000) { closed.await() }
    }
    @Test fun confirmedLessonKeepsReceivingMessagesAfterTheHandshakeDeadline() = runBlocking {
        val socket = CompletableDeferred<WebSocket>()
        val connected = CompletableDeferred<Unit>()
        val question = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"op":"hello"}""")
                socket.complete(webSocket)
            }
        }))
        val timedApi = YuketangApi(endpoint = { server.url("/") }, handshakeTimeoutMillis = 500)
        val job = launch {
            timedApi.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).collect {
                if (it == LessonFrame.Connected) connected.complete(Unit)
                if (it is LessonFrame.Event && it.event.kind == EventKind.QUESTION) question.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { connected.await() }
            delay(700)
            assertTrue(job.isActive)
            socket.await().send("""{"op":"unlockproblem","problem":{"prob":1}}""")
            withTimeout(5_000) { question.await() }
        } finally { job.cancelAndJoin() }
    }
    @Test fun rejectedSocketCredentialsDoNotImmediatelyExpireTheAccountCookie() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("sessionid=secret-cookie"))
        try {
            withTimeout(5_000) { api.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).toList() }
            fail("Expected rejected lesson credentials")
        } catch (e: ClassroomConnectionFailure) {
            assertEquals(ConnectionIssue.CREDENTIALS, e.issue)
            assertEquals(403, e.status)
            assertFalse(e.message!!.contains("secret"))
        }
    }
    @Test fun socketHttpFailuresRetainStatusWithoutExposingResponseBodies() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Bearer secret-bearer"))
        try {
            withTimeout(5_000) { api.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).toList() }
            fail("Expected socket HTTP failure")
        } catch (e: ClassroomConnectionFailure) {
            assertEquals(ConnectionIssue.HTTP, e.issue)
            assertEquals(503, e.status)
            assertFalse(e.message!!.contains("secret"))
        }
    }
    @Test fun validLessonMessagesConfirmReadinessEvenWhenHelloIsOmitted() = runBlocking {
        val socket = CompletableDeferred<WebSocket>()
        val ready = CompletableDeferred<Unit>()
        val question = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"op":"unlockproblem","problem":{"prob":1}}""")
                socket.complete(webSocket)
            }
        }))
        val timedApi = YuketangApi(endpoint = { server.url("/") }, handshakeTimeoutMillis = 500)
        val job = launch {
            timedApi.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).collect {
                if (it == LessonFrame.Connected) ready.complete(Unit)
                if (it is LessonFrame.Event && it.event.kind == EventKind.QUESTION) question.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { ready.await(); question.await() }
            delay(700)
            assertTrue(job.isActive)
            assertTrue(socket.await().send("""{"op":"newdanmu","danmu":"课堂消息"}"""))
        } finally { job.cancelAndJoin() }
    }
    @Test fun unknownFramesDoNotConfirmALessonConnection() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("""{"op":"keepalive"}""") }
        }))
        val timedApi = YuketangApi(endpoint = { server.url("/") }, handshakeTimeoutMillis = 300)
        val frames = mutableListOf<LessonFrame>()
        try {
            withTimeout(5_000) { timedApi.frames(account, LessonAccess(ActiveLesson(42, "8", "课程"), "test", null)).collect(frames::add) }
            fail("Expected a confirmation timeout")
        } catch (e: ClassroomConnectionFailure) { assertEquals(ConnectionIssue.HANDSHAKE, e.issue) }
        assertTrue(frames.isEmpty())
    }
    @Test fun credentialHttpErrorsRetainStatusWithoutResponsePayloads() = runBlocking {
        for (status in listOf(302, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("sessionid=secret-cookie"))
            try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected HTTP failure") }
            catch (e: NetworkFailure) {
                assertEquals(ConnectionIssue.HTTP, e.issue)
                assertEquals(status, e.status)
                assertFalse(e.message!!.contains("secret"))
            }
        }
    }
    @Test fun malformedCredentialResponsesAreClassifiedSeparatelyFromTransportFailures() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>secret-error</html>"))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected malformed response") }
        catch (e: NetworkFailure) {
            assertEquals(ConnectionIssue.RESPONSE, e.issue)
            assertFalse(e.message!!.contains("secret"))
        }
    }
    @Test fun missingCredentialTokensAreReportedWithoutLeakingOtherData() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"note":"secret-error"}}"""))
        try { api.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected missing lesson access") }
        catch (e: NetworkFailure) {
            assertEquals(ConnectionIssue.MISSING_ACCESS, e.issue)
            assertFalse(e.message!!.contains("secret"))
        }
    }
    @Test fun transportDnsFailuresAreTypedWithoutExposingHostOrExceptionText() = runBlocking {
        val client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("secret-$hostname")
        }).build()
        val dnsApi = YuketangApi(client, endpoint = { server.url("/").newBuilder().host("unresolvable.invalid").build() })
        try { dnsApi.checkIn(account, ActiveLesson(42, "8", "课程")); fail("Expected DNS failure") }
        catch (e: NetworkFailure) {
            assertEquals(ConnectionIssue.DNS, e.issue)
            assertFalse(e.message!!.contains("secret"))
            assertFalse(e.message!!.contains("unresolvable"))
        }
    }
}
