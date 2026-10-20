package cn.yukehelper.core

import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

enum class HomeworkType { QUIZ, EXERCISE }

data class Homework(
    val id: String,
    val courseId: String,
    val course: String,
    val title: String,
    val type: HomeworkType,
    val requirements: String = "",
    val deadline: Long? = null,
    val deadlineKnown: Boolean = false,
    val published: Long? = null,
    val leafId: String = "",
    val skuId: String = "",
    val detailsLoaded: Boolean = false,
    val listed: Boolean = true,
) {
    val key get() = "$courseId|${type.name}|$id"
    fun expired(now: Long) = deadline?.let { it <= now } == true
    fun deadlineLabel(): String = when {
        !deadlineKnown -> "截止时间待核对"
        deadline == null -> "老师未设置截止时间"
        else -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
            .format(Instant.ofEpochMilli(deadline)) + "（北京时间）"
    }
    fun requirementsLabel() = requirements.ifBlank {
        if (detailsLoaded) "接口未提供文字要求，请到官方雨课堂查看完整题目、图片和附件。"
        else "详细要求尚未同步成功，请重试或到官方雨课堂查看。"
    }
}

data class HomeworkPage(val items: List<Homework>, val hasMore: Boolean)
data class HomeworkScan(val items: List<Homework>, val partial: Boolean, val detailFailures: Int)

interface HomeworkGateway {
    suspend fun homeworkPage(account: Account, course: Course, page: Int): HomeworkPage
    suspend fun homeworkDetail(account: Account, homework: Homework): Homework
}

/** A failed detail request must not erase the last successful requirements or deadline. */
class HomeworkSynchronizer(private val gateway: HomeworkGateway, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun scan(account: Account, course: Course, previous: List<Homework>): HomeworkScan {
        val old = previous.associateBy { it.key }
        val items = linkedMapOf<String, Homework>()
        var more = true
        var page = 0
        var failures = 0
        while (more && page < MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val result = gateway.homeworkPage(account, course, page++)
            for (item in result.items) {
                val prior = old[item.key]
                var value = if (prior == null) item else item.copy(
                    requirements = item.requirements.ifBlank { prior.requirements },
                    deadline = if (item.deadlineKnown) item.deadline else prior.deadline,
                    deadlineKnown = item.deadlineKnown || prior.deadlineKnown,
                    published = item.published ?: prior.published,
                    skuId = item.skuId.ifBlank { prior.skuId },
                    detailsLoaded = prior.detailsLoaded,
                )
                // Refresh open work even when the list has no revision timestamp.
                if (!value.expired(now()) || prior == null || !prior.detailsLoaded) {
                    try { value = gateway.homeworkDetail(account, value) }
                    catch (e: CancellationException) { throw e }
                    catch (e: SessionExpired) { throw e }
                    catch (_: Exception) { failures++ }
                }
                items[value.key] = value
            }
            more = result.hasMore
        }
        if (!more) previous.filter { it.key !in items }.forEach { items[it.key] = it.copy(listed = false) }
        return HomeworkScan(items.values.toList(), more, failures)
    }
    companion object { const val MAX_PAGES = 10; const val INTERVAL = 5 * 60 * 1000L }
}

object HomeworkChanges {
    fun events(previous: List<Homework>, current: List<Homework>, now: Long): List<ClassroomEvent> {
        val old = previous.associateBy { it.key }
        return current.distinctBy { it.key }.mapNotNull { item ->
            val prior = old[item.key]
            if (!item.listed || item.expired(now) || item.published?.let { it > now } == true) return@mapNotNull null
            val changed = prior != null && (prior.title != item.title || prior.requirements != item.requirements ||
                prior.deadline != item.deadline || prior.deadlineKnown != item.deadlineKnown || !prior.listed)
            if (prior != null && !changed) return@mapNotNull null
            val summary = "${if (prior == null) "新作业" else "作业更新"}：${item.title.take(120)}\n" +
                "截止：${item.deadlineLabel()}\n要求：${item.requirementsLabel().take(220)}"
            ClassroomEvent(EventKind.HOMEWORK, summary, item.course, at = now, homeworkKey = item.key)
        }
    }
}

