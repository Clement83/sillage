package io.github.clement83.sillage.geo

/** One GPS position, free of Android types so the track logic stays testable on the JVM. */
data class Fix(
    /** UTC epoch milliseconds. */
    val time: Long,
    val lat: Double,
    val lon: Double,
    /** Metres, as reported by the receiver (WGS84 ellipsoid height on most phones). */
    val alt: Double? = null,
    /** Horizontal accuracy radius in metres (68 %). */
    val accuracy: Float? = null,
    /** Metres per second. */
    val speed: Float? = null,
)
