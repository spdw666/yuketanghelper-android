package cn.yukehelper.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var ai: AiClient
    private val q = Question("42", "43", "9", 2, listOf("A", "B", "C"), 3, "https://example.com/slide.jpg")
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        ai = AiClient(qwenBase = server.url("/v1/"), geminiBase = server.url("/v1beta/"), allowHttpImagesForTests = true)
    }
    @After fun close() { server.shutdown() }
    @Test fun qwenUsesVisionChatAndDisablesThinkingWithoutClassroomCredentials() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"A C"}}]}"""))
        assertEquals(Answer.Choices(listOf("A", "C")), ai.answer(q, AiKey("q", AiProvider.QWEN, "test-ai")))
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-ai", request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertFalse(root["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals(q.cover, root["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.first().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }
    @Test fun geminiDownloadsImageWithoutKeysAndUsesHeaderForApiKey() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("mockimage"))
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"B"}]}}]}"""))
        assertEquals(Answer.Choices(listOf("B")), ai.answer(q.copy(cover = server.url("/image").toString()), AiKey("g", AiProvider.GEMINI, "test-google")))
        val image = server.takeRequest()
        assertEquals("/image", image.path)
        assertNull(image.getHeader("Cookie")); assertNull(image.getHeader("x-goog-api-key")); assertNull(image.getHeader("Authorization"))
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", request.path)
        assertEquals("test-google", request.getHeader("x-goog-api-key"))
        assertNull(request.getHeader("Cookie")); assertFalse(request.path!!.contains("test-google"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("image/png", root["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.first().jsonObject["inline_data"]!!.jsonObject["mime_type"]!!.jsonPrimitive.content)
    }
    @Test fun nullChoicesAreAFailureRatherThanAnEmptyAnswer() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":null}"""))
        try { ai.answer(q, AiKey("q", AiProvider.QWEN, "test")); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun providerHttpErrorsAreRedacted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("test-secret"))
        try { ai.answer(q, AiKey("q", AiProvider.QWEN, "test")); fail() }
        catch (e: NetworkFailure) { assertFalse(e.message!!.contains("test-secret")) }
    }
    @Test fun answerParserValidatesTokensDeduplicatesAndLimitsVotes() {
        assertEquals(Answer.Choices(listOf("A", "C")), AiClient.parse(q, "a, A c"))
        assertEquals(Answer.Choices(listOf("A")), AiClient.parse(q.copy(type = 3, maxChoices = 1), "A C"))
        assertThrows(IllegalArgumentException::class.java) { AiClient.parse(q, "The answer is A") }
        assertThrows(IllegalArgumentException::class.java) { AiClient.parse(q, "Z") }
        assertThrows(IllegalArgumentException::class.java) { AiClient.parse(q.copy(type = 5), "  ") }
        assertEquals(Answer.Text("简短答案"), AiClient.parse(q.copy(type = 5), " 简短答案 "))
    }
}