object HomeworkCodec {
    fun encode(value: Homework): String = buildJsonObject {
        put("id", value.id); put("courseId", value.courseId); put("course", value.course)
        put("title", value.title); put("type", value.type.name); put("requirements", value.requirements)
        put("deadline", value.deadline?.let(::JsonPrimitive) ?: JsonNull); put("deadlineKnown", value.deadlineKnown)
        put("published", value.published?.let(::JsonPrimitive) ?: JsonNull)
        put("leafId", value.leafId); put("skuId", value.skuId); put("detailsLoaded", value.detailsLoaded)
        put("listed", value.listed)
    }.toString()
    fun decode(text: String): Homework {
        val r = protocolJson.parseToJsonElement(text).jsonObject
        return Homework(r.text("id"), r.text("courseId"), r.text("course"), r.text("title"),
            HomeworkType.valueOf(r.text("type")), r.text("requirements"), (r["deadline"] as? JsonPrimitive)?.longOrNull,
            (r["deadlineKnown"] as? JsonPrimitive)?.booleanOrNull == true, (r["published"] as? JsonPrimitive)?.longOrNull,
            r.text("leafId"), r.text("skuId"), (r["detailsLoaded"] as? JsonPrimitive)?.booleanOrNull == true,
            (r["listed"] as? JsonPrimitive)?.booleanOrNull != false)
    }
}

object HomeworkProtocol {
    fun data(root: JsonObject): JsonObject {
        Protocol.checkResult(root)
        val error = (root["errcode"] as? JsonPrimitive)?.intOrNull
        if (error != null && error != 0) {
            if (error in listOf(401, 403)) throw SessionExpired()
            throw ApiRejected(error, "作业接口暂未接受请求（$error）")
        }
        if (root["code"] == null && root["errcode"] == null && root["success"] != JsonPrimitive(true)) {
            throw NetworkFailure("作业接口未返回成功确认")
        }
        return root["data"] as? JsonObject ?: throw NetworkFailure("作业数据格式发生变化")
    }
    fun page(root: JsonObject, course: Course): HomeworkPage {
        val data = data(root)
        val activities = data["activities"] as? JsonArray ?: throw NetworkFailure("作业动态列表格式发生变化")
        val more = (data["has_more"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull?.let { n -> n != 0 }) }
            ?: throw NetworkFailure("作业分页信息缺失")
        val items = activities.mapNotNull { element ->
            val r = element as? JsonObject ?: throw NetworkFailure("作业动态格式异常")
            val type = when ((r["type"] as? JsonPrimitive)?.intOrNull) { 4 -> HomeworkType.QUIZ; 19 -> HomeworkType.EXERCISE; else -> return@mapNotNull null }
            val content = r.obj("content")
            val id = (if (type == HomeworkType.QUIZ) r.text("courseware_id") else content.text("leaf_id"))
                .takeIf { it.isNotBlank() } ?: throw NetworkFailure("作业动态缺少编号")
            val deadlineRoot = if (type == HomeworkType.QUIZ) r else content
            val deadlineKey = if (type == HomeworkType.QUIZ) "deadline" else "score_d"
            val rawDeadline = deadlineRoot[deadlineKey]
            val deadline = timestamp(rawDeadline)
            Homework(id, course.id, course.name, plain(r.text("title")).ifBlank { "未命名作业" }, type,
                requirements(content, r), deadline, deadlineKnown(deadlineRoot, deadlineKey), timestamp(r["create_time"]),
                if (type == HomeworkType.EXERCISE) id else "", content.text("sku_id"))
        }
        return HomeworkPage(items.distinctBy { it.key }, more)
    }
    fun quiz(root: JsonObject, item: Homework): Homework {
        val data = data(root)
        val text = requirements(data)
        val count = (data["problem_count"] as? JsonPrimitive)?.intOrNull
        val limit = (data["limit"] as? JsonPrimitive)?.intOrNull
        val parts = listOfNotNull(text.takeIf { it.isNotBlank() }, count?.let { "共 $it 道题" }, limit?.takeIf { it > 0 }?.let { "作答限时：$it 秒" })
        return item.copy(title = plain(data.text("title")).ifBlank { item.title },
            requirements = parts.joinToString("\n").ifBlank { item.requirements },
            deadline = if (deadlineKnown(data, "deadline")) timestamp(data["deadline"]) else item.deadline,
            deadlineKnown = deadlineKnown(data, "deadline") || item.deadlineKnown, detailsLoaded = true)
    }
    fun leaf(root: JsonObject, item: Homework): Homework {
        val data = data(root)
        return item.copy(title = plain(data.text("name")).ifBlank { item.title },
            requirements = requirements(data, data.obj("content_info")).ifBlank { item.requirements },
            deadline = if (deadlineKnown(data, "score_deadline")) timestamp(data["score_deadline"]) else item.deadline,
            deadlineKnown = deadlineKnown(data, "score_deadline") || item.deadlineKnown,
            skuId = data.text("sku_id").ifBlank { item.skuId })
    }
    fun exercise(root: JsonObject, item: Homework): Homework {
        val data = data(root)
        val problems = data["problems"] as? JsonArray ?: throw NetworkFailure("作业要求列表格式发生变化")
        val intro = requirements(data)
        val parts = problems.take(30).mapIndexedNotNull { index, element ->
            val p = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = p.obj("content")
            val text = requirements(content, p)
            text.takeIf { it.isNotBlank() }?.let { "${index + 1}. $it" }
        }
        val text = bound((listOf(intro).filter(String::isNotBlank) + parts).joinToString("\n\n"), problems.size > 30)
        return item.copy(requirements = text.ifBlank { "共 ${problems.size} 道题；文字要求未返回，请在官方雨课堂查看题目、图片及附件。" }, detailsLoaded = true)
    }
    private fun requirements(vararg roots: JsonObject): String = roots.flatMap { r ->
        listOf("description", "requirement", "requirements", "body", "Body", "context", "remark").map { plain(r.text(it)) }
    }.filter(String::isNotBlank).distinct().joinToString("\n").let { bound(it) }

