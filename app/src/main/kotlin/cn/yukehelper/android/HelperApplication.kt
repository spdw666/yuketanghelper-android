package cn.yukehelper.android

import android.app.Application
import cn.yukehelper.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class HelperApplication : Application() {
    lateinit var store: AppStore
        private set
    val api = YuketangApi()
    val ai = AiClient()
    override fun onCreate() { super.onCreate(); store = AppStore(this) }
}

class DemoGateway : ClassroomGateway {
    override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(9001, "demo-1", "高等数学 · 课堂演示"))
    override suspend fun checkIn(account: Account, lesson: ActiveLesson) = LessonAccess(lesson, "demo", null)
    override fun frames(account: Account, access: LessonAccess) = flow {
        emit(LessonFrame.Connected)
        emit(LessonFrame.Signal(buildJsonObject { put("op", "hello"); put("presentation", "demo") }))
        val samples = listOf(
            EventKind.QUESTION to "老师发布了一道新题目",
            EventKind.CALL to "老师正在点名，请留意课堂",
            EventKind.DANMU to "这部分讲解很清楚",
        )
        var index = 0
        while (currentCoroutineContext().isActive) {
            delay(8_000)
            val (kind, text) = samples[index++ % samples.size]
            emit(LessonFrame.Event(ClassroomEvent(kind, text, access.lesson.name, access.lesson.id)))
            if (kind == EventKind.QUESTION) {
                val id = System.nanoTime().toString()
                DemoActions.add(id)
                emit(LessonFrame.Signal(buildJsonObject {
                    put("op", "unlockproblem"); put("problem", buildJsonObject { put("prob", id); put("pres", "demo"); put("limit", 60) })
                }))
            }
            if (kind == EventKind.DANMU) {
                repeat(3) { emit(LessonFrame.Signal(buildJsonObject { put("op", "newdanmu"); put("danmu", text) })) }
                emit(LessonFrame.Event(ClassroomEvent(EventKind.BONUS, "课堂出现了演示红包", access.lesson.name, access.lesson.id)))
                emit(LessonFrame.Signal(buildJsonObject { put("op", "gainbonus"); put("redEnvelopeId", System.nanoTime().toString()) }))
            }
        }
    }
    companion object {
        val courses = listOf(
            Course("demo-1", "高等数学", "数学一班", "陈老师"),
            Course("demo-2", "大学英语", "英语二班", "林老师"),
            Course("demo-3", "程序设计基础", "计算机一班", "张老师"),
        )
    }
}

object DemoActions : AutomationGateway {
    private val questions = ConcurrentHashMap<String, Question>()
    fun add(id: String) {
        if (questions.size > 100) questions.clear()
        questions[id] = Question(id, id, "demo", 1, listOf("A", "B", "C", "D"), 1, "", 60.0, System.currentTimeMillis())
    }
    override suspend fun presentation(account: Account, access: LessonAccess, id: String) = questions.values.toList()
    override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) {
        questions[question.id] = question.copy(answered = true)
    }
    override suspend fun danmu(account: Account, access: LessonAccess, content: String) { delay(100) }
    override suspend fun bonus(account: Account, access: LessonAccess, id: String) { delay(100) }
}
