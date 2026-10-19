package cn.yukehelper.android

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.*
import cn.yukehelper.core.*

/** Recycles compact rows; searching never rebuilds the search field or loses hidden selections. */
// Internal Kotlin-only view with required callbacks; never inflated from XML.
@SuppressLint("ViewConstructor")
internal class CourseChecklist(
    context: Context,
    private val changed: (Set<String>) -> Unit,
) : LinearLayout(context) {
    private var courses: List<Course> = emptyList()
    private var visible: List<Course> = emptyList()
    private var selected: Set<String> = emptySet()
    private var active: Set<String> = emptySet()
    private val counter = context.label("", 12f, Palette.muted)
    private val empty = context.label("没有找到课程", 14f, Palette.muted).apply {
        gravity = android.view.Gravity.CENTER; setPadding(0, context.dp(16), 0, context.dp(16))
    }
    private val search = EditText(context).apply {
        hint = "搜索课程、班级或教师"; contentDescription = "搜索课程、班级或教师"
        textSize = 14f; isSingleLine = true; minHeight = context.dp(48); setTextColor(Palette.text); setHintTextColor(Palette.muted)
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        background = context.shape(Palette.card, 10)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private val rows = Rows()
    private val list = ListView(context).apply {
        divider = ColorDrawable(Palette.border); dividerHeight = context.dp(1)
        cacheColorHint = android.graphics.Color.TRANSPARENT
        isFastScrollEnabled = true
    }
    var query: String
        get() = search.text.toString()
        set(value) { if (query != value) search.setText(value) }
    val selectedIds get() = selected.toSet()
    init {
        orientation = VERTICAL
        val controls = context.column()
        controls.addView(search, LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        val bulk = context.row()
        bulk.addView(context.action("全选", false) { selectAll(true) }, LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(6) })
        bulk.addView(context.action("全不选", false) { selectAll(false) }, LayoutParams(0, -2, 1f))
        controls.addView(bulk)
        controls.addView(counter, LayoutParams(-1, -2).apply { topMargin = context.dp(8); bottomMargin = context.dp(6) })
        controls.addView(empty, LayoutParams(-1, -2))
        // The controls scroll with the list, so landscape / large fonts leave room for rows.
        list.addHeaderView(controls, null, false)
        list.adapter = rows
        list.itemsCanFocus = true
        addView(list, LayoutParams(-1, -1))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(filter = true) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh(filter = true)
    }
    fun bind(items: List<Course>, ids: Set<String>, activeIds: Set<String> = emptySet()) {
        val next = ids.intersect(items.map { it.id }.toSet())
        if (courses == items && selected == next && active == activeIds) return
        val reset = courses != items
        courses = items; selected = next; active = activeIds
        refresh(filter = reset)
    }
    private fun toggle(course: Course) {
        selected = selected.toMutableSet().apply { if (!add(course.id)) remove(course.id) }
        refresh(); changed(selectedIds)
    }
    private fun selectAll(enabled: Boolean) {
        selected = if (enabled) courses.map { it.id }.toSet() else emptySet()
        refresh(); changed(selectedIds)
    }
    private fun refresh(filter: Boolean = false) {
        if (filter) visible = CourseSelection.filter(courses, query)
        counter.text = if (query.isBlank()) context.getString(R.string.course_selection_count, selected.size, courses.size)
            else context.getString(R.string.course_selection_matches, selected.size, courses.size, visible.size)
        rows.notifyDataSetChanged()
        empty.visibility = if (visible.isEmpty()) VISIBLE else GONE
        if (filter) list.setSelection(0)
    }
    private inner class CourseRow : LinearLayout(context) {
        val check = CheckBox(context).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val title = context.label("", 15f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val detail = context.label("", 11f, Palette.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        init {
            orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(64); setPadding(0, context.dp(5), 0, context.dp(5))
            addView(check, LayoutParams(context.dp(48), context.dp(48)))
            addView(context.column().apply { addView(title); addView(detail) }, LayoutParams(0, -2, 1f))
        }
    }
    private inner class Rows : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? CourseRow ?: CourseRow()
            val course = getItem(position)
            row.setOnClickListener { toggle(course) }
            row.isFocusable = true
            row.isClickable = true
            row.isSelected = course.id in selected
            row.check.isChecked = course.id in selected
            row.title.text = course.name
            val detail = listOf(course.classroomName, course.teacherName).filter(String::isNotBlank).joinToString(" · ")
                .ifBlank { context.getString(R.string.course_id, course.id) }
            row.detail.text = if (course.id in active) context.getString(R.string.course_live_detail, detail) else detail
            row.contentDescription = "${course.name}，${row.detail.text}，${if (row.check.isChecked) "已选择" else "未选择"}"
            return row
        }
    }
}
