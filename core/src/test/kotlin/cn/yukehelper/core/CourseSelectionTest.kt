package cn.yukehelper.core

import org.junit.Assert.*
import org.junit.Test

class CourseSelectionTest {
    private val account = Account("1", "本人", "pro.yuketang.cn", "session")
    private val courses = listOf(
        Course("1", "高等数学", "数学一班", "陈老师"),
        Course("2", "大学英语", "英语二班", "林老师"),
        Course("3", "Kotlin 程序设计", "计算机一班", "张老师"),
    )

    @Test fun bulkSelectionRoundTripsWithoutChangingAnswerRulesOrOtherAccounts() {
        val rules = CourseSettings(single = AnswerMode.AI, autoBonus = true, voice = Subscription(true))
        val p = AccountProfile(account, courses = courses, excludedCourses = setOf("2"))
        val other = AccountProfile(Account("1", "另一个站点", "www.yuketang.cn", "other"), courses = courses)
        val allOff = CourseSelection.apply(p, setOf("1", "2", "3"), emptySet())
        val config = ConfigurationCodec.decode(ConfigurationCodec.encode(Configuration(listOf(allOff, other), account.key, rules)))
        assertTrue(config.active()!!.courses.all { !config.active()!!.selects(it.id) })
        assertEquals(rules.copy(enabled = false), config.effectiveCourseSettings(account.key, "1"))
        assertTrue(config.profiles.last().courses.all { config.profiles.last().selects(it.id) })
        val allOn = CourseSelection.apply(config.active()!!, setOf("1", "2", "3"), setOf("1", "2", "3"))
        assertTrue(allOn.courses.all { allOn.selects(it.id) })
        assertEquals(rules, config.copy(profiles = listOf(allOn, other)).effectiveCourseSettings(account.key, "1"))
    }

    @Test fun staleSelectionDoesNotDisableNewlyFetchedCoursesOrCreateUnknownCourses() {
        val p = AccountProfile(account, courses = courses)
        // A third course arrived after the selection screen was opened.
        val edited = CourseSelection.apply(p, setOf("1", "2", "unknown"), setOf("2", "unknown"))
        assertFalse(edited.selects("1"))
        assertTrue(edited.selects("2"))
        assertTrue(edited.selects("3"))
        assertFalse("unknown" in edited.excludedCourses)
    }

    @Test fun savingCommonRulesKeepsSelectionsAndAppliesToEverySelectedCourse() {
        val p = AccountProfile(account, courses = courses, excludedCourses = setOf("2"))
        val form = CourseSettings(single = AnswerMode.AI, multiple = AnswerMode.RANDOM, short = AnswerMode.BLANK,
            nearDeadline = true, autoDanmu = true, autoBonus = true, danmuThreshold = 8,
            notification = Subscription(true, setOf(EventKind.QUESTION)), voice = Subscription(true, setOf(EventKind.CALL)))
        val config = Configuration(listOf(p), account.key).withCourseSettings(form)
        val restored = ConfigurationCodec.decode(ConfigurationCodec.encode(config))
        assertEquals(form, restored.effectiveCourseSettings(account.key, "1"))
        assertEquals(form.copy(enabled = false), restored.effectiveCourseSettings(account.key, "2"))
        assertEquals(form, restored.effectiveCourseSettings(account.key, "3"))
        assertEquals(setOf("2"), restored.active()!!.excludedCourses)
        assertEquals(account.sessionId, restored.active()!!.account.sessionId)
    }

    @Test fun courseSelectionKeepsExclusionsForTemporarilyMissingCourses() {
        val p = AccountProfile(account, courses = courses.take(2), excludedCourses = setOf("3"))
        val edited = CourseSelection.apply(p, setOf("1", "2", "3"), setOf("2"))
        assertEquals(setOf("1", "3"), edited.excludedCourses)
        assertFalse(edited.copy(courses = courses).selects("3"))
    }

    @Test fun courseSearchMatchesNamesClassesTeachersIdsAndMultipleWords() {
        assertEquals(listOf(courses[0]), CourseSelection.filter(courses, "数学 陈"))
        assertEquals(listOf(courses[1]), CourseSelection.filter(courses, "林老师"))
        assertEquals(listOf(courses[2]), CourseSelection.filter(courses, "kOtLiN"))
        assertEquals(listOf(courses[2]), CourseSelection.filter(courses, "3"))
        assertEquals(courses, CourseSelection.filter(courses, "  "))
        assertTrue(CourseSelection.filter(courses, "不存在").isEmpty())
    }
}
