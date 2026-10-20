package cn.yukehelper.android

import android.content.Context
import cn.yukehelper.core.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Manual and background refresh share an account lock and the same persisted deduplication. */
class HomeworkSyncManager(context: Context, private val store: AppStore, private val api: YuketangApi) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val notifications = Notifications(context)
    suspend fun sync(account: Account) {
        val key = account.key
        val lock = locks.getOrPut(key) { Mutex() }
        if (!lock.tryLock()) return
        try {
            if (store.profile(key)?.account !== account) return
            store.homeworkStatus(key) { it.copy(loading = true, message = "正在同步作业…") }
            val courses = if (account.demo) DemoGateway.courses else api.courses(account)
            if (store.profile(key)?.account !== account) return
            store.editProfile(key) { it.copy(courses = courses) }
            val selected = courses.filter { store.profile(key)?.selects(it.id) == true }
            var failed = 0
            var incomplete = 0
            var done = 0
            for (course in selected) {
                currentCoroutineContext().ensureActive()
                if (store.profile(key)?.account !== account) return
                if (store.profile(key)?.selects(course.id) != true) continue
                try {
                    val scan = withTimeout(90_000) {
                        if (account.demo) HomeworkScan(listOf(demoHomework(course)), false, 0)
                        else HomeworkSynchronizer(api).scan(account, course, store.homework(key).filter { it.courseId == course.id })
                    }
                    val events = store.applyHomework(account, course.id, scan.items)
                    val settings = store.state.value.configuration.effectiveCourseSettings(key, course.id)
                    if (settings.enabled && settings.notification.accepts(EventKind.HOMEWORK) && events.isNotEmpty()) {
                        val summary = if (events.size == 1) events.single() else ClassroomEvent(EventKind.HOMEWORK,
                            "${course.name} 有 ${events.size} 份新作业或更新，请打开消息查看要求和截止时间。", course.name,
                            homeworkKey = "batch:${course.id}")
                        notifications.event(summary, account)
                    }
                    done++
                    if (scan.partial || scan.detailFailures > 0) incomplete++
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); failed++
                } catch (e: CancellationException) { throw e }
                catch (e: SessionExpired) { throw e }
                catch (_: Exception) { failed++ }
            }
            if (store.profile(key)?.account !== account) return
            store.homeworkStatus(key) { it.copy(lastSuccess = if (failed == 0 && incomplete == 0) System.currentTimeMillis() else it.lastSuccess,
                message = when {
                    selected.isEmpty() -> "未勾选课程，请先到课程页选择"
                    failed > 0 -> "已同步 $done/${selected.size} 门课程；$failed 门暂未成功，已保留上次内容，可重试"
                    incomplete > 0 -> "已检查 ${selected.size} 门课程；$incomplete 门详情暂不完整或超过最近 200 条动态，可重试或到雨课堂核对"
                    else -> "已同步 ${selected.size} 门课程的作业"
                }) }
        } catch (e: CancellationException) { throw e }
        catch (_: SessionExpired) {
            store.expire(account)
            store.homeworkStatus(key) { it.copy(loading = false, message = "登录已失效，请重新登录后同步作业") }
        } catch (_: Exception) {
            if (store.profile(key)?.account === account) store.homeworkStatus(key) { it.copy(message = "作业同步暂未成功，已保留上次内容，请检查网络后重试") }
        } finally {
            if (store.profile(key)?.account === account) store.homeworkStatus(key) { it.copy(loading = false,
                message = if (it.message == "正在同步作业…") "本次同步已停止，保留已同步内容" else it.message) }
            lock.unlock()
        }
    }
    private fun demoHomework(course: Course): Homework {
        // Stable publication/deadline avoid producing a new update on every demo refresh.
        val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        val deadline = day.plusDays(2).atTime(23, 59).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        return Homework("demo-homework", course.id, course.name, "${course.name} · 课后练习（演示）", HomeworkType.QUIZ,
            "完成本周课后练习，写出主要步骤，并在官方雨课堂上传作业。此条为模拟数据。", deadline, true, detailsLoaded = true)
    }
}
