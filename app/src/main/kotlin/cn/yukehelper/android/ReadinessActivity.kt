package cn.yukehelper.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import cn.yukehelper.core.ReadinessRequirement
import cn.yukehelper.core.StartupReadiness

/**
 * Mandatory gate before monitoring: every item either jumps to the exact system page that fixes it,
 * or — for the brand background switch no API exposes — is confirmed explicitly by the user.
 */
class ReadinessActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column()
        val header = row().apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
        header.addView(action("返回", false) { finish() }, touchParams(dp(76)))
        header.addView(label("后台运行准备", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        root.addView(header)
        body = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        showContent(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) render()
    }

    private fun render() {
        body.removeAllViews()
        val state = ReadinessChecks.state(this)
        body.block(label("开始监测前请完成以下设置", 24f, bold = true), 6)
        body.block(label("监测是锁屏后继续运行的前台服务。下列项目由系统判定，缺任意一项都可能让监测在锁屏后中断；点“去开启”会直接跳到对应系统页面。", 13f, Palette.muted), 18)
        ReadinessRequirement.entries.forEach { requirement -> requirementCard(requirement, state) }

        if (state.ready) {
            body.block(card().apply {
                addView(label("已就绪", 18f, bold = true)); gap(6)
                addView(label("全部条件已满足，可以开始监测。", 13f, Palette.accent)); gap()
                block(action("完成") { finish() }, 0)
            })
        } else {
            body.block(action("重新检查", false) { render() }, 10)
            body.block(label("还有 ${state.missing().size} 项未完成：${state.missing().joinToString("、") { it.summary }}", 13f, Palette.warning))
        }
        body.block(label("机型差异：部分品牌还有自启动、关联启动或“最近任务锁定”等开关，系统没有统一接口可供读取，需要你在对应页面手动确认一次。", 12f, Palette.muted))
    }

    private fun requirementCard(requirement: ReadinessRequirement, state: StartupReadiness) {
        val missing = requirement in state.missing()
        body.block(card().apply {
            val head = row()
            head.addView(label(requirement.summary, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(label(if (missing) "未完成" else "已就绪", 12f, if (missing) Palette.warning else Palette.accent))
            addView(head); gap(6)
            addView(label(requirement.advice, 13f, Palette.muted))
            gap(8)
            if (requirement == ReadinessRequirement.BACKGROUND_ALLOWED) {
                addView(CheckBox(this@ReadinessActivity).apply {
                    text = "我已按下方提示完成设置"; textSize = 14f; setTextColor(Palette.text)
                    isChecked = state.backgroundConfirmed
                    setOnCheckedChangeListener { _, checked ->
                        ReadinessChecks.confirmBackground(this@ReadinessActivity, checked)
                        body.post { render() }
                    }
                })
                block(action("打开本应用系统设置", false) { openAppDetails() }, 8)
                addView(label(brandBackgroundAdvice(), 12f, Palette.muted))
            } else {
                block(action("去开启", false) { fix(requirement) }, 0)
            }
        }, 10)
    }

    private fun fix(requirement: ReadinessRequirement) {
        when (requirement) {
            ReadinessRequirement.NOTIFICATIONS -> {
                val granted = Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!granted) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                else openNotificationSettings()
            }
            ReadinessRequirement.NOTIFICATION_CHANNELS -> openNotificationSettings()
            ReadinessRequirement.BATTERY_UNRESTRICTED -> requestBatteryExemption()
            ReadinessRequirement.BACKGROUND_ALLOWED -> openAppDetails()
        }
    }

    private fun openNotificationSettings() {
        openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun openAppDetails() {
        openSystemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** The direct request dialog is the only page that can grant exemption in one tap. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val packageUri = Uri.parse("package:$packageName")
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in candidates) {
            try { startActivity(intent); return } catch (_: Exception) { }
        }
        Toast.makeText(this, "请手动进入系统设置，关闭本应用的电池限制", Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 31
    }
}
