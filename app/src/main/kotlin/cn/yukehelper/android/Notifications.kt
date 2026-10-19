package cn.yukehelper.android

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cn.yukehelper.core.*

class Notifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel("monitor", "课堂监测", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("classroom", "课堂事件提醒", NotificationManager.IMPORTANCE_HIGH))
    }
    private fun contentIntent() = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun foreground(text: String): Notification {
        val stop = PendingIntent.getService(context, 1,
            Intent(context, MonitorService::class.java).setAction(MonitorService.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, "monitor").setSmallIcon(R.drawable.ic_notification)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .setContentTitle("雨课助手 · 课堂监测中").setContentText(text)
            .setContentIntent(contentIntent()).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止监测", stop).build()).build()
    }
    fun update(text: String) { manager.notify(FOREGROUND_ID, foreground(text)) }
    fun event(event: ClassroomEvent, account: Account) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        val id = 100 + ((account.key + event.lessonId + event.kind.name).hashCode() and 0x3fffffff)
        manager.notify(id, Notification.Builder(context, "classroom")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("${account.name} · ${event.course.ifBlank { "课堂提醒" }}")
            .setContentText(event.summary).setStyle(Notification.BigTextStyle().bigText(event.summary))
            .setContentIntent(contentIntent()).setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT).build())
    }
    companion object { const val FOREGROUND_ID = 1 }
}
