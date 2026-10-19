package cn.yukehelper.core

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** A bounded rolling window; a failed send does not create a tight retry loop. */
class DanmuWindow {
    private val messages = ArrayDeque<Pair<Long, String>>()
    private val sent = mutableMapOf<String, Long>()
    fun receive(content: String, now: Long, threshold: Int): Boolean {
        val key = content.trim().lowercase(Locale.ROOT)
        if (key.isBlank() || content.length > 500) return false
        while (messages.isNotEmpty() && messages.first().first <= now - 60_000) messages.removeFirst()
        sent.entries.removeAll { it.value <= now - 60_000 }
        messages.addLast(now to key)
        while (messages.size > 2000) messages.removeFirst()
        if (sent.containsKey(key) || messages.count { it.second == key } < threshold.coerceAtLeast(1)) return false
        sent[key] = now
        return true
    }
}

class LessonAutomation(
    private val scope: CoroutineScope,
    private val account: Account,
    initialAccess: LessonAccess,
    private val gateway: AutomationGateway,
    private val ai: AiAnswerer,
    private val settings: () -> CourseSettings,
    private val aiSettings: () -> AiSettings,
    private val emit: (ClassroomEvent) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    @Volatile private var access = initialAccess
    @Volatile private var offset = 0L
    private val frames = Channel<JsonObject>(64)
    private val questions = ConcurrentHashMap<String, Question>()
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    private val handled = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val notices = ConcurrentHashMap.newKeySet<String>()
    private val bonusIds = mutableSetOf<String>()
    private val unlocked = linkedSetOf<String>()
    private val hints = mutableMapOf<String, Long>()
    private val danmu = DanmuWindow()
    init {
        scope.launch {
            for (frame in frames) {
                try { process(frame) }
                catch (e: CancellationException) { throw e }
                catch (e: SessionExpired) { throw e }
                catch (_: Exception) { event(EventKind.ERROR, "课堂自动操作暂未成功，将在后续消息中重试") }
            }
        }
    }
    fun updateAccess(value: LessonAccess) { access = value }
    fun accept(root: JsonObject) {
        if (frames.trySend(root).isFailure) throw NetworkFailure("课堂操作队列已满，正在重连", ConnectionIssue.QUEUE)
    }
    private fun event(kind: EventKind, text: String) = emit(ClassroomEvent(kind, text, access.lesson.name, access.lesson.id, now()))
    private fun enabled() = settings().enabled
    private fun serverNow() = now() + offset

    private suspend fun load(presentation: String): Boolean {
        if (presentation.isBlank()) return false
        return try {
            val fresh = withTimeout(4_000) { gateway.presentation(account, access, presentation) }
            fresh.forEach { q ->
                questions[q.id] = q
                if (q.slideId.isNotBlank()) questions[q.slideId] = q
                if (q.answered) handled.add(q.id)
            }
            true
        } catch (e: TimeoutCancellationException) { false }
        catch (e: CancellationException) { throw e }
        catch (e: SessionExpired) { throw e }
        catch (_: Exception) { false }
    }

    private suspend fun process(root: JsonObject) {
        val ref = root.obj("problem")
        val timestamp = root.text("dt").toDoubleOrNull()?.toLong() ?: ref.text("dt").toDoubleOrNull()?.toLong()
        if (timestamp != null && timestamp > 0) offset = maxOf(offset, timestamp - now())
        if (!enabled()) return
        when (root.text("op")) {
            "hello" -> {
                val ids = (root["timeline"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("pres") }
                (ids + root.text("presentation")).filter(String::isNotBlank).distinct().take(64).forEach { load(it) }
            }
            "presentationupdated", "presentationcreated", "showpresentation" -> load(root.text("presentation"))
            "unlockproblem" -> {
                val id = ref.text("prob").ifBlank { ref.text("sid") }
                if (id.isNotBlank()) {
                    unlocked.add(id)
                    val limit = ref.text("limit").toDoubleOrNull() ?: 0.0
                    if (limit > 0) hints[id] = serverNow() + (limit * 1000).toLong()
                    if (questions[id] == null) load(ref.text("pres").ifBlank { root.text("presentation") })
                }
            }
            "newdanmu" -> {
                val content = root.text("danmu").trim()
                val s = settings()
                if (s.autoDanmu && danmu.receive(content, now(), s.danmuThreshold)) scope.launch {
                    try {
                        if (!enabled() || !settings().autoDanmu) return@launch
                        withTimeout(3_000) { gateway.danmu(account, access, content) }
                        event(EventKind.ACTION, "已发送重复弹幕：${content.take(100)}")
                    } catch (e: TimeoutCancellationException) { event(EventKind.ACTION, "自动弹幕发送超时") }
                    catch (e: CancellationException) { throw e }
                    catch (e: SessionExpired) { throw e }
                    catch (_: Exception) { event(EventKind.ACTION, "自动弹幕发送失败") }
                }
            }
            "gainbonus" -> {
                val id = root.obj("redpacket").text("redEnvelopeId").ifBlank { root.text("redEnvelopeId") }
                if (settings().autoBonus && id.isNotBlank() && bonusIds.add(id)) scope.launch {
                    try {
                        if (!enabled() || !settings().autoBonus) return@launch
                        withTimeout(3_000) { gateway.bonus(account, access, id) }
                        event(EventKind.ACTION, "已领取课堂红包")
                    } catch (e: TimeoutCancellationException) { event(EventKind.ACTION, "红包请求超时，领取状态待确认") }
                    catch (e: CancellationException) { throw e }
                    catch (e: SessionExpired) { throw e }
                    catch (_: Exception) { event(EventKind.ACTION, "红包领取失败或已结束") }
                }
            }
        }
        (root["unlockedproblem"] as? JsonArray)?.let { current ->
            unlocked.clear()
            current.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(unlocked::add) }
            if (root.text("op") == "unlockproblem") ref.text("prob").ifBlank { ref.text("sid") }.takeIf(String::isNotBlank)?.let(unlocked::add)
        }
        // Bound server-driven state over long sessions.
        while (unlocked.size > 1000) unlocked.remove(unlocked.first())
        var refreshed = false
        for (id in unlocked.toList()) {
            var q = questions[id]
            if (q?.id in handled || q?.id in claimed || (failures[id] ?: 0) >= 3) continue
            if (q == null) {
                if (!refreshed) { load(root.text("presentation")); refreshed = true }
                q = questions[id]
            }
            if (q == null) {
                failures.merge(id, 1, Int::plus)
                if (notices.add("missing:$id")) event(EventKind.ANSWER, "题目 $id 的课件尚未加载，暂时无法自动答题")
                continue
            }
            schedule(q, hints[id])
        }
    }

    private fun schedule(q: Question, hint: Long?) {
        if (q.answered) { handled.add(q.id); return }
        if (settings().mode(q.type) == AnswerMode.OFF) {
            if (notices.add(q.id)) event(EventKind.ANSWER, "题目 ${q.id}：${if (q.type !in listOf(1, 2, 3, 5)) "暂不支持此题型" else "此题型自动答题已关闭"}")
            return
        }
        if (q.id in handled || (attempts[q.id] ?: 0) >= 3 || (failures[q.id] ?: 0) >= 3 || !claimed.add(q.id)) return
        scope.launch {
            var complete = false
            try { complete = answer(q, hint) }
            catch (e: CancellationException) { throw e }
            catch (e: SessionExpired) { throw e }
            catch (_: Exception) { event(EventKind.ANSWER, "题目 ${q.id} 处理失败，等待后续消息重试") }
            finally {
                if (complete) handled.add(q.id) else failures.merge(q.id, 1, Int::plus)
                claimed.remove(q.id)
            }
        }
    }

    private fun fallback(q: Question): Answer {
        if (q.type == 5) return Answer.Text(" ")
        require(q.options.isNotEmpty() && q.maxChoices > 0)
        val count = if (q.type == 1) 1 else random.nextInt(1, q.maxChoices + 1)
        return Answer.Choices(q.options.shuffled(random).take(count))
    }

    private suspend fun answer(q: Question, hint: Long?): Boolean {
        val s = settings()
        val mode = s.mode(q.type)
        val deadline = q.deadline() ?: hint
        val remaining = deadline?.minus(serverNow())
        if (remaining != null && remaining <= 0) {
            event(EventKind.ANSWER, "题目 ${q.id} 已到截止时间，跳过提交")
            return true
        }
        val started = now()
        val hold = if (s.nearDeadline && remaining != null) (remaining - random.nextLong(10_000, 15_001)).coerceAtLeast(0) else 0L
        val budget = when {
            s.nearDeadline && remaining != null -> hold.coerceAtMost(45_000)
            remaining != null -> (remaining - 4_000).coerceAtLeast(0).coerceAtMost(45_000)
            else -> 45_000L
        }
        var result: Answer? = null
        var source = if (q.type == 5) "空白" else "随机"
        if (mode == AnswerMode.AI) {
            result = withTimeoutOrNull(budget) {
                for (key in aiSettings().ordered()) {
                    try { return@withTimeoutOrNull withTimeout(20_000) { ai.answer(q, key) } }
                    catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Try the next configured key without logging provider errors. */ }
                }
                null
            }
            if (result != null) source = "AI" else event(EventKind.ANSWER, "题目 ${q.id}：AI 无可用结果，使用${source}回退")
        }
        if (result == null) result = fallback(q)
        delay((hold - (now() - started)).coerceAtLeast(0))
        if (!enabled() || settings().mode(q.type) != mode) return false
        // Always refresh before POST, including retries, to protect manual answers.
        while ((attempts[q.id] ?: 0) < 3 && currentCoroutineContext().isActive) {
            if (!enabled() || settings().mode(q.type) != mode) return false
            if (deadline != null && deadline <= serverNow()) return true
            if (!load(q.presentation)) {
                event(EventKind.ANSWER, "题目 ${q.id}：提交前状态检查失败，暂缓提交")
                return false
            }
            val fresh = questions[q.id] ?: return false
            if (fresh.answered || q.id in handled) {
                event(EventKind.ANSWER, "题目 ${q.id} 已有答案，保留现有提交")
                return true
            }
            if (fresh.deadline()?.let { it <= serverNow() } == true) return true
            attempts.merge(q.id, 1, Int::plus)
            try {
                val timeLeft = deadline?.minus(serverNow()) ?: 3000L
                withTimeout(timeLeft.coerceIn(1, 3000)) { gateway.submit(account, access, fresh, result, now()) }
                val display = when (result) { is Answer.Choices -> result.options.joinToString(" "); is Answer.Text -> result.content.trim().ifBlank { "（空白）" } }
                event(EventKind.ANSWER, "题目 ${q.id} 已提交（$source）：${display.take(200)}")
                return true
            } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
            catch (e: CancellationException) { throw e }
            catch (e: SessionExpired) { throw e }
            catch (_: Exception) { /* A fresh server result is required before every retry. */ }
            if ((attempts[q.id] ?: 0) < 3) delay(350)
        }
        event(EventKind.ANSWER, "题目 ${q.id} 提交未确认，已达到 3 次尝试上限")
        return false
    }
}
