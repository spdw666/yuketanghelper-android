package cn.yukehelper.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cn.yukehelper.core.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AccountRuntime(val lessons: List<ActiveLesson> = emptyList(), val connected: Set<Long> = emptySet(), val lastPoll: Long = 0)
data class HomeworkSyncStatus(val loading: Boolean = false, val lastSuccess: Long = 0, val message: String = "尚未同步作业")
data class AppState(
    val configuration: Configuration = Configuration(), val runtimes: Map<String, AccountRuntime> = emptyMap(),
    val events: List<ClassroomEvent> = emptyList(), val monitoring: Boolean = false,
    val message: String = "", val loading: Boolean = false,
    val homework: List<Homework> = emptyList(), val homeworkSync: Map<String, HomeworkSyncStatus> = emptyMap(),
) {
    val profile get() = configuration.active()
    val account get() = profile?.account
    val courses get() = profile?.courses.orEmpty()
    private val runtime get() = runtimes[account?.key] ?: AccountRuntime()
    val lessons get() = runtime.lessons
    val connected get() = runtime.connected
    val lastPoll get() = runtime.lastPoll
    val selectedHomework get() = homework.filter { item -> item.listed && profile?.let { p -> p.selects(item.courseId) && p.courses.any { it.id == item.courseId } } == true }
    val homeworkStatus get() = homeworkSync[account?.key] ?: HomeworkSyncStatus()
}

/** Retain the v0.1 key and ciphertext slot for update-install migration. */
private class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("private_session", Context.MODE_PRIVATE)
    var loadFailed = false
        private set
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("yuke-session-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("yuke-session-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(config: Configuration) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(ConfigurationCodec.encode(config).toByteArray(Charsets.UTF_8))
        val edit = prefs.edit()
        if (loadFailed) prefs.getString("encrypted", null)?.let { edit.putString("recovery", it) }
        check(edit.putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        loadFailed = false
    }
    fun load(excluded: Set<String>): Configuration {
        val saved = prefs.getString("encrypted", null) ?: return Configuration()
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            require(bytes.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            ConfigurationCodec.decode(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), excluded)
        } catch (_: Exception) { loadFailed = true; Configuration() }
    }
}

