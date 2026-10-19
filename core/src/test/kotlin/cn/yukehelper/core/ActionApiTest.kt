package cn.yukehelper.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ActionApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YuketangApi
    private val account = Account("1", "本人", "pro.yuketang.cn", "test-session")
    private val access = LessonAccess(ActiveLesson(12, "7", "数学"), "test-token", "test-bearer")
    private val q = Question("42", "43", "9", 1, listOf("A", "B"), 1, "https://example.com/a.jpg")
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
    @Before fun setup() { server = MockWebServer().apply { start() }; api = YuketangApi(endpoint = { server.url("/") }) }
    @After fun close() { server.shutdown() }
    @Test fun presentationParsesBothIdsDeadlineVotingAndManualResult() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"slides":[{"id":43,"cover":"https://example.com/a.jpg","problem":{"problemId":42,"problemType":3,"options":[{"key":"A"},{"key":"B"}],"pollingCount":1,"limit":60,"sendTime":100000,"result":[]}}]}}"""))
        val question = api.presentation(account, access, "9").single()
        assertEquals("42", question.id); assertEquals("43", question.slideId)
        assertEquals(160_000L, question.deadline()); assertTrue(question.answered)
        assertEquals(1, question.maxChoices)
        val request = server.takeRequest()
        assertEquals("/api/v3/lesson/presentation/fetch?presentation_id=9", request.path)
        assertEquals("Bearer test-bearer", request.getHeader("Authorization"))
        assertEquals("sessionid=test-session", request.getHeader("Cookie"))
    }
    @Test fun choiceAndShortAnswerPayloadsMatchClassroomProtocol() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"code":0}""")) }
        api.submit(account, access, q, Answer.Choices(listOf("B")), 1234)
        val choiceRequest = server.takeRequest()
        assertEquals("/api/v3/lesson/problem/answer", choiceRequest.path)
        val choice = parse(choiceRequest.body.readUtf8())
        assertEquals(42, choice["problemId"]!!.jsonPrimitive.int)
        assertEquals(1234, choice["dt"]!!.jsonPrimitive.int)
        assertEquals("B", choice["result"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Bearer test-bearer", choiceRequest.getHeader("Authorization"))
        api.submit(account, access, q.copy(type = 5), Answer.Text(" "), 1235)
        val short = parse(server.takeRequest().body.readUtf8())["result"]!!.jsonObject
        assertEquals(" ", short["content"]!!.jsonPrimitive.content)
        assertEquals("", short["pics"]!!.jsonArray.single().jsonObject["pic"]!!.jsonPrimitive.content)
    }
    @Test fun danmuAndBonusUseExactEndpointAndFields() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}""")) }
        api.danmu(account, access, "你好")
        val dm = server.takeRequest(); val body = parse(dm.body.readUtf8())
        assertEquals("/api/v3/lesson/danmu/send", dm.path)
        assertEquals("你好", body["message"]!!.jsonPrimitive.content)
        assertEquals("50", body["fromStart"]!!.jsonPrimitive.content)
        assertFalse(body["requiredCensor"]!!.jsonPrimitive.boolean)
        assertTrue(body["wordCloud"]!!.jsonPrimitive.boolean)
        api.bonus(account, access, "88")
        val bonus = server.takeRequest()
        assertEquals("/api/v3/lesson/redenvelope/prepare", bonus.path)
        assertEquals(88, parse(bonus.body.readUtf8())["redEnvelopeId"]!!.jsonPrimitive.int)
    }
    @Test fun unsuccessfulActionsNeverReportSuccess() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":5,"msg":"do not expose secret error data"}"""))
        try { api.bonus(account, access, "88"); fail() }
        catch (e: ApiRejected) { assertFalse(e.message!!.contains("secret")) }
    }
    @Test fun actionsUseTheSameStringLessonIdentifierAsTheAcceptedEntry() = runBlocking {
        val stringAccess = LessonAccess(ActiveLesson(12, "7", "数学", JsonPrimitive("00012")), "test-token", "test-bearer")
        repeat(2) { server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}""")) }
        api.danmu(account, stringAccess, "你好")
        api.bonus(account, stringAccess, "88")
        repeat(2) {
            val request = server.takeRequest()
            val id = parse(request.body.readUtf8())["lessonId"]!!.jsonPrimitive
            assertTrue(id.isString)
            assertEquals("00012", id.content)
            assertEquals("Bearer test-bearer", request.getHeader("Authorization"))
        }
    }
    @Test fun sessionExpiryInsideActionIsPropagated() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":-1,"msg":"登录过期"}"""))
        try { api.submit(account, access, q, Answer.Choices(listOf("A")), 0); fail() }
        catch (_: SessionExpired) { }
    }
}
