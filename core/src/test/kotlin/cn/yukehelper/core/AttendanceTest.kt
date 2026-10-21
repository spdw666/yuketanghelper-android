package cn.yukehelper.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AttendanceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YuketangApi
    private lateinit var attendance: ScanAttendance
    private val account = Account("7", "本人", "pro.yuketang.cn", "test-attendance-session")
    private val code = "https://pro.yuketang.cn/v2/web/attend?c=abc123&t=1761000000&s=deadbeef&v=2"

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        api = YuketangApi(endpoint = { server.url("/") })
        attendance = ScanAttendance(api)
    }
    @After fun close() { server.shutdown() }

    private fun body(request: okhttp3.mockwebserver.RecordedRequest) =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test fun qrContentOnlyAcceptsYuketangHttpsLinks() {
        fun accept(raw: String) = QrContent.check(raw) is QrContent.Check.Yuketang
        for (raw in listOf(
            code,
            "https://www.yuketang.cn/c/abc",
            "https://changjiang.yuketang.cn/api/v3/lesson/checkin?c=1&t=2&s=3&v=2",
            "https://huanghe.yuketang.cn/x",
            "https://yuketang.cn/c/abc",
            "https://gsscut.yuketang.cn/c/abc",   // school subdomains are real codes too
            "  $code  ",
        )) assertTrue("should accept: $raw", accept(raw))
        for (raw in listOf(
            "", "   ", "not a url", "http://pro.yuketang.cn/c/abc", "ftp://pro.yuketang.cn/c",
            "https://evil.com/c/abc", "https://yuketang.cn.evil.com/c",
            "https://evil-yuketang.cn/c", "https://user:pw@pro.yuketang.cn/c",
            "https://pro.yuketang.cn:8443/c", "http://weixin.qq.com/q/abc",
            "https://" + "a".repeat(QrContent.MAX_BYTES) + ".yuketang.cn/c",
        )) assertFalse("should reject: $raw", accept(raw))
    }

    @Test fun scanForwardsTheExactCodeThenSignsInWithSource21AndJoinIfNotIn() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"flow":"normal","type":"lesson_checkin","value":123456}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"lessonToken":"tk","className":"数学一班"}}"""))
        val outcome = attendance.submit(account, code)
        val signed = outcome as AttendanceOutcome.Signed
        assertEquals("123456", signed.lessonId); assertEquals("数学一班", signed.lessonName); assertFalse(signed.repeated)

        val scan = server.takeRequest()
        assertEquals("POST", scan.method); assertEquals("/api/v3/app/scan", scan.path)
        assertEquals("sessionid=test-attendance-session", scan.getHeader("Cookie"))
        assertEquals("app", scan.getHeader("x-client")); assertEquals("ykt", scan.getHeader("xtbz"))
        assertNull("web agent must not be mixed with the app client", scan.getHeader("xt-agent"))
        assertEquals(code, body(scan)["url"]!!.jsonPrimitive.content)

        val checkin = server.takeRequest()
        assertEquals("POST", checkin.method); assertEquals("/api/v3/lesson/checkin", checkin.path)
        val payload = body(checkin)
        assertEquals(21, payload["source"]!!.jsonPrimitive.int)
        assertEquals(123456, payload["lessonId"]!!.jsonPrimitive.int)
        assertTrue(payload["joinIfNotIn"]!!.jsonPrimitive.boolean)
        assertEquals(2, server.requestCount)
    }

    @Test fun stringLessonIdsKeepTheirJsonTypeEndToEnd() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"type":"lesson_checkin","value":"1800552410780549125"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"lessonToken":"tk"}}"""))
        attendance.submit(account, code)
        server.takeRequest()
        val sent = body(server.takeRequest())["lessonId"]!!.jsonPrimitive
        assertTrue("a string id must stay a string", sent.isString)
        assertEquals("1800552410780549125", sent.content)
    }

    @Test fun loginCodesAreNeverTreatedAsAttendance() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"flow":"normal","type":"app_web_login","value":"uuid-1"}}"""))
        val outcome = attendance.submit(account, code) as AttendanceOutcome.Rejected
        assertEquals(AttendanceIssue.LOGIN_CODE, outcome.issue)
        assertEquals(1, server.requestCount)
    }

    @Test fun expiredMalformedAndUnknownCodesStopBeforeTheCheckInRequest() = runBlocking {
        val cases = listOf(
            51203 to AttendanceIssue.EXPIRED,
            51201 to AttendanceIssue.MALFORMED,
            50033 to AttendanceIssue.NOT_FOUND,
            50004 to AttendanceIssue.FINISHED,
            50029 to AttendanceIssue.RATE_LIMIT,
        )
        for ((failure, issue) in cases) {
            server.enqueue(MockResponse().setBody("""{"code":$failure,"msg":"server-secret-payload","data":""}"""))
            val outcome = attendance.submit(account, code) as AttendanceOutcome.Rejected
            assertEquals("code $failure", issue, outcome.issue)
            assertEquals("code $failure", failure, outcome.code)
            assertFalse("a fixed category must not echo the upstream message", outcome.issue.summary.contains("server-secret-payload"))
        }
        assertEquals("no check-in request may follow a rejected scan", cases.size, server.requestCount)
    }

    @Test fun anAcceptedScanWithoutAnExplicitBusinessCodeIsNotASignIn() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"type":"lesson_checkin","value":""}}"""))
        val outcome = attendance.submit(account, code) as AttendanceOutcome.Rejected
        assertEquals(AttendanceIssue.UNKNOWN, outcome.issue)
        assertEquals(1, server.requestCount)
    }

    @Test fun aCheckInThatIsNotConfirmedIsReportedAsFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"type":"lesson_checkin","value":42}}"""))
        server.enqueue(MockResponse().setBody("""{"code":51004,"msg":"LESSON_END"}"""))
        val outcome = attendance.submit(account, code) as AttendanceOutcome.Rejected
        assertEquals(AttendanceIssue.UNKNOWN, outcome.issue); assertEquals(51004, outcome.code)
        assertEquals(2, server.requestCount)
    }

    @Test fun rescanningTheSameCodeDoesNotSendASecondCheckIn() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"type":"lesson_checkin","value":9001}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"lessonToken":"tk"}}"""))
        val first = attendance.submit(account, code) as AttendanceOutcome.Signed
        val second = attendance.submit(account, "  $code ") as AttendanceOutcome.Signed
        assertFalse(first.repeated); assertTrue(second.repeated)
        assertEquals(2, server.requestCount)
    }

    @Test fun aRefreshedCodeForTheSameLessonIsNotPostedTwice() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"type":"lesson_checkin","value":9001}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"lessonToken":"tk"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"type":"lesson_checkin","value":9001}}"""))
        attendance.submit(account, code)
        val second = attendance.submit(account, code.replace("c=abc123", "c=refreshed")) as AttendanceOutcome.Signed
        assertTrue(second.repeated)
        assertEquals(3, server.requestCount)   // scan, checkin, scan — never a second checkin
        assertEquals("/api/v3/app/scan", server.takeRequest().path)
        assertEquals("/api/v3/lesson/checkin", server.takeRequest().path)
        assertEquals("/api/v3/app/scan", server.takeRequest().path)
    }

    @Test fun nonYuketangContentNeverReachesTheNetwork() = runBlocking {
        val outcome = attendance.submit(account, "https://evil.com/steal") as AttendanceOutcome.Rejected
        assertEquals(AttendanceIssue.NOT_YUKETANG, outcome.issue)
        assertEquals(0, server.requestCount)
    }

    @Test fun unauthorizedScanExpiresTheSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { attendance.submit(account, code); fail("Expected session expiry") }
        catch (_: SessionExpired) { }
    }
}
