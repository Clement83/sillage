package io.github.clement83.sillage.singletrack

import io.github.clement83.sillage.data.OutingEntity
import io.github.clement83.sillage.data.RecordingEntity
import io.github.clement83.sillage.data.RecordingStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** A ride made into a private solo outing on singletrack, by hand (#1766). */
object SoloOutings {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** A finished ride that is not on an outing, with something to draw. */
    fun canMake(recording: RecordingEntity): Boolean =
        recording.outingId == null && recording.status == RecordingStatus.FINISHED && recording.pointCount >= 2

    /**
     * The outing a ride started off any outing was probably part of (#1777): same day, starting within
     * an hour of the ride, not cancelled, one the paired account (on [server]) can send to; the closest
     * in time. Only the time counts, not the route.
     */
    fun matchingOuting(
        rideStart: Long,
        outings: List<OutingEntity>,
        server: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): OutingEntity? {
        val day = Instant.ofEpochMilli(rideStart).atZone(zone).toLocalDate()
        return outings
            .filter { it.server == server || it.inviteToken != null }
            .filter { Instant.ofEpochMilli(it.startsAt).atZone(zone).toLocalDate() == day }
            .filter { abs(it.startsAt - rideStart) <= MATCH_WINDOW_MS }
            .filter { it.decode()?.cancelled != true }
            .minByOrNull { abs(it.startsAt - rideStart) }
    }

    private const val MATCH_WINDOW_MS = 3_600_000L

    /** `startedAt` as singletrack wants it: ISO 8601 with the offset of [zone], to the second. */
    fun startedAt(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ISO.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
