package cn.yukehelper.android

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import cn.yukehelper.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.*

/**
 * Dynamic QR attendance: the classroom screen shows a code that refreshes every few seconds.
 * This screen reads it locally (camera, gallery image or pasted text) and submits it through the
 * same two-step protocol the official client uses.
 */
class ScanActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph get() = application as HelperApplication
    private val executor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var resultDetail: TextView
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStarting = false
    @Volatile private var busy = false
    @Volatile private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        ensureCamera()
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val footer = column().apply {
            setPadding(dp(18), dp(16), dp(18), dp(28))
            setBackgroundColor(0xCC0A1220.toInt())
        }
        footer.addView(label("扫描课堂签到二维码", 20f, bold = true))
        footer.block(label("把手机对准课堂大屏上几秒刷新一次的雨课堂签到二维码。图片只在本机识别，不会上传。", 13f, Palette.muted), 10)
        status = label("正在启动相机…", 14f, Palette.accent)
        footer.block(status, 12)
        val actions = row()
        actions.addView(action("相册识别", false) { pickImage() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(action("粘贴内容", false) { paste() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(action("取消") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        footer.block(actions, 0)
        root.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        resultPanel = card().apply {
            visibility = View.GONE
            setPadding(dp(20), dp(20), dp(20), dp(20))
            resultTitle = label("", 22f, bold = true)
            addView(resultTitle); gap(10)
            resultDetail = label("", 14f, Palette.muted).apply { setTextIsSelectable(true) }
            addView(resultDetail); gap(16)
            val buttons = row()
            buttons.addView(action("再扫一次", false) { resumeScanning() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            buttons.addView(action("完成") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
            block(buttons, 0)
            block(label("签到结果以官方雨课堂的考勤记录为准。", 12f, Palette.muted), 0)
        }
        root.addView(resultPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { marginStart = dp(22); marginEnd = dp(22) })
        setContentView(root)
    }

    private fun ensureCamera() {
        if (cameraProvider != null || cameraStarting) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        startCamera()
    }

    private fun startCamera() {
        if (cameraProvider != null || cameraStarting || closed) return
        cameraStarting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            if (closed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyze) }
                provider.unbindAll()
                // ComponentActivity is a LifecycleOwner, so this binds through the public vararg API.
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
                if (!busy && resultPanel.visibility == View.GONE) status.text = "对准课堂大屏上的签到二维码"
            } catch (_: Exception) {
                status.text = "相机启动失败，可用相册识别或粘贴二维码内容"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (busy || closed) return
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val text = QrImages.decodeYuv(data, plane.rowStride, image.width, image.height) ?: return
            if (busy) return
            busy = true
            runOnUiThread { submit(text) }
        } catch (_: Exception) {
            // A single unreadable frame is normal; the next one may decode.
        } finally {
            image.close()
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try { startActivityForResult(intent, REQUEST_IMAGE) }
        catch (_: Exception) { Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Activity result API is fine here; the app has no AndroidX activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        busy = true
        status.text = "正在识别图片…"
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                QrImages.readBitmap(this@ScanActivity, uri)?.let { QrImages.decode(it) }
            }
            if (text == null) {
                busy = false
                status.text = "图片里没有识别到二维码，请换一张更清晰的截图"
            } else submit(text)
        }
    }

    private fun paste() {
        val input = EditText(this).apply {
            hint = "粘贴二维码里的链接，例如 https://…yuketang.cn/…"
            setTextColor(Palette.text); setHintTextColor(Palette.muted); textSize = 14f; minLines = 2
        }
        AlertDialog.Builder(this).setTitle("粘贴二维码内容").setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("签到") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show() else submit(text)
            }.show()
    }

    private fun submit(raw: String) {
        val account = graph.store.state.value.account
        if (account == null) { Toast.makeText(this, "请先登录雨课堂账号", Toast.LENGTH_LONG).show(); finish(); return }
        if (account.demo) { show(false, "演示账号不能签到", "请在“账号管理”中添加真实账号后重试"); return }
        if (account.sessionId.isBlank()) { show(false, "登录已失效", "请在设置中重新登录此账号"); return }
        busy = true
        status.text = "正在提交签到…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try { Result.success(graph.attendance.submit(account, raw)) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { Result.failure(error) }
            }
            outcome.onSuccess { result ->
                val (ok, title, detail) = describe(result)
                withContext(Dispatchers.IO) { record(account, ok, title) }
                show(ok, title, detail)
            }.onFailure { error ->
                val title = if (error is SessionExpired) "登录已失效，请重新登录" else "签到未完成"
                val detail = if (error is SessionExpired) "账号会话已过期，重新登录后再扫一次。" else "请检查网络后重试；若仍失败，请在官方雨课堂核对考勤。"
                withContext(Dispatchers.IO) {
                    if (error is SessionExpired) graph.store.expire(account)
                    record(account, false, title)
                }
                show(false, title, detail)
            }
            if (resultPanel.visibility != View.VISIBLE) {
                busy = false
                status.text = "对准课堂大屏上的签到二维码"
            }
        }
    }

    private fun describe(outcome: AttendanceOutcome): Triple<Boolean, String, String> = when (outcome) {
        is AttendanceOutcome.Signed -> Triple(true,
            if (outcome.repeated) "该二维码已签到" else "签到成功",
            listOfNotNull(
                outcome.lessonName.takeIf { it.isNotBlank() }?.let { "课堂：$it" },
                "课堂编号 ${outcome.lessonId}",
                "已向雨课堂提交本次签到，考勤状态以官方端为准。",
            ).joinToString("\n"))
        is AttendanceOutcome.Rejected -> Triple(false, outcome.issue.summary,
            outcome.issue.advice + outcome.code?.let { "\n接口代码 $it" }.orEmpty())
    }

    private fun record(account: Account, ok: Boolean, title: String) {
        if (graph.store.profile(account.key) == null) return
        graph.store.event(ClassroomEvent(if (ok) EventKind.SIGN_IN else EventKind.ERROR, "动态二维码签到：$title"), account.key)
    }

    private fun show(ok: Boolean, title: String, detail: String) {
        resultTitle.text = title
        resultTitle.setTextColor(if (ok) Palette.accent else Palette.warning)
        resultDetail.text = detail
        resultPanel.visibility = View.VISIBLE
        status.text = if (ok) "签到已提交" else "未完成签到"
        busy = false
    }

    private fun resumeScanning() {
        resultPanel.visibility = View.GONE
        busy = false
        status.text = "对准课堂大屏上的签到二维码"
        ensureCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else status.text = "相机未授权，可用相册识别或粘贴二维码内容"
    }

    override fun onResume() {
        super.onResume()
        if (resultPanel.visibility == View.GONE) ensureCamera()
    }

    override fun onDestroy() {
        closed = true
        scope.cancel()
        runCatching { cameraProvider?.unbindAll() }
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CAMERA = 21
        const val REQUEST_IMAGE = 22
    }
}
