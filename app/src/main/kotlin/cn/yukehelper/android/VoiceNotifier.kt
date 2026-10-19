package cn.yukehelper.android

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class VoiceNotifier(context: Context, private val report: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var closed = false
    private var warned = false
    private val pending = AtomicInteger(0)
    private val waiting = ArrayDeque<String>()
    init {
        try { tts = TextToSpeech(context) { status -> main.post {
            if (closed) return@post
            initialized = true
            ready = status == TextToSpeech.SUCCESS && runCatching { (tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: -1) >= 0 }.getOrDefault(false)
            runCatching { tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { pending.updateAndGet { (it - 1).coerceAtLeast(0) } }
                @Deprecated("Android callback") override fun onError(utteranceId: String?) { onDone(utteranceId); main.post { warn() } }
            }) }.onFailure { ready = false }
            val queued = waiting.toList(); waiting.clear()
            queued.forEach { deliver(it) }
        } } } catch (_: RuntimeException) { initialized = true; ready = false }
    }
    private fun warn() { if (!warned) { warned = true; report("中文语音不可用，请在系统文字转语音设置中安装或选择中文语音引擎") } }
    fun speak(text: String) { main.post {
        if (closed) return@post
        if (!initialized && waiting.size < 5) waiting.addLast(text) else deliver(text)
    } }
    private fun deliver(text: String) {
        if (!ready) { warn(); return }
        if (pending.get() >= 5) return
        pending.incrementAndGet()
        val outcome = runCatching { tts?.speak(text.take(500), TextToSpeech.QUEUE_ADD, null, System.nanoTime().toString()) }
            .getOrDefault(TextToSpeech.ERROR)
        if (outcome == null || outcome == TextToSpeech.ERROR) {
            pending.decrementAndGet(); warn()
        }
    }
    fun close() { main.post {
        closed = true; waiting.clear()
        runCatching { tts?.stop() }; runCatching { tts?.shutdown() }; tts = null
    } }
}
