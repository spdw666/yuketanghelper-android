package cn.yukehelper.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GlobalAiSettingsTest {
    private val firstKey = "pro.yuketang.cn:1"
    private fun key(name: String, secret: String, model: String = "deepseek-flash", provider: String = "DEEPSEEK") =
        """{"name":"$name","provider":"$provider","secret":"$secret","model":"$model"}"""
    private fun account(id: String, keys: String, active: Int = 0, fallback: Boolean = true, domain: String = "pro.yuketang.cn") =
        """{"id":"$id","name":"同学$id","domain":"$domain","session":"test-session-$id","excluded":["7"],
        "ai":{"keys":[$keys],"active":$active,"fallback":$fallback}}"""
    private fun migrate(accounts: String, selected: String = firstKey, version: Int = 3) = ConfigurationCodec.decode(
        """{"version":$version,"selected":"$selected","accounts":[$accounts],"courseSettings":{"single":"AI"}}""")

    @Test fun migrationMergesKeysAndKeepsCurrentAccountsPriorityAndDisabledFallback() {
        val a = account("1", key("甲", "test-first"))
        val b = account("2", listOf(key("备用", "test-second"), key("首选", "test-preferred")).joinToString(), 1, false)
        val config = migrate("$a,$b", "pro.yuketang.cn:2")
        assertEquals(listOf("test-second", "test-preferred", "test-first"), config.ai.keys.map { it.secret })
        assertEquals("test-preferred", config.ai.ordered().single().secret)
        assertFalse(config.ai.fallback)
        assertTrue(config.aiMigration.isNotBlank())
        assertFalse(config.aiMigration.contains("test-preferred"))
        assertFalse(config.toString().contains("test-preferred"))
        assertEquals(AnswerMode.AI, config.courseSettings.single)
        assertEquals(setOf("7"), config.active()!!.excludedCourses)
        assertEquals("test-session-2", config.active()!!.account.sessionId)
    }

    @Test fun migrationDeduplicatesSameKeyAndModelButRetainsOtherModelsAndProviders() {
        val a = account("1", listOf(key("重复甲", "test-shared"), key("另一模型", "test-shared", "custom-model")).joinToString())
        val b = account("2", listOf(key("重复乙", "test-shared"), key("首选", "test-shared"),
            key("另一平台", "test-shared", provider = "QWEN")).joinToString(), 1)
        val config = migrate("$a,$b", "pro.yuketang.cn:2")
        assertEquals(3, config.ai.keys.size)
        assertEquals(listOf("重复乙", "另一平台", "另一模型"), config.ai.keys.map { it.name })
        assertEquals("重复乙", config.ai.ordered().first().name)
        assertEquals(0, config.ai.active)
    }

    @Test fun emptyCurrentAccountUsesAnotherAccountsSavedSettings() {
        val config = migrate(account("1", "") + "," + account("2", key("已有", "test-existing"), fallback = false))
        assertEquals(firstKey, config.selected)
        assertEquals("test-existing", config.ai.ordered().single().secret)
        assertFalse(config.ai.fallback)
    }

    @Test fun migrationSeparatesSameUserIdAcrossSitesAndPrefersSelectedSite() {
        val config = migrate(account("1", key("荷塘", "test-pro")) + "," +
            account("1", key("雨课堂", "test-www"), domain = "www.yuketang.cn"), "www.yuketang.cn:1")
        assertEquals("test-www", config.ai.ordered().first().secret)
        assertEquals(setOf("test-www", "test-pro"), config.ai.keys.map { it.secret }.toSet())
        assertEquals(2, config.profiles.size)
    }

    @Test fun legacySingleAccountAndVersionTwoAlsoMigrateAi() {
        val old = account("1", key("旧密钥", "test-legacy"), fallback = false)
        val single = ConfigurationCodec.decode(old, setOf("8"))
        val multi = migrate(old, version = 2)
        for (config in listOf(single, multi)) {
            assertEquals("test-legacy", config.ai.ordered().single().secret)
            assertFalse(config.ai.fallback)
            assertEquals("test-session-1", config.active()!!.account.sessionId)
        }
        assertEquals(setOf("8"), single.active()!!.excludedCourses)
    }

    @Test fun migrationWithNoUsableKeysStaysEmptyAndSkipsUnknownProviders() {
        val config = migrate(account("1", listOf(key("空", " "), key("未知", "test-unknown", provider = "FUTURE")).joinToString()))
        assertTrue(config.ai.keys.isEmpty())
        assertTrue(config.aiMigration.isEmpty())
        assertTrue(config.ai.ordered().isEmpty())
    }

    @Test fun globalKeysPersistWithoutAccountsAndDoNotReappearAfterDeletion() {
        val migrated = migrate(account("1", key("已有", "test-retained")))
        val noAccounts = ConfigurationCodec.decode(ConfigurationCodec.encode(migrated.copy(profiles = emptyList(), selected = null)))
        assertTrue(noAccounts.profiles.isEmpty())
        assertEquals("test-retained", noAccounts.ai.ordered().single().secret)
        assertEquals(migrated.aiMigration, noAccounts.aiMigration)
        val cleared = ConfigurationCodec.decode(ConfigurationCodec.encode(noAccounts.withAiSettings(AiSettings())))
        assertTrue(cleared.ai.keys.isEmpty())
        assertTrue(cleared.aiMigration.isEmpty())
    }

    @Test fun versionFourOnlyStoresOneAiSectionAndNeverResurrectsAccountKeys() {
        val migrated = migrate(account("1", key("原密钥", "test-old")))
        val encoded = Json.parseToJsonElement(ConfigurationCodec.encode(migrated)).jsonObject
        assertEquals(4, encoded["version"]!!.jsonPrimitive.int)
        assertFalse("ai" in encoded["accounts"]!!.jsonArray.single().jsonObject)
        val current = Json.parseToJsonElement("""{"active":0,"fallback":false,"keys":[${key("全局", "test-global")}]}""")
        val staleAccount = Json.parseToJsonElement(account("1", key("旧账号", "test-stale")))
        val root = JsonObject(encoded + mapOf("ai" to current, "accounts" to JsonArray(listOf(staleAccount))))
        assertEquals("test-global", ConfigurationCodec.decode(root.toString()).ai.ordered().single().secret)
        val empty = Json.parseToJsonElement("""{"keys":[]}""")
        assertTrue(ConfigurationCodec.decode(JsonObject(root + ("ai" to empty)).toString()).ai.keys.isEmpty())
    }

    @Test fun savingSharedAiRetainsCourseRulesAndClearsOnlyAiMigrationNotice() {
        val config = migrate(account("1", key("已有", "test-saved"))).copy(settingsMigration = "课程迁移")
        val saved = ConfigurationCodec.decode(ConfigurationCodec.encode(config.withAiSettings(config.ai.copy(fallback = false))))
        assertEquals(config.courseSettings, saved.courseSettings)
        assertEquals(config.profiles.map { it.account.key }, saved.profiles.map { it.account.key })
        assertEquals(config.profiles.map { it.account.sessionId }, saved.profiles.map { it.account.sessionId })
        assertEquals(config.profiles.map { it.excludedCourses }, saved.profiles.map { it.excludedCourses })
        assertEquals("课程迁移", saved.settingsMigration)
        assertTrue(saved.aiMigration.isEmpty())
        assertFalse(saved.ai.fallback)
        assertEquals("test-saved", saved.ai.ordered().single().secret)
    }
}
