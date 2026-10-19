package cn.yukehelper.core

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeepSeekTest {
    private lateinit var server: MockWebServer
    private lateinit var ai: AiClient
    private lateinit var question: Question
    private val key = AiKey("DS", AiProvider.DEEPSEEK, "test-deepseek")
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        ai = client()
        question = Question("42", "43", "9", 2, listOf("A", "B", "C"), 3, server.url("/slide").toString())
    }
    @After fun close() { server.shutdown() }
    private fun client(http: OkHttpClient = OkHttpClient(), allowHttp: Boolean = true) =
        AiClient(http, deepseekBase = server.url("/"), allowHttpImagesForTests = allowHttp)
    private fun image() { server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("fake-slide")) }
    private fun answer(text: String = "A C", finish: String = "stop") {
        server.enqueue(MockResponse().setBody(buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", finish)
                put("message", buildJsonObject { put("content", text); put("reasoning_content", "Ignore this reasoning") })
            }) })
        }.toString()))
    }
    @Test fun visionUsesOfficialChatFormatAndInlineImageWithIsolatedCredentials() = runBlocking {
        image(); answer()
        assertEquals(Answer.Choices(listOf("A", "C")), ai.answer(question, key))
        val download = server.takeRequest()
        assertEquals("/slide", download.path)
        for (header in listOf("Authorization", "Cookie", "x-goog-api-key")) assertNull(download.getHeader(header))
        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer test-deepseek", request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("x-goog-api-key"))
        val raw = request.body.readUtf8()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals("deepseek-flash", root["model"]!!.jsonPrimitive.content)
        assertEquals("disabled", root["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(root["stream"]!!.jsonPrimitive.boolean)
        assertEquals(128, root["max_tokens"]!!.jsonPrimitive.int)
        assertFalse(root.containsKey("chat_template_kwargs"))
        val message = root["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        val parts = message["content"]!!.jsonArray
        assertEquals("image_url", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        val uri = parts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/png;base64,${Base64.getEncoder().encodeToString("fake-slide".toByteArray())}", uri)
        assertTrue(parts[1].jsonObject["text"]!!.jsonPrimitive.content.contains("A, B, C"))
        assertFalse(raw.contains(question.cover)); assertFalse(raw.contains(key.secret))
    }
    @Test fun customVisionModelAndShortAnswerAreSupported() = runBlocking {
        image(); answer("简短答案")
        val custom = AiKey("DS", AiProvider.DEEPSEEK, key.secret, "custom-vision-model")
        assertEquals(Answer.Text("简短答案"), ai.answer(question.copy(type = 5), custom))
        server.takeRequest()
        val root = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(custom.model, root["model"]!!.jsonPrimitive.content)
        assertEquals(2048, root["max_tokens"]!!.jsonPrimitive.int)
    }
    @Test fun truncatedChoiceAndShortAnswersAreRejected() = runBlocking {
        for (q in listOf(question, question.copy(type = 5))) {
            image(); answer(if (q.type == 5) "unfinished" else "A", "length")
            try { ai.answer(q, key); fail("Truncated answer was accepted") }
            catch (_: NetworkFailure) { }
        }
    }
    @Test fun invalidOptionsAndReasoningOnlyResponseAreRejected() = runBlocking {
        for (text in listOf("Z", "The answer is A", "")) {
            image(); answer(text)
            try { ai.answer(question, key); fail("Invalid answer was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun httpErrorsDoNotExposeProviderBodyOrKey() = runBlocking {
        image(); server.enqueue(MockResponse().setResponseCode(401).setBody("provider-secret test-deepseek"))
        try { ai.answer(question, key); fail() }
        catch (e: NetworkFailure) {
            assertFalse(e.message!!.contains("provider-secret")); assertFalse(e.message!!.contains(key.secret))
        }
    }
    @Test fun unsupportedAndOversizedImagesFailBeforeCallingDeepSeek() = runBlocking {
        val responses = listOf(
            MockResponse().setHeader("Content-Type", "text/html").setBody("not a slide"),
            MockResponse().setHeader("Content-Length", 10_000_001),
            MockResponse().setHeader("Content-Type", "image/png").setBody(""),
        )
        for (response in responses) {
            server.enqueue(response)
            try { ai.answer(question, key); fail("Invalid image was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun cleartextSlideIsRejectedInProduction() = runBlocking {
        try { client(allowHttp = false).answer(question, key); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun cancellingWhileReadingImageBodyClosesTheCallPromptly() = runBlocking { verifyCancellation(true) }
    @Test fun cancellingWhileReadingAiBodyClosesTheCallPromptly() = runBlocking { verifyCancellation(false) }
    private suspend fun verifyCancellation(slowImage: Boolean) = coroutineScope {
        val headers = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                if (slowImage || call.request().method == "POST") headers.complete(Unit)
            }
        }).build()
        val slow = MockResponse().setBody("x".repeat(1000))
        if (slowImage) server.enqueue(slow.setHeader("Content-Type", "image/png").throttleBody(1, 100, TimeUnit.MILLISECONDS))
        // MockWebServer throttles request bodies too; delay only the response body for POST.
        else { image(); server.enqueue(slow.setBodyDelay(3, TimeUnit.SECONDS)) }
        val operation = async { client(http).answer(question, key) }
        try {
            withTimeout(5000) { headers.await() }
            delay(50)
            withTimeout(2000) { operation.cancelAndJoin() }
            assertTrue(operation.isCancelled)
        } finally {
            operation.cancel()
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