    private fun bound(text: String, truncated: Boolean = false): String = if (text.length > 12_000 || truncated)
        text.take(11_940) + "\n[内容较长，其余要求请到官方雨课堂查看]" else text

    fun timestamp(value: JsonElement?): Long? {
        val text = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        text.toDoubleOrNull()?.let { n ->
            if (!n.isFinite() || n <= 0 || n > 100_000_000_000_000) return null
            return (if (n < 100_000_000_000) n * 1000 else n).toLong()
        }
        if (text.isBlank()) return null
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        val normalized = text.replace(' ', 'T').replace('/', '-')
        return runCatching { LocalDateTime.parse(normalized).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() }.getOrNull()
    }
    private fun deadlineKnown(root: JsonObject, key: String): Boolean {
        if (key !in root) return false
        val raw = root[key]
        return raw == JsonNull || (raw as? JsonPrimitive)?.contentOrNull in listOf("", "0", "0.0") || timestamp(raw) != null
    }
    fun plain(html: String): String {
        var text = html.take(60_000).replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), "")
            .replace(Regex("(?i)<img\\b[^>]*>"), " [图片，请到雨课堂查看] ")
            .replace(Regex("(?i)<(?:br\\s*/?|/p|/div|/li|/tr|/h[1-6])\\s*>"), "\n")
            .replace(Regex("<[^>]*>"), "")
        text = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(text) { match ->
            val n = match.groupValues[1]
            runCatching { String(Character.toChars(if (n.startsWith("x")) n.drop(1).toInt(16) else n.toInt())) }.getOrDefault("")
        }
        for ((entity, value) in mapOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&amp;" to "&")) text = text.replace(entity, value)
        return bound(text.replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "").replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\n *"), "\n").replace(Regex("\n{3,}"), "\n\n").trim(), html.length > 60_000)
    }
}
