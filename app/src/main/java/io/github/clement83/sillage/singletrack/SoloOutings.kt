package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.RecordingStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A ride made into a private solo outing on singletrack, by hand (#1766). */
object SoloOutings {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** A finished ride that is not on an outing, with something to draw. */
    fun canMake(recording: RecordingEntity): Boolean =
        recording.outingId == null && recording.status == RecordingStatus.FINISHED && recording.pointCount >= 2

    /** `startedAt` as singletrack wants it: ISO 8601 with the offset of [zone], to the second. */
    fun startedAt(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ISO.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
