package cn.yukehelper.core

/**
 * What a monitoring session needs before it can survive a locked screen.
 *
 * Three of the four can be read from the system; the brand background/autostart switch cannot, so it
 * is recorded as an explicit user confirmation instead of being silently assumed.
 */
enum class ReadinessRequirement(val summary: String, val advice: String, val detectable: Boolean) {
    NOTIFICATIONS("通知权限未开启", "监测通知和事件提醒都依赖通知权限", true),
    NOTIFICATION_CHANNELS("课堂或作业通知类别被关闭", "在系统通知设置中重新打开对应类别", true),
    BATTERY_UNRESTRICTED("电池优化仍可能限制后台", "为本应用选择“不优化”，否则锁屏后可能断网", true),
    BACKGROUND_ALLOWED("尚未确认机型的后台/自启动设置", "部分品牌会冻结后台，需要按机型提示手动设置", false),
}

data class StartupReadiness(
    val notificationsGranted: Boolean = false,
    val classroomChannelEnabled: Boolean = true,
    val homeworkChannelEnabled: Boolean = true,
    val batteryUnrestricted: Boolean = false,
    val backgroundConfirmed: Boolean = false,
) {
    fun missing(): List<ReadinessRequirement> = buildList {
        if (!notificationsGranted) add(ReadinessRequirement.NOTIFICATIONS)
        if (!classroomChannelEnabled || !homeworkChannelEnabled) add(ReadinessRequirement.NOTIFICATION_CHANNELS)
        if (!batteryUnrestricted) add(ReadinessRequirement.BATTERY_UNRESTRICTED)
        if (!backgroundConfirmed) add(ReadinessRequirement.BACKGROUND_ALLOWED)
    }

    /** Items the system can verify; the manual brand confirmation is deliberately excluded. */
    fun blocking(): List<ReadinessRequirement> = missing().filter { it.detectable }

    val ready: Boolean get() = missing().isEmpty()
}
