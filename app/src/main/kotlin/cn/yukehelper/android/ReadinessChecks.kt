package cn.yukehelper.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import cn.yukehelper.core.StartupReadiness

/** Reads the system state the readiness gate needs; the decision itself lives in core. */
internal object ReadinessChecks {
    private const val PREFS = "readiness"
    private const val KEY_BACKGROUND = "background_confirmed"

    fun state(context: Context): StartupReadiness {
        // Channel records must exist before their importance can be read.
        Notifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val permission = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return StartupReadiness(
            notificationsGranted = permission && manager.areNotificationsEnabled(),
            classroomChannelEnabled = manager.getNotificationChannel("classroom")?.importance != NotificationManager.IMPORTANCE_NONE,
            homeworkChannelEnabled = manager.getNotificationChannel("homework")?.importance != NotificationManager.IMPORTANCE_NONE,
            batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName),
            backgroundConfirmed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BACKGROUND, false),
        )
    }

    fun confirmBackground(context: Context, confirmed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BACKGROUND, confirmed).apply()
    }
}
