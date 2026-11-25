package io.github.clement83.sillage.geo

/** A position without time: a point of an imported (planned) track. */
data class GeoPoint(val lat: Double, val lon: Double, val alt: Double? = null)

/** A stretch of track drawn in one colour: its average grade (rise / run), null without altitudes. */
data class GradeChunk(val points: List<GeoPoint>, val grade: Double?)

/** One sample of the elevation profile: distance from the start (gaps excluded) and altitude. */
data class ProfileSample(val distanceM: Double, val alt: Double)

/** Pure geometry of a planned track: distance, climb, grade colouring, profile, where I am on it. */
object TrackShape {

    fun distanceM(segments: List<List<GeoPoint>>): Double =
        segments.sumOf { seg -> seg.zipWithNext().sumOf { (a, b) -> GeoMath.distanceM(a.lat, a.lon, b.lat, b.lon) } }

    /** Same hysteresis as a recording, so a followed track and the ride on it compare fairly. */
    fun elevationGainM(segments: List<List<GeoPoint>>): Double {
        val stats = TrackStats.raw(minStepM = 0.0)
        var t = 0L
        segments.forEach { seg ->
            seg.forEachIndexed { i, p -> stats.add(Fix(t++, p.lat, p.lon, p.alt), newSegment = i == 0) }
        }
        return stats.elevationGainM
    }

    /**
     * Cuts the track into stretches of about [chunkM] metres and gives each its grade. Short chunks
     * would colour GPS altitude noise; long ones would hide a steep ramp.
     */
    fun gradeChunks(segments: List<List<GeoPoint>>, chunkM: Double = 100.0): List<GradeChunk> {
        val chunks = mutableListOf<GradeChunk>()
        for (seg in segments) {
            if (seg.size < 2) continue
            var current = mutableListOf(seg[0])
            var run = 0.0
            for (i in 1 until seg.size) {
                run += GeoMath.distanceM(seg[i - 1].lat, seg[i - 1].lon, seg[i].lat, seg[i].lon)
                current.add(seg[i])
                if (run >= chunkM || i == seg.size - 1) {
                    val first = current.first().alt
                    val last = current.last().alt
                    val grade = if (first != null && last != null && run > 0) (last - first) / run else null
                    chunks += GradeChunk(current, grade)
                    current = mutableListOf(seg[i])
                    run = 0.0
                }
            }
        }
        return chunks
    }

    /** Elevation profile; empty when the track has no altitude. */
    fun profile(segments: List<List<GeoPoint>>): List<ProfileSample> {
        val samples = mutableListOf<ProfileSample>()
        var distance = 0.0
        for (seg in segments) {
            seg.forEachIndexed { i, p ->
                if (i > 0) distance += GeoMath.distanceM(seg[i - 1].lat, seg[i - 1].lon, p.lat, p.lon)
                p.alt?.let { samples += ProfileSample(distance, it) }
            }
        }
        return samples
    }

    /** Distance along the track of the point nearest to me, and how far I am from it. */
    fun locate(segments: List<List<GeoPoint>>, lat: Double, lon: Double): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        var distance = 0.0
        for (seg in segments) {
            seg.forEachIndexed { i, p ->
                if (i > 0) distance += GeoMath.distanceM(seg[i - 1].lat, seg[i - 1].lon, p.lat, p.lon)
                val away = GeoMath.distanceM(lat, lon, p.lat, p.lon)
                if (best == null || away < best!!.second) best = distance to away
            }
        }
        return best
    }
}
