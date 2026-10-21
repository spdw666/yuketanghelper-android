package cn.yukehelper.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class YuketangApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS)
        .followRedirects(false).build(),
    private val endpoint: ((String) -> HttpUrl)? = null,
    private val handshakeTimeoutMillis: Long = 15_000,
) : ClassroomGateway, AutomationGateway, HomeworkGateway, AttendanceGateway {
    private fun url(domain: String, path: String): HttpUrl {
        Domain.requireHost(domain)
        val base = endpoint?.invoke(domain) ?: "https://$domain/".toHttpUrl()
        return requireNotNull(base.resolve(path))
    }

    private fun request(domain: String, path: String, session: String? = null): Request.Builder = Request.Builder()
        .url(url(domain, path)).header("Referer", "https://$domain/")
        .header("xt-agent", "web")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/120.0")
        .apply { if (session != null) header("Cookie", "sessionid=$session") }

    private suspend fun response(request: Request, lessonEntry: ActiveLesson? = null): Response {
        val result = client.newCall(request).await()
        if (!result.isSuccessful) {
            val code = result.code
            if (lessonEntry != null && code == 400) {
                result.use {
                    // Error bodies can contain credentials. Keep only numeric business codes;
                    // bound the read and never retain, log or display arbitrary server content.
                    val root = withContext(Dispatchers.IO) {
                        try { protocolJson.parseToJsonElement(it.peekBody(16_384).string()) as? JsonObject }
                        catch (_: IOException) { null }
                        catch (_: kotlinx.serialization.SerializationException) { null }
                    }
                    if (root != null) {
                        try { Protocol.checkResult(root) }
                        catch (_: ApiRejected) { throw entryFailure(code, root, lessonEntry) }
                    }
                    throw entryFailure(code, root, lessonEntry)
                }
            }
            result.close()
            when (code) {
                401, 403 -> throw SessionExpired()
                429 -> throw NetworkFailure("请求较频繁，稍后会自动重试", ConnectionIssue.HTTP, code)
                else -> throw NetworkFailure("雨课堂连接暂不可用（HTTP $code）", ConnectionIssue.HTTP, code)
            }
        }
        return result
    }

    private fun entryFailure(status: Int, root: JsonObject?, lesson: ActiveLesson): LessonEntryFailure {
        val code = (root?.get("code") as? JsonPrimitive)?.intOrNull
        return LessonEntryFailure(LessonEntryIssue.fromCode(code), status, code, lesson.requestId.isString,
            LessonEntryDetail.fromMessage(root?.text("msg").orEmpty()))
    }

    private suspend fun readJson(result: Response): JsonObject = withContext(Dispatchers.IO) {
        val text = try { result.body?.string() ?: throw NetworkFailure("服务器没有返回数据", ConnectionIssue.EMPTY) }
        catch (error: IOException) { throw requestFailure(error) }
        try {
            protocolJson.parseToJsonElement(text) as? JsonObject
                ?: throw NetworkFailure("雨课堂返回的数据不是对象", ConnectionIssue.RESPONSE)
        } catch (_: kotlinx.serialization.SerializationException) {
            throw NetworkFailure("雨课堂返回了无法识别的数据", ConnectionIssue.RESPONSE)
        }
    }

    private suspend fun json(request: Request): JsonObject = response(request).use { readJson(it) }

    suspend fun account(domain: String, session: String): Account = Protocol.parseUser(
        domain, session, json(request(domain, "/api/v3/user/basic-info", session).build()),
    )

    suspend fun courses(account: Account): List<Course> = Protocol.parseCourses(json(
        request(account.domain, "/v2/api/web/courses/list?identity=2", account.sessionId).build(),
    ))

    override suspend fun homeworkPage(account: Account, course: Course, page: Int): HomeworkPage {
        require(page in 0 until HomeworkSynchronizer.MAX_PAGES)
        val path = "/v2/api/web/logs/learn/" + course.id
        val target = url(account.domain, "/v2/api/web/logs/learn/").newBuilder().addPathSegment(course.id)
            .addQueryParameter("actype", "-1").addQueryParameter("page", page.toString())
            .addQueryParameter("offset", "20").addQueryParameter("sort", "-1").build()
        return HomeworkProtocol.page(json(request(account.domain, path, account.sessionId).url(target).build()), course)
    }

    override suspend fun homeworkDetail(account: Account, homework: Homework): Homework {
        if (homework.type == HomeworkType.QUIZ) {
            val path = "/v2/api/web/quiz/quiz_info"
            val target = url(account.domain, path).newBuilder().addQueryParameter("classroom_id", homework.courseId)
                .addQueryParameter("quiz_id", homework.id).build()
            return HomeworkProtocol.quiz(json(request(account.domain, path, account.sessionId).url(target).build()), homework)
        }
        val target = url(account.domain, "/mooc-api/v1/lms/learn/leaf_info/").newBuilder()
            .addPathSegment(homework.courseId).addPathSegment(homework.leafId).addPathSegment("").build()
        val root = json(request(account.domain, "/", account.sessionId).url(target).build())
        val leaf = HomeworkProtocol.leaf(root, homework)
        val exerciseId = HomeworkProtocol.data(root).obj("content_info").text("leaf_type_id")
            .takeIf { it.isNotBlank() } ?: throw NetworkFailure("作业详情缺少练习编号")
        if (leaf.skuId.isBlank()) throw NetworkFailure("作业详情缺少课程资源编号")
        val detailTarget = url(account.domain, "/mooc-api/v1/lms/exercise/get_exercise_list/").newBuilder()
            .addPathSegment(exerciseId).addPathSegment(leaf.skuId).addPathSegment("").build()
        return HomeworkProtocol.exercise(json(request(account.domain, "/", account.sessionId).url(detailTarget).build()), leaf)
    }

    override suspend fun activeLessons(account: Account): List<ActiveLesson> = Protocol.parseLessons(json(
        request(account.domain, "/api/v3/classroom/on-lesson-upcoming-exam", account.sessionId).build(),
    ))

    override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
        try { return checkInRequest(account, lesson) }
        catch (error: LessonEntryFailure) {
            // Match the public web client's string ID only for this reported rejection.
            // Keep the account, site, source and ID value fixed; never retry other denials.
            if (error.httpStatus != 400 || error.code != 50400 || lesson.requestId.isString ||
                error.detail !in listOf(null, LessonEntryDetail.STRING_TYPE, LessonEntryDetail.PARAMETERS)) throw error
        }
        currentCoroutineContext().ensureActive()
        return checkInRequest(account, lesson.copy(requestId = JsonPrimitive(lesson.requestId.content)))
    }

    private suspend fun checkInRequest(account: Account, lesson: ActiveLesson): LessonAccess {
        val body = buildJsonObject { put("source", 21); put("lessonId", lesson.requestId) }
        val req = request(account.domain, "/api/v3/lesson/checkin", account.sessionId)
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        return response(req, lessonEntry = lesson).use { result ->
            val root = readJson(result)
            val data = try { Protocol.data(root) }
            catch (_: ApiRejected) { throw entryFailure(result.code, root, lesson) }
            val token = data.text("lessonToken").takeIf { it.isNotBlank() }
                ?: throw NetworkFailure("签到未返回课堂连接凭据", ConnectionIssue.MISSING_ACCESS)
            LessonAccess(lesson, token, result.header("Set-Auth"))
        }
    }

    private fun lessonRequest(account: Account, access: LessonAccess, path: String) = request(account.domain, path, account.sessionId)
        .apply { access.bearer?.let { header("Authorization", "Bearer $it") } }

    override suspend fun presentation(account: Account, access: LessonAccess, id: String): List<Question> {
        val base = url(account.domain, "/api/v3/lesson/presentation/fetch").newBuilder().addQueryParameter("presentation_id", id).build()
        val request = lessonRequest(account, access, "/api/v3/lesson/presentation/fetch").url(base).build()
        return QuestionProtocol.parse(json(request), id)
    }

    private suspend fun action(account: Account, access: LessonAccess, path: String, body: JsonObject) {
        val request = lessonRequest(account, access, path).post(body.toString().toRequestBody(JSON_TYPE)).build()
        client.newCall(request).await().use { response ->
            if (response.code == 401 || response.code == 403) throw SessionExpired()
            val root = if (response.isSuccessful) readJson(response) else withContext(Dispatchers.IO) {
                try { protocolJson.parseToJsonElement(response.peekBody(16_384).string()) as? JsonObject }
                catch (_: IOException) { null }
                catch (_: kotlinx.serialization.SerializationException) { null }
            }
            val code = (root?.get("code") as? JsonPrimitive)?.intOrNull
            val failure = ActionFailure(response.code, code, LessonEntryDetail.fromMessage(root?.text("msg").orEmpty()))
            if (root != null) {
                try { Protocol.checkResult(root) }
                catch (_: ApiRejected) { throw failure }
            }
            // HTTP errors and missing business confirmation must never count as success.
            if (!response.isSuccessful || code != 0) throw failure
        }
    }
    override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) =
        action(account, access, "/api/v3/lesson/problem/answer", QuestionProtocol.answer(question, answer, now))

    override suspend fun danmu(account: Account, access: LessonAccess, content: String) =
        action(account, access, "/api/v3/lesson/danmu/send", buildJsonObject {
            put("lessonId", access.lesson.requestId); put("target", ""); put("userName", ""); put("message", content); put("extra", "")
            put("requiredCensor", false); put("wordCloud", true); put("showStatus", true); put("fromStart", "50")
        })
    override suspend fun bonus(account: Account, access: LessonAccess, id: String) =
        action(account, access, "/api/v3/lesson/redenvelope/prepare", buildJsonObject {
            put("lessonId", access.lesson.requestId); put("redEnvelopeId", identifier(id))
        })

    private suspend fun exchange(domain: String, userId: JsonElement, auth: String): Account {
        val body = buildJsonObject { put("UserID", userId); put("Auth", auth) }
        val req = request(domain, "/pc/web_login")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        val session = response(req).use { result ->
            Cookie.parseAll(req.url, result.headers).firstOrNull { it.name == "sessionid" }?.value
                ?: throw NetworkFailure("登录未返回会话，请重新获取二维码")
        }
        return account(domain, session)
    }

    fun qrLogin(domain: String): Flow<LoginState> = callbackFlow {
        trySend(LoginState.Connecting)
        val exchanging = AtomicBoolean(false)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(QR_REQUEST) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = try { protocolJson.parseToJsonElement(text).jsonObject }
                catch (_: Exception) { close(NetworkFailure("登录消息格式异常")); return }
                when (root.text("op")) {
                    "requestlogin" -> launch {
                        try {
                            val imageUrl = root.text("ticket").toHttpUrl()
                            require(imageUrl.isHttps) { "二维码地址必须使用 HTTPS" }
                            // QR image fetches never carry the user's cookies or lesson credentials.
                            val image = withContext(Dispatchers.IO) { client.newBuilder().followRedirects(true).build()
                                .newCall(Request.Builder().url(imageUrl).build()).await().use { imageResponse ->
                                    if (!imageResponse.isSuccessful || (imageResponse.body?.contentLength() ?: 0) > 2_000_000) {
                                        throw NetworkFailure("二维码暂时无法加载")
                                    }
                                    val bytes = imageResponse.body?.bytes() ?: throw NetworkFailure("二维码为空")
                                    if (bytes.size > 2_000_000) throw NetworkFailure("二维码图片过大")
                                    bytes
                                } }
                            send(LoginState.QrCode(image))
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { close(NetworkFailure("二维码加载失败，请重试")) }
                    }
                    "loginsuccess" -> if (exchanging.compareAndSet(false, true)) launch {
                        try {
                            val id = root["UserID"] ?: throw NetworkFailure("登录消息缺少用户信息")
                            val account = exchange(domain, id, root.text("Auth"))
                            send(LoginState.Success(account))
                            webSocket.close(1000, "complete")
                            close()
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { close(NetworkFailure("登录验证失败，请重新登录")) }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                if (!exchanging.get()) close(NetworkFailure("登录连接已断开，请重试"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!exchanging.get()) close(NetworkFailure("二维码连接已结束，请重新获取"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }
        val socket = client.newWebSocket(request(domain, "/wsapp/").build(), listener)
        val refresh = launch { while (isActive) { delay(55_000); socket.send(QR_REQUEST) } }
        awaitClose { refresh.cancel(); socket.cancel() }
    }

    override fun frames(account: Account, access: LessonAccess): Flow<LessonFrame> = callbackFlow {
        // A successful HTTP upgrade does not prove that the lesson hello was accepted.
        val handshake = launch {
            delay(handshakeTimeoutMillis)
            close(ClassroomConnectionFailure(ConnectionIssue.HANDSHAKE))
        }
        val confirmed = AtomicBoolean(false)
        val req = request(account.domain, "/wsapp/", account.sessionId)
            .apply { access.bearer?.let { header("Authorization", "Bearer $it") } }.build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(Protocol.hello(account, access))) close(ClassroomConnectionFailure(ConnectionIssue.CLOSED))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frames = Protocol.decodeFrame(text, access.lesson, account.name)
                    // Valid lesson traffic also proves readiness when hello is omitted or delayed.
                    if (frames.isNotEmpty() && confirmed.compareAndSet(false, true)) {
                        handshake.cancel()
                        if (trySend(LessonFrame.Connected).isFailure) { close(ClassroomConnectionFailure(ConnectionIssue.QUEUE)); return }
                    }
                    for (frame in frames) {
                        if (frame == LessonFrame.Connected) continue
                        if (trySend(frame).isFailure) { close(ClassroomConnectionFailure(ConnectionIssue.QUEUE)); return }
                        if (frame is LessonFrame.Finished) {
                            webSocket.close(1000, "finished")
                            close()
                        }
                    }
                } catch (_: Exception) { close(ClassroomConnectionFailure(ConnectionIssue.FORMAT)) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                response?.close()
                val issue = when {
                    code == 401 || code == 403 -> ConnectionIssue.CREDENTIALS
                    code != null -> ConnectionIssue.HTTP
                    t is IOException -> transportIssue(t)
                    else -> ConnectionIssue.NETWORK
                }
                // A rejected lesson token is not proof that the account cookie expired.
                close(ClassroomConnectionFailure(issue, code))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 1000 || code == 1001) close()
                else close(ClassroomConnectionFailure(ConnectionIssue.CLOSED, code))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
        awaitClose { handshake.cancel(); socket.cancel() }
    }

    /**
     * The scanned code is forwarded verbatim. Only a check-in code answers with a lesson id; a login
     * code answers with type=app_web_login and must never be treated as attendance.
     */
    override suspend fun dispatchScan(account: Account, raw: String): ScanDispatch {
        val request = request(account.domain, "/api/v3/app/scan", account.sessionId)
            .removeHeader("xt-agent").header("x-client", "app").header("xtbz", "ykt")
            .post(buildJsonObject { put("url", raw) }.toString().toRequestBody(JSON_TYPE)).build()
        val root = json(request)
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
        val data = root["data"] as? JsonObject
        val type = data?.text("type").orEmpty()
        val value = data?.get("value") as? JsonPrimitive
        if (type == LOGIN_CODE_TYPE) throw AttendanceRejected(AttendanceIssue.LOGIN_CODE, code)
        if (code != 0) throw AttendanceRejected(attendanceIssue(code), code)
        if (value == null || value.contentOrNull.isNullOrBlank()) {
            throw AttendanceRejected(attendanceIssue(code), code)
        }
        return ScanDispatch.CheckIn(value)
    }

    /** Same two-step protocol as the official client: source 21 plus joinIfNotIn. */
    override suspend fun signIn(account: Account, lessonId: JsonPrimitive): SignInReceipt {
        val body = buildJsonObject { put("source", 21); put("lessonId", lessonId); put("joinIfNotIn", true) }
        val request = request(account.domain, "/api/v3/lesson/checkin", account.sessionId)
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        return client.newCall(request).await().use { result ->
            if (result.code == 401 || result.code == 403) throw SessionExpired()
            val text = withContext(Dispatchers.IO) {
                try { (if (result.isSuccessful) result.body?.string() else result.peekBody(16_384).string()).orEmpty() }
                catch (_: IOException) { "" }
            }
            val root = try { protocolJson.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            val code = (root?.get("code") as? JsonPrimitive)?.intOrNull
            // Never treat a bare HTTP success as a completed check-in.
            if (result.isSuccessful && code == 0) {
                val data = root["data"] as? JsonObject
                SignInReceipt(lessonId.content, data?.text("lessonToken").orEmpty(),
                    data?.text("className").takeUnless { it.isNullOrBlank() } ?: data?.text("courseName").orEmpty())
            } else throw AttendanceRejected(attendanceIssue(code), code)
        }
    }

    private fun attendanceIssue(code: Int?): AttendanceIssue = when (code) {
        51203 -> AttendanceIssue.EXPIRED
        51201 -> AttendanceIssue.MALFORMED
        50033 -> AttendanceIssue.NOT_FOUND
        50004 -> AttendanceIssue.FINISHED
        50025, 50029 -> AttendanceIssue.RATE_LIMIT
        50000, 50002, 50003 -> AttendanceIssue.DENIED
        else -> AttendanceIssue.UNKNOWN
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val LOGIN_CODE_TYPE = "app_web_login"
        private val QR_REQUEST = buildJsonObject {
            put("op", "requestlogin"); put("role", "web"); put("version", 1.4)
            put("type", "qrcode"); put("from", "web")
        }.toString()
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(requestFailure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, resource, _ -> resource.close() })
        }
    })
}

private fun transportIssue(error: IOException): ConnectionIssue = when (error) {
    is UnknownHostException -> ConnectionIssue.DNS
    is SSLException -> ConnectionIssue.TLS
    is SocketTimeoutException -> ConnectionIssue.TIMEOUT
    is ConnectException -> ConnectionIssue.CONNECT
    else -> ConnectionIssue.NETWORK
}

private fun requestFailure(error: IOException): NetworkFailure =
    transportIssue(error).let { NetworkFailure(it.summary, it) }
