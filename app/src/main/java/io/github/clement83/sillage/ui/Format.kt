package io.github.clement83.sillage.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

object Format {

    /** "850 m", "4.25 km", "37.4 km" with the locale's decimal separator. */
    fun distance(metres: Double, locale: Locale = Locale.getDefault()): String = when {
        metres < 1000 -> "${metres.roundToLong()} m"
        metres < 10_000 -> String.format(locale, "%.2f km", metres / 1000)
        else -> String.format(locale, "%.1f km", metres / 1000)
    }

    fun elevation(metres: Double): String = "${metres.roundToLong()} m"

    /** "4:05" under an hour, "1:04:05" above. */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** "10:32:05": key moments are matched against video timestamps, so seconds matter. */
    fun timeOfDay(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun dateTime(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
