package cn.yukehelper.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GlobalCourseSettingsTest {
    private val a = Account("1", "甲", "pro.yuketang.cn", "test-session")
    private val b = Account("2", "乙", "pro.yuketang.cn", "test-second")
    private val rules = CourseSettings(single = AnswerMode.AI, multiple = AnswerMode.RANDOM, short = AnswerMode.BLANK,
        nearDeadline = true, autoBonus = true, danmuThreshold = 8, voice = Subscription(true, setOf(EventKind.CALL)))
    private fun legacy(accounts: String, selected: String = a.key) = ConfigurationCodec.decode(
        """{"version":2,"selected":"$selected","accounts":[$accounts]}""")
    private fun oldAccount(id: String, settings: String) = """{"id":"$id","name":"旧账号$id","domain":"pro.yuketang.cn","session":"test-session",
        "courses":[{"id":"7","name":"数学"},{"id":"8","name":"英语"},{"id":"9","name":"编程"}],"settings":$settings} """

    @Test fun allAccountsAndFutureCoursesUseOneRuleSetWhileSelectionsRemainSeparate() {
        val config = Configuration(listOf(AccountProfile(a, excludedCourses = setOf("8")), AccountProfile(b)), a.key).withCourseSettings(rules)
        val decoded = ConfigurationCodec.decode(ConfigurationCodec.encode(config))
        for (id in listOf("7", "future")) {
            assertEquals(rules, decoded.effectiveCourseSettings(a.key, id))
            assertEquals(rules, decoded.effectiveCourseSettings(b.key, id))
        }
        assertEquals(rules.copy(enabled = false), decoded.effectiveCourseSettings(a.key, "8"))
        assertEquals(rules, decoded.effectiveCourseSettings(b.key, "8"))
    }
    @Test fun serializedAccountsContainOnlySelectionsAndCannotOverrideCommonRules() {
        val config = Configuration(listOf(AccountProfile(a, excludedCourses = setOf("8"))), a.key, rules)
        val json = Json.parseToJsonElement(ConfigurationCodec.encode(config)).jsonObject
        assertEquals(5, json["version"]!!.jsonPrimitive.int)
        assertFalse("enabled" in json["courseSettings"]!!.jsonObject)
        val account = json["accounts"]!!.jsonArray.single().jsonObject
        assertFalse("settings" in account)
        assertEquals(listOf("8"), account["excluded"]!!.jsonArray.map { it.jsonPrimitive.content })
        val tamperedAccount = JsonObject(account + ("settings" to Json.parseToJsonElement("""{"7":{"single":"RANDOM"}}""")))
        val tampered = JsonObject(json + ("accounts" to JsonArray(listOf(tamperedAccount))))
        assertEquals(rules, ConfigurationCodec.decode(tampered.toString()).effectiveCourseSettings(a.key, "7"))
    }
    @Test fun legacyMigrationPrefersCurrentAccountAndMostUsedSelectedRuleSet() {
        val first = oldAccount("1", """{"7":{"single":"RANDOM","danmu":true}}""")
        val selected = oldAccount("2", """{"7":{"single":"AI","bonus":true},"8":{"single":"RANDOM"},"9":{"single":"AI","bonus":true},"hidden":{"enabled":false,"single":"RANDOM"}}""")
        val config = legacy("$first,$selected", b.key)
        assertEquals(AnswerMode.AI, config.courseSettings.single)
        assertTrue(config.courseSettings.autoBonus)
        assertFalse(config.courseSettings.autoDanmu)
        assertEquals(setOf("hidden"), config.active()!!.excludedCourses)
        assertTrue(config.settingsMigration.contains("旧账号2"))
        assertTrue(config.settingsMigration.contains("数学"))
        assertEquals(config.courseSettings, config.effectiveCourseSettings(a.key, "7"))
    }
    @Test fun legacyMigrationDoesNotFavorMoreDisabledRulesOverSelectedCourses() {
        val config = legacy(oldAccount("1", """{"7":{"single":"RANDOM"},"8":{"enabled":false,"single":"AI"},"9":{"enabled":false,"single":"AI"}}"""))
        assertEquals(AnswerMode.RANDOM, config.courseSettings.single)
        assertEquals(setOf("8", "9"), config.active()!!.excludedCourses)
        assertFalse(config.effectiveCourseSettings(a.key, "8").enabled)
    }
    @Test fun tiedLegacyRulesUseCourseOrderAndDoNotCombineAutomaticActions() {
        val config = legacy(oldAccount("1", """{"8":{"bonus":true},"7":{"danmu":true}}"""))
        assertTrue(config.courseSettings.autoDanmu)
        assertFalse(config.courseSettings.autoBonus)
        assertTrue(config.settingsMigration.contains("数学"))
    }
    @Test fun migrationFallsBackToSavedRulesWhenNoCourseIsSelectedOrLoaded() {
        val config = legacy("""{"id":"1","name":"本人","domain":"pro.yuketang.cn","session":"test-session","settings":{"7":{"enabled":false,"single":"AI"}}}""")
        assertEquals(AnswerMode.AI, config.courseSettings.single)
        assertFalse(config.effectiveCourseSettings(a.key, "7").enabled)
        assertTrue(config.settingsMigration.contains("课程 7"))
        val roundTrip = ConfigurationCodec.decode(ConfigurationCodec.encode(config))
        assertEquals(config.settingsMigration, roundTrip.settingsMigration)
        assertEquals(config.courseSettings, roundTrip.courseSettings)
    }
    @Test fun savingCommonRulesClearsMigrationAndSurvivesHavingNoAccounts() {
        val migrated = legacy(oldAccount("1", """{"7":{"single":"AI"}}"""))
        val config = migrated.withCourseSettings(rules.copy(enabled = false))
        assertTrue(config.courseSettings.enabled)
        assertTrue(config.settingsMigration.isEmpty())
        val decoded = ConfigurationCodec.decode(ConfigurationCodec.encode(config.copy(profiles = emptyList(), selected = null)))
        assertEquals(rules, decoded.courseSettings)
        assertTrue(decoded.profiles.isEmpty())
    }
    @Test fun removedAndDisabledAccountsCannotActDespiteGlobalRules() {
        val config = Configuration(listOf(AccountProfile(a, monitored = false)), a.key, rules)
        assertFalse(config.effectiveCourseSettings(a.key, "7").enabled)
        assertFalse(config.effectiveCourseSettings(b.key, "7").enabled)
    }
}
