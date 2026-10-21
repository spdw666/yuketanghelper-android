package cn.yukehelper.core

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Fill-in-the-blank (problemType 4) answers are one array entry per blank, unlike short answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class FillBlankTest {
    private val account = Account("1", "本人", "pro.yuketang.cn", "test")
    private val access = LessonAccess(ActiveLesson(12, "7", "数学"), "token", null)
    private fun root(text: String) = Json.parseToJsonElement(text).jsonObject
    private val unlock = root("""{"op":"unlockproblem","problem":{"prob":52,"sid":53,"pres":9,"limit":60}}""")

    private fun question(blanks: Int = 3) = Question(
        id = "52", slideId = "53", presentation = "9", type = Question.FILL_BLANK,
        options = emptyList(), maxChoices = 0, cover = "https://example.com/slide.jpg",
        limitSeconds = 60.0, sentAt = 100_000, blankCount = blanks,
    )

    private class Gateway(var q: Question) : AutomationGateway {
        var posts = 0
        var answer: Answer? = null
        override suspend fun presentation(account: Account, access: LessonAccess, id: String) = listOf(q)
        override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) {
            posts++; this.answer = answer
        }
        override suspend fun danmu(account: Account, access: LessonAccess, content: String) = Unit
        override suspend fun bonus(account: Account, access: LessonAccess, id: String) = Unit
    }

    private inner class Fixture(scope: TestScope, q: Question = question()) {
        val gateway = Gateway(q)
        var settings = CourseSettings(fill = AnswerMode.AI)
        var keys = AiSettings(listOf(AiKey("test", AiProvider.QWEN, "test-key")))
        var provider: AiAnswerer = AiAnswerer { _, _ -> Answer.Blanks(listOf("甲", "乙", "丙")) }
        val events = mutableListOf<ClassroomEvent>()
        val engine = LessonAutomation(scope.backgroundScope, account, access, gateway,
            AiAnswerer { question, key -> provider.answer(question, key) }, { settings }, { keys }, { events.add(it) },
            { 100_000 + scope.testScheduler.currentTime }, Random(5))
    }

    @Test fun presentationParsesTheBlankCountInOrder() {
        val parsed = QuestionProtocol.parse(root("""
            {"code":0,"data":{"slides":[{"id":53,"problem":{"problemId":52,"problemType":4,
            "blanks":[{},{},{}],"limit":60,"sendTime":100000}}]}}
        """.trimIndent()), "9").single()
        assertEquals(Question.FILL_BLANK, parsed.type)
        assertEquals(3, parsed.blankCount)
        assertEquals(3, parsed.blanks)
        // A slide without the array still needs a single value rather than a zero-length answer.
        assertEquals(1, question(0).blanks)
    }

    @Test fun fillBlankPayloadIsOneArrayEntryPerBlank() {
        val payload = QuestionProtocol.answer(question(3), Answer.Blanks(listOf("甲", "", "丙")), 1_234)
        assertEquals(Question.FILL_BLANK, payload["problemType"]!!.jsonPrimitive.int)
        val result = payload["result"]!!.jsonArray
        assertEquals(3, result.size)
        assertEquals(listOf("甲", "", "丙"), result.map { it.jsonPrimitive.content })
        // Padding and truncation keep the server-visible length equal to the blank count.
        val padded = QuestionProtocol.answer(question(3), Answer.Blanks(listOf("甲")), 1)["result"]!!.jsonArray
        assertEquals(listOf("甲", "", ""), padded.map { it.jsonPrimitive.content })
        val truncated = QuestionProtocol.answer(question(2), Answer.Blanks(listOf("甲", "乙", "丙")), 1)["result"]!!.jsonArray
        assertEquals(listOf("甲", "乙"), truncated.map { it.jsonPrimitive.content })
    }

    @Test fun shortAnswerStillUsesTheObjectPayload() {
        val payload = QuestionProtocol.answer(question().copy(type = Question.SHORT_ANSWER, blankCount = 0), Answer.Text("答案"), 1)
        assertEquals("答案", payload["result"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(payload["result"]!!.jsonObject["pics"] is JsonArray)
    }

    @Test fun aiPromptAsksForOneLinePerBlankAndTheParserMapsLines() {
        assertTrue(AiClient.prompt(question(3)).contains("3 blank"))
        assertEquals(Answer.Blanks(listOf("甲", "乙", "丙")), AiClient.parse(question(3), "甲\n乙\n丙"))
        assertEquals(Answer.Blanks(listOf("甲", "乙", "")), AiClient.parse(question(3), "甲\n乙"))
        // List numbering is stripped only when it is a real marker; a decimal answer survives.
        assertEquals(Answer.Blanks(listOf("甲", "乙", "丙")), AiClient.parse(question(3), "1. 甲\n2. 乙\n3、 丙"))
        assertEquals(Answer.Blanks(listOf("1.5", "", "")), AiClient.parse(question(3), "1.5"))
        assertThrows(IllegalArgumentException::class.java) { AiClient.parse(question(3), "   ") }
    }

    @Test fun aiBlankValuesAreSubmittedForEveryBlank() = runTest {
        val f = Fixture(this)
        f.engine.accept(unlock); runCurrent()
        assertEquals(1, f.gateway.posts)
        assertEquals(Answer.Blanks(listOf("甲", "乙", "丙")), f.gateway.answer)
    }

    @Test fun missingKeysFallBackToAnEmptyValuePerBlankInsteadOfSkippingTheQuestion() = runTest {
        val f = Fixture(this); f.keys = AiSettings()
        f.engine.accept(unlock); runCurrent()
        assertEquals(1, f.gateway.posts)
        assertEquals(Answer.Blanks(listOf("", "", "")), f.gateway.answer)
        assertTrue(f.events.any { "回退" in it.summary })
    }

    @Test fun aNonBlankAiAnswerIsDiscardedInsteadOfGuessed() = runTest {
        val f = Fixture(this)
        f.provider = AiAnswerer { _, _ -> Answer.Choices(listOf("A")) }
        f.engine.accept(unlock); runCurrent()
        assertEquals(1, f.gateway.posts)
        assertEquals(Answer.Blanks(listOf("", "", "")), f.gateway.answer)
        assertTrue(f.events.any { "不是填空格式" in it.summary })
    }

    @Test fun fillBlankOffSubmitsNothing() = runTest {
        val f = Fixture(this); f.settings = CourseSettings()
        f.engine.accept(unlock); runCurrent()
        assertEquals(0, f.gateway.posts)
        assertTrue(f.events.any { "自动答题已关闭" in it.summary })
    }

    @Test fun configurationRoundTripsFillAndDefaultsOldDocumentsToOff() {
        val config = Configuration(profiles = listOf(AccountProfile(account)), courseSettings = CourseSettings(fill = AnswerMode.AI))
        assertEquals(AnswerMode.AI, ConfigurationCodec.decode(ConfigurationCodec.encode(config)).courseSettings.fill)

        val legacy = ConfigurationCodec.decode("""
            {"version":5,"courseSettings":{"single":"OFF","multiple":"OFF","vote":"OFF","short":"OFF"},
             "accounts":[{"id":"1","name":"甲","domain":"pro.yuketang.cn","session":"s"}]}
        """.trimIndent())
        assertEquals(AnswerMode.OFF, legacy.courseSettings.fill)
        assertEquals(AnswerMode.OFF, legacy.courseSettings.mode(Question.FILL_BLANK))

        // A random value is not a plausible fill-in answer, so a hand-edited config cannot enable it.
        val random = ConfigurationCodec.decode("""
            {"version":5,"courseSettings":{"fill":"RANDOM"},
             "accounts":[{"id":"1","name":"甲","domain":"pro.yuketang.cn","session":"s"}]}
        """.trimIndent())
        assertEquals(AnswerMode.OFF, random.courseSettings.fill)
        assertEquals(AnswerMode.AI, ConfigurationCodec.decode("""
            {"version":5,"courseSettings":{"fill":"AI"},
             "accounts":[{"id":"1","name":"甲","domain":"pro.yuketang.cn","session":"s"}]}
        """.trimIndent()).courseSettings.mode(Question.FILL_BLANK))
    }
}
