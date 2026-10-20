package cn.yukehelper.core

import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HomeworkTest {
    private val account = Account("1", "测试", "pro.yuketang.cn", "test-session")
    private val course = Course("8", "数学", "一班", "老师")
    private val now = Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun item(id: String = "00042") = Homework(id, "8", "数学", "课后练习", HomeworkType.QUIZ,
        "完成第 1 至 3 题", now + 86_400_000, true, now - 1000, detailsLoaded = true)

    @Test fun officialActivityTypesPreserveIdsAndExcludeLessonsNoticesAndExams() {
        val page = HomeworkProtocol.page(json("""{"errcode":0,"data":{"has_more":true,"activities":[
            {"id":1,"type":3,"title":"上课"},{"type":5,"title":"考试"},{"type":6,"title":"通知"},
            {"type":4,"courseware_id":"00042","title":"<b>练习</b>","deadline":1790000000000,"create_time":1780000000},
            {"type":19,"courseware_id":99,"title":"开放作业","content":{"leaf_id":"00077","sku_id":"008","score_d":1790000000000}},
            {"type":20,"content":{"leaf_id":"exam"}}
        ]}}"""), course)
        assertTrue(page.hasMore)
        assertEquals(listOf("00042", "00077"), page.items.map { it.id })
        assertEquals(listOf(HomeworkType.QUIZ, HomeworkType.EXERCISE), page.items.map { it.type })
        assertEquals("练习", page.items.first().title)
        assertEquals("008", page.items.last().skuId)
        assertEquals(1_790_000_000_000L, page.items.last().deadline)
        assertEquals("8", page.items.first().courseId)
    }
    @Test fun missingOrMalformedListsCannotMasqueradeAsNoHomework() {
        for (body in listOf("""{"errcode":0,"data":{}}""", """{"errcode":0,"data":{"activities":[]}}""",
            """{"data":{"activities":[],"has_more":false}}""", """{"errcode":0,"data":{"activities":[{"type":4}],"has_more":false}}""")) {
            try { HomeworkProtocol.page(json(body), course); fail("Expected malformed data rejection") }
            catch (_: NetworkFailure) { }
        }
    }
    @Test fun legacyApiRejectionsNeverExposeResponseSecrets() {
        try { HomeworkProtocol.data(json("""{"errcode":12345,"msg":"secret-cookie","data":{"token":"secret-token"}}""")); fail("Expected rejection") }
        catch (e: ApiRejected) { assertEquals(12345, e.code); assertFalse(e.message!!.contains("secret")) }
        try { HomeworkProtocol.data(json("""{"errcode":401,"data":{}}""")); fail("Expected expired session") }
        catch (_: SessionExpired) { }
    }
    @Test fun timestampsSupportSecondsMillisecondsAndBeijingLocalAndOffsetStrings() {
        val instant = Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()
        for (raw in listOf(JsonPrimitive(instant), JsonPrimitive(instant / 1000), JsonPrimitive("2026-10-21 08:00:00"),
            JsonPrimitive("2026-10-21T08:00:00+08:00"), JsonPrimitive("2026-10-21T00:00:00Z"))) {
            assertEquals(instant, HomeworkProtocol.timestamp(raw))
        }
        for (raw in listOf(JsonNull, JsonPrimitive(0), JsonPrimitive("NaN"), JsonPrimitive("not-a-time"))) assertNull(HomeworkProtocol.timestamp(raw))
        assertTrue(item().deadlineLabel().contains("2026-10-22 08:00"))
    }
    @Test fun unsetDeadlineDiffersFromUnknownAndInvalidDeadline() {
        fun parse(field: String) = HomeworkProtocol.page(json("""{"errcode":0,"data":{"has_more":false,"activities":[{"type":4,"courseware_id":42$field}]}}"""), course).items.single()
        for (field in listOf(",\"deadline\":0", ",\"deadline\":null", ",\"deadline\":\"\"")) {
            assertTrue(parse(field).deadlineKnown); assertNull(parse(field).deadline)
        }
        assertFalse(parse("").deadlineKnown)
        assertFalse(parse(",\"deadline\":\"broken\"").deadlineKnown)
    }
    @Test fun requirementsArePlainTextWithLineBreaksImageHintsAndDecodedEntities() {
        val text = HomeworkProtocol.plain("<p>要求 &amp; &#x4e00;</p><script>secret-js()</script><style>hide</style><p>第二行<img src='https://example.invalid/x'></p>")
        assertTrue(text.startsWith("要求 & 一\n第二行"))
        assertTrue(text.contains("图片"))
        assertFalse(text.contains("secret-js")); assertFalse(text.contains("<img")); assertFalse(text.contains("hide"))
        assertTrue(HomeworkProtocol.plain("a".repeat(70_000)).length <= 12_000)
    }
    @Test fun leafAndExerciseDetailsSupplyRequirementsAndDeadline() {
        val base = item().copy(type = HomeworkType.EXERCISE, leafId = "77", detailsLoaded = false)
        val leaf = HomeworkProtocol.leaf(json("""{"success":true,"data":{"name":"小论文","score_deadline":"2026-10-22 23:59:00","sku_id":"008","content_info":{"leaf_type_id":"099"}}}"""), base)
        val detail = HomeworkProtocol.exercise(json("""{"success":true,"data":{"problems":[{"content":{"Body":"<p>写一篇 800 字小论文。</p><p>提交 PDF 附件。</p>"}}]}}"""), leaf)
        assertTrue(detail.detailsLoaded)
        assertTrue(detail.requirements.contains("800 字")); assertTrue(detail.requirements.contains("PDF"))
        assertEquals("008", detail.skuId)
        assertTrue(detail.deadlineLabel().contains("2026-10-22 23:59"))
    }
    @Test fun classicQuizCoverIncludesAvailableInstructionsCountAndCorrectLimitUnit() {
        val detail = HomeworkProtocol.quiz(json("""{"errcode":0,"data":{"title":"练习二","description":"<p>请独立完成</p>","problem_count":3,"limit":600,"deadline":0}}"""), item())
        assertEquals("练习二", detail.title)
        assertTrue(detail.requirements.contains("请独立完成")); assertTrue(detail.requirements.contains("3 道题"))
        assertTrue(detail.requirements.contains("600 秒")); assertNull(detail.deadline); assertTrue(detail.deadlineKnown)
    }
    @Test fun cachedRoundTripSuppressesDuplicateNoticesAfterRestart() {
        val first = item()
        assertEquals(1, HomeworkChanges.events(emptyList(), listOf(first, first), now).size)
        val restored = HomeworkCodec.decode(HomeworkCodec.encode(first))
        assertEquals(first, restored)
        assertTrue(HomeworkChanges.events(listOf(restored), listOf(first), now + 1).isEmpty())
        assertEquals("8|QUIZ|00042", restored.key)
    }
    @Test fun onlyMeaningfulChangesProduceUpdatesAndOtherCourseIdsRemainDistinct() {
        val first = item()
        for (changed in listOf(first.copy(title = "新标题"), first.copy(requirements = "新要求"), first.copy(deadline = now + 100_000))) {
            val event = HomeworkChanges.events(listOf(first), listOf(changed), now).single()
            assertTrue(event.summary.startsWith("作业更新")); assertEquals(first.key, event.homeworkKey)
        }
        assertTrue(HomeworkChanges.events(listOf(first), listOf(first.copy(skuId = "new", detailsLoaded = false)), now).isEmpty())
        assertEquals(1, HomeworkChanges.events(listOf(first), listOf(first.copy(courseId = "9")), now).size)
    }
    @Test fun initialExpiredAndFutureUnpublishedHomeworkDoNotNotify() {
        assertTrue(HomeworkChanges.events(emptyList(), listOf(item().copy(deadline = now), item("43").copy(published = now + 1)), now).isEmpty())
        assertEquals(1, HomeworkChanges.events(emptyList(), listOf(item().copy(deadline = null, deadlineKnown = true)), now).size)
    }
    @Test fun scanningPaginatesAndRetainsPriorDetailsWhenDetailFetchFails() = runBlocking {
        val prior = item().copy(skuId = "008")
        val pages = mutableListOf<Int>()
        val gateway = object : HomeworkGateway {
            override suspend fun homeworkPage(account: Account, course: Course, page: Int): HomeworkPage {
                pages += page
                return if (page == 0) HomeworkPage(listOf(prior.copy(requirements = "", deadline = null, deadlineKnown = false, detailsLoaded = false, skuId = "")), true)
                    else HomeworkPage(listOf(item("43")), false)
            }
            override suspend fun homeworkDetail(account: Account, homework: Homework): Homework = throw NetworkFailure("offline")
        }
        val result = HomeworkSynchronizer(gateway) { now }.scan(account, course, listOf(prior))
        assertEquals(listOf(0, 1), pages); assertEquals(2, result.detailFailures); assertFalse(result.partial)
        assertEquals(prior.requirements, result.items.first().requirements)
        assertEquals(prior.deadline, result.items.first().deadline)
        assertEquals(prior.skuId, result.items.first().skuId)
        assertTrue(HomeworkChanges.events(listOf(prior), listOf(result.items.first()), now).isEmpty())
    }
    @Test fun scannerBoundsBrokenPaginationAndPropagatesCancellationAndExpiredSessions() = runBlocking {
        val gateway = object : HomeworkGateway {
            var pages = 0
            override suspend fun homeworkPage(account: Account, course: Course, page: Int): HomeworkPage {
                pages++; return HomeworkPage(listOf(item()), true)
            }
            override suspend fun homeworkDetail(account: Account, homework: Homework) = homework
        }
        assertTrue(HomeworkSynchronizer(gateway) { now }.scan(account, course, emptyList()).partial)
        assertEquals(HomeworkSynchronizer.MAX_PAGES, gateway.pages)
        for (error in listOf(CancellationException("stop"), SessionExpired())) {
            val failing = object : HomeworkGateway by gateway {
                override suspend fun homeworkDetail(account: Account, homework: Homework): Homework = throw error
            }
            try { HomeworkSynchronizer(failing) { now }.scan(account, course, emptyList()); fail("Expected propagation") }
            catch (actual: Exception) { assertSame(error, actual) }
        }
    }
    @Test fun configMigrationAddsHomeworkNotificationsWithoutEnablingDisabledNotificationsOrVoice() {
        val legacy = """{"version":4,"accounts":[],"courseSettings":{"notification":{"enabled":false,"kinds":["QUESTION"]},"voice":{"enabled":false,"kinds":["CALL"]}},"ai":{"keys":[]}}"""
        val restored = ConfigurationCodec.decode(legacy)
        assertFalse(restored.courseSettings.notification.enabled)
        assertEquals(setOf(EventKind.QUESTION, EventKind.HOMEWORK), restored.courseSettings.notification.kinds)
        assertEquals(Subscription(false, setOf(EventKind.CALL)), restored.courseSettings.voice)
        val disabled = restored.withCourseSettings(restored.courseSettings.copy(notification = Subscription(true, emptySet())))
        assertFalse(ConfigurationCodec.decode(ConfigurationCodec.encode(disabled)).courseSettings.notification.accepts(EventKind.HOMEWORK))
    }
    @Test fun completeSnapshotsHideWithdrawnHomeworkButKeepDeduplicationHistory() = runBlocking {
        val gateway = object : HomeworkGateway {
            override suspend fun homeworkPage(account: Account, course: Course, page: Int) = HomeworkPage(emptyList(), false)
            override suspend fun homeworkDetail(account: Account, homework: Homework) = homework
        }
        val prior = item()
        val scan = HomeworkSynchronizer(gateway) { now }.scan(account, course, listOf(prior))
        assertFalse(scan.items.single().listed)
        assertTrue(HomeworkChanges.events(listOf(prior), scan.items, now).isEmpty())
        val persisted = HomeworkCodec.decode(HomeworkCodec.encode(scan.items.single()))
        assertFalse(persisted.listed)
        assertEquals(1, HomeworkChanges.events(listOf(persisted), listOf(prior), now).size)
    }
    @Test fun failedPageDoesNotProduceAnEmptySuccessfulSnapshot() = runBlocking {
        val gateway = object : HomeworkGateway {
            override suspend fun homeworkPage(account: Account, course: Course, page: Int): HomeworkPage = throw NetworkFailure("offline")
            override suspend fun homeworkDetail(account: Account, homework: Homework) = homework
        }
        try { HomeworkSynchronizer(gateway) { now }.scan(account, course, listOf(item())); fail("Expected failure") }
        catch (_: NetworkFailure) { }
    }
}
