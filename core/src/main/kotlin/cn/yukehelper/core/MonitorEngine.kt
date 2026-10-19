package cn.yukehelper.core

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** The polling coordinator owns session jobs; cancelling run() tears down every socket. */
class MonitorEngine(
    private val gateway: ClassroomGateway,
    private val pollMillis: Long = 30_000,
    private val excludedCourses: () -> Set<String> = { emptySet() },
    private val now: () -> Long = System::currentTimeMillis,
    private val automationFactory: ((CoroutineScope, Account, LessonAccess, (ClassroomEvent) -> Unit) -> LessonAutomation)? = null,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun run(account: Account, emit: (MonitorUpdate) -> Unit): Unit = coroutineScope {
        val jobs = mutableMapOf<Long, Job>()
        val finished = ConcurrentHashMap.newKeySet<Long>()
        var failedPolls = 0
        while (isActive) {
            try {
                val lessons = gateway.activeLessons(account).filter { it.classroomId !in excludedCourses() }
                failedPolls = 0
                emit(MonitorUpdate.Poll(lessons, now()))
                val ids = lessons.mapTo(mutableSetOf()) { it.id }
                finished.retainAll(ids)
                for (id in jobs.keys.toList()) {
                    if (id !in ids) {
                        jobs.remove(id)?.cancelAndJoin()
                        emit(MonitorUpdate.Connection(id, false))
                    }
                }
                for (lesson in lessons) {
                    if (lesson.id in finished || jobs[lesson.id]?.isActive == true) continue
                    jobs[lesson.id] = launch {
                        try {
                            monitorLesson(account, lesson, emit)
                            finished.add(lesson.id)
                        } finally { emit(MonitorUpdate.Connection(lesson.id, false)) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: SessionExpired) { throw error }
            catch (_: Exception) {
                failedPolls++
                if (failedPolls == 1 || failedPolls % 5 == 0) emit(MonitorUpdate.Event(
                    ClassroomEvent(EventKind.ERROR, "课堂检查暂未成功，将自动重试", at = now()),
                ))
            }
            delay(pollMillis * minOf(failedPolls.coerceAtLeast(1), 4))
        }
    }

    private suspend fun monitorLesson(account: Account, lesson: ActiveLesson, emit: (MonitorUpdate) -> Unit): Unit = coroutineScope {
        var access: LessonAccess? = null
        var automation: LessonAutomation? = null
        var entered = false
        var failures = 0
        var disconnectedSince: Long? = null
        var lastWarningAt: Long? = null
        var refreshRequested = false
        var accessRejected = false
        var lastQueueWarningAt: Long? = null
        fun event(kind: EventKind, message: String) = emit(MonitorUpdate.Event(
            ClassroomEvent(kind, message, lesson.name, lesson.id, now()),
        ))
        try {
            while (currentCoroutineContext().isActive) {
                var ended = false
                var connected = false
                var stable = false
                var stability: Job? = null
                var reason = ConnectionIssue.CLOSED.summary
                var stage = "获取课堂凭据"
                var entryFailure: LessonEntryFailure? = null
                val attemptStarted = elapsed()
                fun markHealthy() {
                    stable = true
                    failures = 0
                    disconnectedSince = null
                    if (lastWarningAt != null) event(EventKind.SYSTEM, "课堂消息连接已恢复")
                    lastWarningAt = null
                }
                try {
                    if (access == null || refreshRequested) {
                        val previous = access
                        try {
                            access = gateway.checkIn(account, lesson)
                            refreshRequested = false
                            accessRejected = false
                        } catch (error: CancellationException) { throw error }
                        catch (error: SessionExpired) { throw error }
                        catch (error: Exception) {
                            // An optional refresh failure must not destroy a usable connection.
                            // Explicitly rejected access can never be used as a fallback.
                            if (previous == null || accessRejected) throw error
                        }
                        if (!entered) {
                            entered = true
                            event(EventKind.SIGN_IN, "已进入课堂；考勤状态请以雨课堂为准")
                        }
                    }
                    val currentAccess = access ?: throw NetworkFailure("缺少课堂连接凭据", ConnectionIssue.MISSING_ACCESS)
                    stage = "启动课堂消息处理"
                    if (automation == null) automation = automationFactory?.invoke(this, account, currentAccess) { emit(MonitorUpdate.Event(it)) }
                    automation?.updateAccess(currentAccess)
                    stage = "连接课堂消息"
                    gateway.frames(account, currentAccess).collect { frame ->
                        stage = "处理课堂消息"
                        when (frame) {
                            LessonFrame.Connected -> if (!connected) {
                                connected = true
                                emit(MonitorUpdate.Connection(lesson.id, true))
                                // Brief hello/disconnect loops must not reset failure backoff.
                                // Join this job before touching recovery state after collection.
                                stability = launch {
                                    delay(STABLE_CONNECTION_MILLIS)
                                    markHealthy()
                                }
                            }
                            is LessonFrame.Event -> {
                                stability?.cancelAndJoin()
                                markHealthy()
                                emit(MonitorUpdate.Event(frame.event))
                            }
                            is LessonFrame.Signal -> {
                                if (frame.root.text("op") != "hello") {
                                    stability?.cancelAndJoin()
                                    markHealthy()
                                }
                                automation?.accept(frame.root)
                            }
                            LessonFrame.Finished -> ended = true
                        }
                        stage = "连接课堂消息"
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: SessionExpired) { throw error }
                catch (error: Exception) {
                    reason = "$stage：${connectionReason(error)}"
                    if (error is LessonEntryFailure) entryFailure = error
                    if (error is ClassroomConnectionFailure && error.issue == ConnectionIssue.CREDENTIALS) {
                        refreshRequested = true
                        accessRejected = true
                    }
                    if ((error is NetworkFailure && error.issue == ConnectionIssue.QUEUE) ||
                        (error is ClassroomConnectionFailure && error.issue == ConnectionIssue.QUEUE)) {
                        val queueWarningAt = lastQueueWarningAt
                        if (queueWarningAt == null || elapsed() - queueWarningAt >= WARNING_INTERVAL_MILLIS) {
                            event(EventKind.ACTION, "课堂消息处理积压，正在重新同步课堂")
                            lastQueueWarningAt = elapsed()
                        }
                    }
                } finally { stability?.cancelAndJoin() }
                if (ended) return@coroutineScope
                if (stable && !accessRejected) refreshRequested = false
                emit(MonitorUpdate.Connection(lesson.id, false))
                // Normal server rotation after a healthy session reconnects in 1s, silently.
                if (!stable) failures++
                val time = elapsed()
                val outageAt = disconnectedSince ?: (if (stable) time else attemptStarted).also { disconnectedSince = it }
                val warningAt = lastWarningAt
                if ((entryFailure != null || failures >= 3 || time - outageAt >= WARNING_DELAY_MILLIS) &&
                    (warningAt == null || time - warningAt >= WARNING_INTERVAL_MILLIS)) {
                    val rejected = entryFailure
                    event(EventKind.ERROR, if (rejected != null) {
                        "获取课堂凭据未成功（${rejected.message}）；${rejected.advice}。每 60 秒自动重试"
                    } else "课堂消息连接持续中断，正在自动重试（$reason）")
                    lastWarningAt = time
                }
                // Retain the automation instance and deduplication state when refreshing access.
                if (failures > 0 && failures % 3 == 0) refreshRequested = true
                delay(if (entryFailure != null) 60_000 else if (stable) 1_000
                    else (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000))
            }
        } finally { coroutineContext.cancelChildren() }
    }

    private fun connectionReason(error: Exception): String = when (error) {
        is ClassroomConnectionFailure -> error.issue.summary + error.status?.let { "，状态码 $it" }.orEmpty()
        is ApiRejected -> "课堂接口拒绝请求" + error.code?.let { "，代码 $it" }.orEmpty()
        is LessonEntryFailure -> error.message.orEmpty()
        is NetworkFailure -> error.issue?.summary?.let { it + error.status?.let { status -> "，HTTP $status" }.orEmpty() }
            ?: "请求未成功，原因尚未识别"
        // Exception messages may contain credentials or arbitrary upstream content.
        else -> "内部处理异常"
    }

    private companion object {
        const val STABLE_CONNECTION_MILLIS = 10_000L
        const val WARNING_DELAY_MILLIS = 15_000L
        const val WARNING_INTERVAL_MILLIS = 120_000L
    }
}
