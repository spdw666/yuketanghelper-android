package cn.yukehelper.core

import org.junit.Assert.*
import org.junit.Test

class ConfigurationTest {
    @Test fun legacySessionAndCourseSwitchesSurviveMigration() {
        val config = ConfigurationCodec.decode("""{"id":"1","name":"本人","domain":"pro.yuketang.cn","session":"old-session"}""", setOf("7"))
        val p = config.active()!!
        assertEquals("old-session", p.account.sessionId)
        assertFalse(p.selects("7"))
        assertTrue(p.selects("8"))
        assertEquals(AnswerMode.OFF, config.courseSettings.single)
        assertFalse(config.courseSettings.autoBonus)
        assertEquals(p.account.key, config.selected)
        assertEquals("old-session", ConfigurationCodec.decode(ConfigurationCodec.encode(config)).active()!!.account.sessionId)
    }
    @Test fun accountKeysIncludeDomainToAvoidUserIdCollisions() {
        assertNotEquals(Account("1", "同学", "pro.yuketang.cn", "a").key, Account("1", "同学", "www.yuketang.cn", "b").key)
    }
    @Test fun multiAccountSessionsStaySeparateWithSharedAiSettings() {
        val a = Account("1", "甲", "pro.yuketang.cn", "first")
        val b = Account("1", "乙", "www.yuketang.cn", "second")
        val s = CourseSettings(single = AnswerMode.AI, short = AnswerMode.BLANK, nearDeadline = true, autoDanmu = true,
            autoBonus = true, danmuThreshold = 4, voice = Subscription(true, setOf(EventKind.CALL)))
        val config = Configuration(listOf(AccountProfile(a, excludedCourses = setOf("8")),
            AccountProfile(b, monitored = false, pollSeconds = 90)), b.key, courseSettings = s,
            ai = AiSettings(listOf(AiKey("通用", AiProvider.QWEN, "key-one"))))
        val decoded = ConfigurationCodec.decode(ConfigurationCodec.encode(config))
        assertEquals("second", decoded.active()!!.account.sessionId)
        assertFalse(decoded.active()!!.monitored)
        val first = decoded.profiles.first()
        assertEquals(s, decoded.effectiveCourseSettings(a.key, "7"))
        assertEquals(s.copy(enabled = false), decoded.effectiveCourseSettings(a.key, "8"))
        assertEquals(s, decoded.courseSettings)
        assertEquals("first", first.account.sessionId)
        assertEquals("key-one", decoded.ai.keys.single().secret)
        assertEquals("key-one", decoded.copy(selected = a.key).ai.keys.single().secret)
        assertFalse(decoded.toString().contains("key-one"))
    }
    @Test fun fallbackOrderStartsWithActiveAndCanBeDisabled() {
        val keys = listOf(AiKey("a", AiProvider.QWEN, "1"), AiKey("b", AiProvider.GEMINI, "2"), AiKey("c", AiProvider.QWEN, "3"))
        assertEquals(listOf("b", "a", "c"), AiSettings(keys, 1).ordered().map { it.name })
        assertEquals(listOf("b"), AiSettings(keys, 1, false).ordered().map { it.name })
        assertTrue(AiSettings(keys, -1).ordered().isEmpty())
    }
    @Test fun demoCredentialsAreNeverPersisted() {
        val config = Configuration(listOf(AccountProfile(Account("demo", "体验", "pro.yuketang.cn", "", true))), "demo")
        assertTrue(ConfigurationCodec.decode(ConfigurationCodec.encode(config)).profiles.isEmpty())
    }
    @Test fun deepseekKeysAndExistingProvidersRoundTripWithActiveFallbackOrder() {
        val account = Account("1", "本人", "pro.yuketang.cn", "test-session")
        val keys = listOf(AiKey("Qwen", AiProvider.QWEN, "test-qwen"),
            AiKey("Gemini", AiProvider.GEMINI, "test-google"),
            AiKey("DeepSeek", AiProvider.DEEPSEEK, "test-deepseek"),
            AiKey("Custom DS", AiProvider.DEEPSEEK, "test-custom", "custom-vision"))
        val config = Configuration(listOf(AccountProfile(account)), account.key, ai = AiSettings(keys, 2, true))
        val decoded = ConfigurationCodec.decode(ConfigurationCodec.encode(config)).ai
        assertEquals(listOf(AiProvider.QWEN, AiProvider.GEMINI, AiProvider.DEEPSEEK, AiProvider.DEEPSEEK), decoded.keys.map { it.provider })
        assertEquals(listOf("DeepSeek", "Qwen", "Gemini", "Custom DS"), decoded.ordered().map { it.name })
        assertEquals(keys.map { it.secret }, decoded.keys.map { it.secret })
        assertEquals("deepseek-flash", decoded.keys[2].model)
        assertEquals("custom-vision", decoded.keys[3].model)
        assertFalse(decoded.toString().contains("test-deepseek"))
    }
}
