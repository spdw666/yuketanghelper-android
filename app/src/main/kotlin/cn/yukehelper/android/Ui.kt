package cn.yukehelper.android

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*

internal object Palette {
    val background = Color.rgb(16, 26, 37)
    val card = Color.rgb(26, 41, 56)
    val border = Color.rgb(43, 62, 78)
    val text = Color.rgb(238, 246, 250)
    val muted = Color.rgb(170, 189, 202)
    val accent = Color.rgb(117, 227, 196)
    val warning = Color.rgb(255, 207, 137)
}
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
internal fun Context.label(value: String, size: Float = 14f, color: Int = Palette.text, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(dp(3).toFloat(), 1f)
}
internal fun Context.shape(color: Int, radius: Int = 18, border: Boolean = false) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat()
    if (border) setStroke(dp(1), Palette.border)
}
internal fun Context.card() = column().apply { background = shape(Palette.card, border = true); setPadding(dp(18), dp(18), dp(18), dp(18)) }
internal fun LinearLayout.gap(height: Int = 12) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(height))) }
internal fun LinearLayout.block(view: View, gap: Int = 14) {
    addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(gap) })
}
internal fun Context.action(title: String, primary: Boolean = true, click: () -> Unit): Button = Button(this).apply {
    text = title; textSize = 15f; isAllCaps = false; minHeight = dp(50)
    setTextColor(if (primary) Palette.background else Palette.text)
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(0x3375E3C4), shape(if (primary) Palette.accent else Palette.card, 14, !primary), null)
    setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { click() }
}
/** A bounded reading width also responds to split-screen/folded window resizing. */
internal fun Activity.showContent(content: View) {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    if (Build.VERSION.SDK_INT in 30..34) {
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
    }
    if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    val frame = object : FrameLayout(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            content.layoutParams.width = minOf(available, dp(840))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        setBackgroundColor(Palette.background)
        addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
    }
    setContentView(frame)
    fitInsets(frame)
}
private fun Activity.fitInsets(root: View) {
    root.setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        } else if (Build.VERSION.SDK_INT >= 28) {
            val cutout = insets.displayCutout
            @Suppress("DEPRECATION")
            view.setPadding(maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0))
        } else {
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
    root.requestApplyInsets()
}

/** Text can wrap with the system font size; 48dp remains the minimum touch height. */
internal fun Context.touchParams(width: Int = -1) = LinearLayout.LayoutParams(width, -2)
internal fun Context.compactAction(title: String, click: () -> Unit) = action(title, false, click).apply {
    textSize = 13f; minWidth = 0; minimumWidth = 0; minHeight = dp(48)
    setPadding(dp(8), dp(8), dp(8), dp(8))
}
internal fun Activity.openSystemSettings(intent: Intent) {
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    for (candidate in listOf(intent, fallback, Intent(Settings.ACTION_SETTINGS))) {
        try { startActivity(candidate); return } catch (_: Exception) { }
    }
    Toast.makeText(this, "请手动进入系统设置，打开雨课助手的应用管理页面", Toast.LENGTH_LONG).show()
}
