package cn.yukehelper.core

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface AiAnswerer { suspend fun answer(question: Question, key: AiKey): Answer }

/** REST adapters use their own client: no classroom cookies are ever sent to AI/image hosts. */
class AiClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build(),
    private val qwenBase: HttpUrl = "https://api-inference.modelscope.cn/v1/".toHttpUrl(),
    private val geminiBase: HttpUrl = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl(),
    private val allowHttpImagesForTests: Boolean = false,
    private val deepseekBase: HttpUrl = "https://api.deepseek.com/".toHttpUrl(),
) : AiAnswerer {
    override suspend fun answer(question: Question, key: AiKey): Answer = withContext(Dispatchers.IO) {
        require(key.secret.isNotBlank())
        val cover = question.cover.toHttpUrl()
        require(cover.isHttps || allowHttpImagesForTests)
        val prompt = prompt(question)
        val builder: Request.Builder
        val payload: JsonObject
        if (key.provider == AiProvider.QWEN || key.provider == AiProvider.DEEPSEEK) {
            val base = if (key.provider == AiProvider.DEEPSEEK) deepseekBase else qwenBase
            // Inline DeepSeek images so the provider does not need access to the classroom CDN.
            val imageUrl = if (key.provider == AiProvider.DEEPSEEK) {
                val image = downloadImage(cover)
                "data:${image.first};base64,${image.second}"
            } else cover.toString()
            builder = Request.Builder().url(base.newBuilder().addPathSegments("chat/completions").build())
                .header("Authorization", "Bearer ${key.secret}")
            payload = buildJsonObject {
                put("model", key.model)
                put("messages", buildJsonArray { add(buildJsonObject {
                    put("role", "user"); put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", imageUrl) }) })
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    })
                }) })
                if (key.provider == AiProvider.DEEPSEEK) {
                    put("thinking", buildJsonObject { put("type", "disabled") })
                    put("stream", false)
                    put("max_tokens", if (question.type == 5) 2048 else 128)
                } else put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
            }
        } else {
            val image = downloadImage(cover)
            builder = Request.Builder().url(geminiBase.newBuilder().addPathSegment("models").addPathSegment("${key.model}:generateContent").build())
                .header("x-goog-api-key", key.secret)
            payload = buildJsonObject {
                put("contents", buildJsonArray { add(buildJsonObject { put("parts", buildJsonArray {
                    add(buildJsonObject { put("inline_data", buildJsonObject { put("mime_type", image.first); put("data", image.second) }) })
                    add(buildJsonObject { put("text", prompt) })
                }) }) })
            }
        }
        val root = readResponse(builder.post(payload.toString().toRequestBody("application/json".toMediaType())).build()) {
            if (!it.isSuccessful) throw NetworkFailure("AI 服务暂不可用")
            protocolJson.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
        }
        val text = if (key.provider != AiProvider.GEMINI) {
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            if (key.provider == AiProvider.DEEPSEEK && choice?.text("finish_reason") != "stop") {
                throw NetworkFailure("AI 未返回完整答案")
            }
            choice?.obj("message")?.text("content").orEmpty()
        } else {
            val content = ((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.obj("content")
            (content?.get("parts") as? JsonArray).orEmpty().joinToString("") { (it as? JsonObject)?.text("text").orEmpty() }
        }
        parse(question, text)
    }

    private suspend fun downloadImage(cover: HttpUrl): Pair<String, String> =
        readResponse(Request.Builder().url(cover).build()) { response ->
            require(response.isSuccessful)
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 10_000_000)
            val source = body.source()
            source.request(10_000_001)
            require(source.buffer.size <= 10_000_000)
            val bytes = source.readByteArray()
            require(bytes.isNotEmpty() && bytes.size <= 10_000_000)
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "image/jpeg"
            require(mime in setOf("image/jpeg", "image/png", "image/webp"))
            mime to Base64.getEncoder().encodeToString(bytes)
        }

    // Keep cancellation attached until the body is consumed, including a slow image/AI body.
    private suspend fun <T> readResponse(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(NetworkFailure("AI 网络连接失败"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(read)
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        fun prompt(q: Question): String {
            if (q.type == 5) return "Read this short-answer question slide. Reply with ONLY a concise answer, nothing else."
            val count = when (q.type) { 1 -> "exactly ONE"; 3 -> "up to ${q.maxChoices}"; else -> "ALL correct" }
            return "Read the question and options from this slide. Choose $count option(s) from ${q.options.joinToString(", ")}. Reply with ONLY option letters separated by spaces, nothing else."
        }
        fun parse(q: Question, raw: String): Answer {
            val text = raw.trim()
            if (q.type == 5) {
                require(text.isNotBlank() && text.length <= 4000) { "AI 未返回有效答案" }
                return Answer.Text(text)
            }
            val map = q.options.associateBy { it.uppercase() }
            val tokens = text.uppercase().split(Regex("[,，\\s]+" )).filter(String::isNotBlank)
            require(tokens.isNotEmpty() && tokens.all { it in map }) { "AI 答案格式无效" }
            val picked = tokens.map { map.getValue(it) }.distinct().take(q.maxChoices)
            require(picked.isNotEmpty()) { "AI 未返回有效选项" }
            return Answer.Choices(picked)
        }
    }
}
