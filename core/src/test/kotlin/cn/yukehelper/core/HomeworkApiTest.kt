package cn.yukehelper.core

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HomeworkApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YuketangApi
    private val account = Account("1", "甲", "pro.yuketang.cn", "test-homework-session")
    @Before fun setup() { server = MockWebServer().apply { start() }; api = YuketangApi(endpoint = { server.url("/") }) }
    @After fun cleanup() { server.shutdown() }

    @Test fun activityPollingUsesOfficialZeroBasedPaginationAndOnlyTheAccountSession() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"errcode":0,"data":{"activities":[],"has_more":false}}"""))
        api.homeworkPage(account, Course("0008", "数学", "", ""), 1)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/api/web/logs/learn/0008?actype=-1&page=1&offset=20&sort=-1", request.path)
        assertEquals("sessionid=test-homework-session", request.getHeader("Cookie"))
        assertEquals("web", request.getHeader("xt-agent")); assertNull(request.getHeader("Authorization"))
    }
    @Test fun quizDetailsReadTheCoverWithoutStartingOrSubmittingHomework() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"errcode":0,"data":{"title":"作业","problem_count":2,"deadline":0}}"""))
        val value = api.homeworkDetail(account, Homework("00042", "0008", "数学", "作业", HomeworkType.QUIZ))
        assertTrue(value.detailsLoaded)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/api/web/quiz/quiz_info?classroom_id=0008&quiz_id=00042", request.path)
        assertEquals(1, server.requestCount)
    }
    @Test fun exerciseDetailsUseTheLeafsExerciseAndSkuIdsWithNoAnswerSubmission() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"name":"写作","sku_id":"0009","score_deadline":"2026-10-22 23:59:00","content_info":{"leaf_type_id":"00099"}}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"problems":[{"content":{"Body":"<p>提交一篇小论文</p>"}}]}}"""))
        val result = api.homeworkDetail(account, Homework("00077", "0008", "语文", "作业", HomeworkType.EXERCISE, leafId = "00077"))
        assertTrue(result.requirements.contains("小论文")); assertTrue(result.deadlineKnown)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("/mooc-api/v1/lms/learn/leaf_info/0008/00077/", first.path)
        assertEquals("/mooc-api/v1/lms/exercise/get_exercise_list/00099/0009/", second.path)
        for (request in listOf(first, second)) { assertEquals("GET", request.method); assertEquals("sessionid=test-homework-session", request.getHeader("Cookie")) }
        assertEquals(2, server.requestCount)
    }
    @Test fun unauthorizedHomeworkRequestsExpireTheSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.homeworkPage(account, Course("8", "数学", "", ""), 0); fail("Expected session expiry") }
        catch (_: SessionExpired) { }
    }
    @Test fun paginationCannotEscapeTheConfiguredLimit() = runBlocking {
        for (page in listOf(-1, HomeworkSynchronizer.MAX_PAGES)) {
            try { api.homeworkPage(account, Course("8", "数学", "", ""), page); fail("Expected invalid page") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, server.requestCount)
    }
}