private class EventDatabase(context: Context) : SQLiteOpenHelper(context, "events.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, summary TEXT NOT NULL, course TEXT NOT NULL, lesson INTEGER, at INTEGER NOT NULL, account TEXT NOT NULL DEFAULT '', homework_key TEXT)")
        db.execSQL("CREATE INDEX events_account ON events(account,id)")
        createHomework(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN account TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX events_account ON events(account,id)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE events ADD COLUMN homework_key TEXT")
            createHomework(db)
        }
    }
    private fun createHomework(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE homework(account TEXT NOT NULL, item_key TEXT NOT NULL, content TEXT NOT NULL, PRIMARY KEY(account,item_key))")
    }
    private fun insertEvent(key: String, event: ClassroomEvent) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("account", key); put("kind", event.kind.name); put("summary", event.summary.take(500)); put("course", event.course.take(200))
            event.lessonId?.let { put("lesson", it) }; put("at", event.at); put("homework_key", event.homeworkKey)
        })
    }
    private fun trimEvents(key: String) {
        writableDatabase.execSQL("DELETE FROM events WHERE account = ? AND id NOT IN (SELECT id FROM events WHERE account = ? ORDER BY id DESC LIMIT 200)", arrayOf(key, key))
    }
    @Synchronized fun assignLegacy(key: String) { writableDatabase.update("events", ContentValues().apply { put("account", key) }, "account = ''", null) }
    @Synchronized fun add(key: String, event: ClassroomEvent): List<ClassroomEvent> {
        writableDatabase.beginTransaction()
        try {
            insertEvent(key, event)
            trimEvents(key)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return recent(key)
    }
    @Synchronized fun recent(key: String): List<ClassroomEvent> = buildList {
        readableDatabase.rawQuery("SELECT kind,summary,course,lesson,at,homework_key FROM events WHERE account = ? ORDER BY id DESC LIMIT 100", arrayOf(key)).use { c ->
            while (c.moveToNext()) add(ClassroomEvent(runCatching { EventKind.valueOf(c.getString(0)) }.getOrDefault(EventKind.SYSTEM),
                c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3), c.getLong(4), if (c.isNull(5)) null else c.getString(5)))
        }
    }
    @Synchronized fun homework(key: String): List<Homework> = buildList {
        readableDatabase.rawQuery("SELECT content FROM homework WHERE account = ?", arrayOf(key)).use { c ->
            while (c.moveToNext()) add(HomeworkCodec.decode(c.getString(0)))
        }
    }
    @Synchronized fun saveHomework(key: String, items: List<Homework>, now: Long): List<ClassroomEvent> {
        writableDatabase.beginTransaction()
        try {
            val changes = HomeworkChanges.events(homework(key), items, now)
            items.forEach { item -> writableDatabase.insertWithOnConflict("homework", null, ContentValues().apply {
                put("account", key); put("item_key", item.key); put("content", HomeworkCodec.encode(item))
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) } }
            changes.forEach { insertEvent(key, it) }
            trimEvents(key)
            writableDatabase.setTransactionSuccessful()
            return changes
        } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun clear(key: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("events", "account = ?", arrayOf(key))
            writableDatabase.delete("homework", "account = ?", arrayOf(key))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
}

class AppStore(context: Context) {
    private val credentials = CredentialStore(context)
    private val database = EventDatabase(context)
    private val legacy = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val config = credentials.load(legacy.getStringSet("excluded", emptySet()).orEmpty())
    private val mutable: MutableStateFlow<AppState>
    init {
        config.active()?.account?.key?.let(database::assignLegacy)
        mutable = MutableStateFlow(AppState(configuration = config,
            events = config.active()?.account?.key?.let(database::recent).orEmpty(),
            homework = config.active()?.account?.key?.let(database::homework).orEmpty(),
            message = if (credentials.loadFailed) "本机登录信息暂时无法解密，请重新登录；已保留原始加密数据" else ""))
        if (!credentials.loadFailed && config.profiles.isNotEmpty()) {
            credentials.save(config)
            legacy.edit().remove("excluded").apply()
        }
    }
    val state = mutable.asStateFlow()
    fun update(block: (AppState) -> AppState) { mutable.update(block) }
    fun profile(key: String) = state.value.configuration.profiles.firstOrNull { it.account.key == key }
    @Synchronized private fun save(config: Configuration) {
        credentials.save(config)
        update { it.copy(configuration = config) }
    }
    @Synchronized fun editProfile(key: String, block: (AccountProfile) -> AccountProfile) {
        val current = state.value.configuration
        save(current.copy(profiles = current.profiles.map { if (it.account.key == key) block(it) else it }))
    }
    @Synchronized fun applyCourseSelection(key: String, knownIds: Set<String>, selectedIds: Set<String>) {
        editProfile(key) { CourseSelection.apply(it, knownIds, selectedIds) }
    }
    @Synchronized fun saveCourseSettings(value: CourseSettings) {
        save(state.value.configuration.withCourseSettings(value))
    }
    @Synchronized fun editAiSettings(block: (AiSettings) -> AiSettings) {
        val current = state.value.configuration
        save(current.withAiSettings(block(current.ai)))
    }
    @Synchronized fun event(event: ClassroomEvent, key: String? = state.value.account?.key) {
        if (key == null || profile(key) == null) return
        val recent = database.add(key, event)
        update { if (it.account?.key == key) it.copy(events = recent) else it }
    }
    @Synchronized fun homework(key: String): List<Homework> = database.homework(key)
    @Synchronized fun applyHomework(account: Account, courseId: String, items: List<Homework>): List<ClassroomEvent> {
        val profile = profile(account.key) ?: return emptyList()
        if (profile.account !== account || !profile.selects(courseId)) return emptyList()
        require(items.all { it.courseId == courseId })
        val changes = database.saveHomework(account.key, items, System.currentTimeMillis())
        update { if (it.account?.key == account.key) it.copy(homework = database.homework(account.key), events = database.recent(account.key)) else it }
        return changes
    }
    fun homeworkStatus(key: String, block: (HomeworkSyncStatus) -> HomeworkSyncStatus) {
        update { if (profile(key) == null) it else it.copy(homeworkSync = it.homeworkSync + (key to block(it.homeworkSync[key] ?: HomeworkSyncStatus()))) }
    }
    fun runtime(key: String, block: (AccountRuntime) -> AccountRuntime) {
        update { it.copy(runtimes = it.runtimes + (key to block(it.runtimes[key] ?: AccountRuntime()))) }
    }
    @Synchronized fun signIn(account: Account) {
        val current = state.value.configuration
        val previous = profile(account.key)
        val profile = previous?.copy(account = account, monitored = true) ?: AccountProfile(account)
        save(current.copy(profiles = current.profiles.filterNot { it.account.key == account.key } + profile, selected = account.key))
        update { it.copy(events = database.recent(account.key), homework = database.homework(account.key), message = "", loading = false) }
    }
    @Synchronized fun select(key: String) {
        if (profile(key) == null) return
        save(state.value.configuration.copy(selected = key))
        update { it.copy(events = database.recent(key), homework = database.homework(key), message = "", loading = false) }
    }
    @Synchronized fun signOut(key: String? = state.value.account?.key) {
        if (key == null) return
        val current = state.value.configuration
        val remaining = current.profiles.filterNot { it.account.key == key }
        val selected = if (current.selected == key) remaining.firstOrNull()?.account?.key else current.selected
        save(current.copy(profiles = remaining, selected = selected))
        database.clear(key)
        update { it.copy(events = selected?.let(database::recent).orEmpty(), homework = selected?.let(database::homework).orEmpty(),
            homeworkSync = it.homeworkSync - key, runtimes = it.runtimes - key, message = "", loading = false) }
    }
    @Synchronized fun expire(account: Account) {
        if (profile(account.key)?.account !== account) return
        editProfile(account.key) { it.copy(account = Account(account.userId, account.name, account.domain, ""), monitored = false) }
        runtime(account.key) { AccountRuntime() }
        event(ClassroomEvent(EventKind.ERROR, "账号登录已失效，请重新登录"), account.key)
        if (state.value.account?.key == account.key) update { it.copy(message = "当前账号登录已失效，请在设置中重新登录", loading = false) }
    }
}
