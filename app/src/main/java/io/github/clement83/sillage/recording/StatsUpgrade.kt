package io.github.clement83.sillage.recording

import io.github.clement83.sillage.data.RecordingDao
import io.github.clement83.sillage.geo.TrackStats
import io.github.clement83.sillage.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Counts the finished rides again when the way distance and climb are computed changes, so that old
 * and new rides compare. Version 2: GPS altitude is cleaned before the climb is counted.
 */
object StatsUpgrade {
    private const val VERSION = 2

    suspend fun run(dao: RecordingDao, settings: SettingsRepository) {
        if (settings.statsVersion.first() >= VERSION) return
        for (recording in dao.finished()) {
            val stats = TrackStats()
            var segment: Int? = null
            for (point in dao.points(recording.id)) {
                stats.add(point.toFix(), newSegment = point.segment != segment)
                segment = point.segment
            }
            dao.setStats(recording.id, stats.distanceM, stats.elevationGainM)
        }
        settings.setStatsVersion(VERSION)
    }
}
