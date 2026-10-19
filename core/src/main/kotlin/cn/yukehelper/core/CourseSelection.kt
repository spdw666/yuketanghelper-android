package cn.yukehelper.core

/** Only course selection belongs to an account; operational rules belong to Configuration. */
object CourseSelection {
    fun filter(courses: List<Course>, query: String): List<Course> {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return courses
        return courses.filter { course ->
            val text = listOf(course.name, course.classroomName, course.teacherName, course.id).joinToString(" ")
            words.all { text.contains(it, ignoreCase = true) }
        }
    }

    fun apply(profile: AccountProfile, knownIds: Set<String>, selectedIds: Set<String>): AccountProfile {
        val ids = profile.courses.map { it.id }.toSet().intersect(knownIds)
        return profile.copy(excludedCourses = (profile.excludedCourses - ids) + (ids - selectedIds))
    }
}
