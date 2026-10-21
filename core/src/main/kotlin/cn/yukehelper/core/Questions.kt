package cn.yukehelper.core

import kotlinx.serialization.json.*

data class Question(
    val id: String, val slideId: String, val presentation: String, val type: Int,
    val options: List<String>, val maxChoices: Int, val cover: String,
    val limitSeconds: Double = 0.0, val sentAt: Long = 0, val answered: Boolean = false,
    val originalId: JsonPrimitive? = null,
    /** Fill-in-the-blank only: how many blanks the slide declares, in display order. */
    val blankCount: Int = 0,
) {
    val requestId: JsonPrimitive get() = originalId ?: identifier(id)
    /** A fill-in question always needs at least one value, even when the slide omits the array. */
    val blanks: Int get() = if (type == FILL_BLANK) blankCount.coerceAtLeast(1) else 0
    fun deadline(): Long? = if (limitSeconds > 0 && sentAt > 0) sentAt + (limitSeconds * 1000).toLong() else null

    companion object {
        const val SINGLE = 1
        const val MULTIPLE = 2
        const val VOTE = 3
        const val FILL_BLANK = 4
        const val SHORT_ANSWER = 5
        val supported = setOf(SINGLE, MULTIPLE, VOTE, FILL_BLANK, SHORT_ANSWER)
    }
}

sealed interface Answer {
    data class Choices(val options: List<String>) : Answer
    data class Text(val content: String) : Answer
    /** One value per blank, in slide order; an empty string leaves that blank unanswered. */
    data class Blanks(val values: List<String>) : Answer
}

interface AutomationGateway {
    suspend fun presentation(account: Account, access: LessonAccess, id: String): List<Question>
    suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long)
    suspend fun danmu(account: Account, access: LessonAccess, content: String)
    suspend fun bonus(account: Account, access: LessonAccess, id: String)
}

internal fun identifier(value: String): JsonPrimitive = value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)

object QuestionProtocol {
    fun parse(root: JsonObject, presentation: String): List<Question> =
        (Protocol.data(root)["slides"] as? JsonArray).orEmpty().mapNotNull { element ->
            val slide = element as? JsonObject ?: return@mapNotNull null
            val p = slide["problem"] as? JsonObject ?: return@mapNotNull null
            val id = p.text("problemId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val options = (p["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("key")?.takeIf(String::isNotBlank) }.distinct()
            val type = p.text("problemType").toIntOrNull() ?: 0
            val max = when (type) {
                Question.SINGLE -> 1
                Question.VOTE -> (p.text("pollingCount").toIntOrNull() ?: 1).coerceAtLeast(1)
                else -> options.size
            }
            Question(id, slide.text("id"), presentation, type, options, max.coerceAtMost(options.size), slide.text("cover"),
                p.text("limit").toDoubleOrNull() ?: 0.0, p.text("sendTime").toDoubleOrNull()?.toLong() ?: 0L,
                p["result"] != null && p["result"] != JsonNull, p["problemId"] as? JsonPrimitive,
                // Fill-in-the-blank slides list one entry per blank; that order is the answer order.
                if (type == Question.FILL_BLANK) (p["blanks"] as? JsonArray)?.size ?: 0 else 0)
        }

    fun answer(q: Question, answer: Answer, now: Long) = buildJsonObject {
        put("problemId", q.requestId); put("problemType", q.type); put("dt", now)
        put("result", when {
            // A fill-in-the-blank answer is one array entry per blank; a short answer is an object.
            q.type == Question.FILL_BLANK -> JsonArray(when (answer) {
                is Answer.Blanks -> (0 until q.blanks).map { index ->
                    JsonPrimitive(answer.values.getOrElse(index) { "" })
                }
                is Answer.Text -> (0 until q.blanks).map { index ->
                    JsonPrimitive(if (index == 0) answer.content else "")
                }
                is Answer.Choices -> (0 until q.blanks).map { JsonPrimitive("") }
            })
            q.type == Question.SHORT_ANSWER -> buildJsonObject {
                put("content", (answer as? Answer.Text)?.content
                    ?: (answer as? Answer.Blanks)?.values?.firstOrNull().orEmpty())
                put("pics", buildJsonArray { add(buildJsonObject { put("pic", ""); put("thumb", "") }) })
            }
            answer is Answer.Choices -> JsonArray(answer.options.map { JsonPrimitive(it) })
            else -> JsonArray(emptyList())
        })
    }
}
