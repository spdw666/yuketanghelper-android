package cn.yukehelper.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun root(text: String) = Json.parseToJsonElement(text).jsonObject
    private val lesson = ActiveLesson(12, "34", "测试课程")

    @Test fun numericIdentifiersAndNestedCoursesAreRead() {
        val courses = Protocol.parseCourses(root("""{"code":0,"data":{"list":[{"classroom_id":34,"name":"一班","course":{"name":"数学"},"teacher":{"name":"老师"}}]}}"""))
        assertEquals(Course("34", "数学", "一班", "老师"), courses.single())
        assertEquals(lesson, Protocol.parseLessons(root("""{"code":0,"data":{"onLessonClassrooms":[{"lessonId":12,"classroomId":34,"courseName":"测试课程"}]}}""")).single())
    }
    @Test fun aMissingListIsAnErrorRatherThanAnEmptyClassroom() {
        assertThrows(NetworkFailure::class.java) { Protocol.parseLessons(root("""{"code":0,"data":{}}""")) }
    }
    @Test fun stringLessonIdsRetainTheirTypeAndDigitsWhileTheLocalKeyRemainsNumeric() {
        val parsed = Protocol.parseLessons(root("""{"code":0,"data":{"onLessonClassrooms":[{"lessonId":"00012","classroomId":34}]}}""")).single()
        assertEquals(12L, parsed.id)
        assertTrue(parsed.requestId.isString)
        assertEquals("00012", parsed.requestId.content)
    }
    @Test fun requestIdentifiersCannotPointToADifferentLocalLesson() {
        assertThrows(IllegalArgumentException::class.java) { ActiveLesson(12, "34", "课程", JsonPrimitive("13")) }
        assertThrows(IllegalArgumentException::class.java) { ActiveLesson(12, "34", "课程", JsonPrimitive("secret")) }
    }
    @Test fun unauthenticatedIsSessionExpiryButGenericResourceDenialIsNot() {
        assertThrows(SessionExpired::class.java) { Protocol.data(root("""{"code":50000,"msg":"UNAUTHENTICATED","data":""}""")) }
        assertThrows(ApiRejected::class.java) { Protocol.data(root("""{"code":50000,"msg":"资源无权访问","data":{}}""")) }
    }
    @Test fun unknownServerDetailsAreNotConvertedIntoArbitraryVisibleText() {
        assertNull(LessonEntryDetail.fromMessage("secret-token https://private.invalid"))
        assertNull(LessonEntryDetail.fromMessage("cannot unmarshal number into Go value of type CustomObject"))
        assertEquals(LessonEntryDetail.STRING_TYPE,
            LessonEntryDetail.fromMessage("json: cannot unmarshal number into Go struct field Entry.lessonId of type string secret"))
        assertEquals(LessonEntryDetail.NUMBER_TYPE,
            LessonEntryDetail.fromMessage("json: cannot unmarshal string into Go struct field Entry.lessonId of type int64 secret"))
    }
    @Test fun expiredSessionsAreDistinctFromOtherRejections() {
        assertThrows(SessionExpired::class.java) { Protocol.data(root("""{"code":-1,"msg":"未登录","data":{}}""")) }
        assertThrows(ApiRejected::class.java) { Protocol.data(root("""{"code":99,"msg":"busy","data":{}}""")) }
    }
    @Test fun rollCallsOnlyNotifyTheCurrentStudent() {
        assertTrue(Protocol.decodeFrame("""{"op":"callpaused","name":"其他人"}""", lesson, "本人").isEmpty())
        val event = Protocol.decodeFrame("""{"op":"callpaused","name":"本人"}""", lesson, "本人").single() as LessonFrame.Event
        assertEquals(EventKind.CALL, event.event.kind)
    }
    @Test fun finishedFramesProduceAnEventAndTerminateTheSession() {
        val frames = Protocol.decodeFrame("""{"op":"lessonfinished"}""", lesson, "本人")
        assertEquals(EventKind.LESSON_END, (frames.first() as LessonFrame.Event).event.kind)
        assertEquals(LessonFrame.Finished, frames.last())
    }
    @Test fun credentialsAreExcludedFromDiagnosticStrings() {
        assertFalse(Account("1", "本人", "pro.yuketang.cn", "secret-cookie").toString().contains("secret-cookie"))
        assertFalse(LessonAccess(lesson, "secret-token", "secret-bearer").toString().contains("secret-"))
    }
}
