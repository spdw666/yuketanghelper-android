package cn.yukehelper.android

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/** Standard system intents work across brands, with app-details/system-settings fallbacks. */
class BackgroundActivity : Activity() {
    private lateinit var body: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column()
        val header = row().apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
        header.addView(action("返回", false) { finish() }, touchParams(dp(76)))
        header.addView(label("后台运行与机型适配", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        root.addView(header)
        body = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        showContent(root)
    }
    override fun onResume() { super.onResume(); render() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) render()
    }
    private fun render() {
        body.removeAllViews()
        val manager = getSystemService(NotificationManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        val allowed = manager.areNotificationsEnabled()
        val classroom = manager.getNotificationChannel("classroom")?.importance != NotificationManager.IMPORTANCE_NONE
        body.block(label("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}", 19f, bold = true))
        body.block(card().apply {
            addView(label("通知与提醒", 18f, bold = true)); gap(8)
            addView(label(when { !allowed -> "通知尚未开启，课堂提醒无法显示。"; !classroom -> "课堂事件通知类别已关闭。"; else -> "应用通知已开启；请同时检查声音、锁屏显示与勿扰模式。" }, 14f, Palette.muted)); gap()
            block(action("通知设置", false) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
                } else openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }, 8)
            block(action("课堂事件通知类别", false) {
                openSystemSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "classroom"))
            }, 0)
        })
        body.block(card().apply {
            addView(label("电池与后台", 18f, bold = true)); gap(8)
            addView(label(if (power.isIgnoringBatteryOptimizations(packageName)) "系统电池优化已豁免，仍需留意品牌的后台管理。" else "系统可能限制锁屏后的网络连接。可按需要在电池优化列表中为本应用选择“不优化”。", 14f, Palette.muted)); gap()
            block(action("系统电池优化列表", false) { openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, 8)
            block(action("本应用后台与电池设置", false) {
                openSystemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }, 8)
            addView(label(brandAdvice(), 14f, Palette.muted))
        })
        body.block(label("课堂期间手动开始监测，保持监测通知可见。避免从最近任务中划掉应用或执行一键清理；返回 App 可查看是否仍在运行。每次最多 4 小时，系统停止后需重新开启。以上系统页面和选项名称会随系统版本变化。", 13f, Palette.muted))
        body.block(label("网页登录依赖手机的 Android System WebView 或系统浏览器组件；组件不可用时可使用扫码登录。纯 HarmonyOS NEXT 不支持 Android APK。", 13f, Palette.muted))
    }
    private fun brandAdvice(): String {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        return when {
            listOf("vivo", "iqoo").any(brand::contains) -> "vivo / iQOO：检查后台耗电管理、允许后台高耗电及自启动；如系统提供，可在最近任务中锁定本应用。"
            listOf("xiaomi", "redmi", "poco").any(brand::contains) -> "小米 / Redmi / POCO：检查应用省电策略（无限制）、后台自启动和最近任务锁定。"
            listOf("oppo", "realme", "oneplus").any(brand::contains) -> "OPPO / realme / 一加：检查应用电池管理中的后台活动、自启动或关联启动，并留意自动冻结。"
            listOf("huawei", "honor").any(brand::contains) -> "华为 / 荣耀：在应用启动管理中检查手动管理、自启动、关联启动和后台活动。需系统支持 Android APK。"
            "samsung" in brand -> "三星：检查后台使用限制，避免将本应用列入休眠或深度休眠应用；可按需要加入从不自动休眠列表。"
            else -> "其他品牌：检查应用信息中的电池限制、后台活动、自启动及省电模式。没有对应选项时使用系统默认设置，并在锁屏后验证监测。"
        }
    }
}
