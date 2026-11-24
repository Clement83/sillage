package io.github.clement83.sillage.map

/** Colour of a stretch of planned track by its grade: blues downhill, green flat, yellow to dark red uphill. */
object GradeColors {
    const val UNKNOWN = "#3F7CAC"

    fun hex(grade: Double?): String = when {
        grade == null -> UNKNOWN
        grade <= -0.10 -> "#1F5FA0"
        grade < -0.03 -> "#5B9BD5"
        grade < 0.03 -> "#2E9E5B"
        grade < 0.06 -> "#E3B505"
        grade < 0.10 -> "#F08A24"
        grade < 0.15 -> "#D63A2A"
        else -> "#7E1416"
    }
}
