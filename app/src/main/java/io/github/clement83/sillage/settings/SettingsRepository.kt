package io.github.clement83.sillage.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    /** The singletrack server typed by the user or set by a pairing QR code; none until then. */
    val serverUrl: Flow<String?> = store.data.map { it[SERVER_URL] }

    /**
     * Saves the address if it is valid and returns its canonical form, or null if rejected. A blank
     * address forgets the server and returns "".
     */
    suspend fun setServerUrl(input: String): String? {
        if (input.isBlank()) {
            store.edit { it.remove(SERVER_URL) }
            return ""
        }
        val url = ServerUrl.normalize(input) ?: return null
        store.edit { it[SERVER_URL] = url }
        return url
    }

    /** Show the recording map over the lock screen (#1725). On by default, can be turned off. */
    val lockScreenMap: Flow<Boolean> = store.data.map { it[LOCK_SCREEN_MAP] ?: true }

    suspend fun setLockScreenMap(enabled: Boolean) {
        store.edit { it[LOCK_SCREEN_MAP] = enabled }
    }

    /** Show the downloaded map instead of OpenTopoMap, even with network (#1729). */
    val offlineFirst: Flow<Boolean> = store.data.map { it[OFFLINE_FIRST] ?: false }

    suspend fun setOfflineFirst(enabled: Boolean) {
        store.edit { it[OFFLINE_FIRST] = enabled }
    }

    /** Download offline maps over mobile data too, not only Wi-Fi (#1729). Off by default. */
    val offlineMobileData: Flow<Boolean> = store.data.map { it[OFFLINE_MOBILE_DATA] ?: false }

    suspend fun setOfflineMobileData(enabled: Boolean) {
        store.edit { it[OFFLINE_MOBILE_DATA] = enabled }
    }

    /** Show, export and send the clean track rather than the raw GPS fixes (#1795). */
    val smoothTrack: Flow<Boolean> = store.data.map { it[SMOOTH_TRACK] ?: true }

    suspend fun setSmoothTrack(enabled: Boolean) {
        store.edit { it[SMOOTH_TRACK] = enabled }
    }

    /** Off-track alert (#1797): vibrate when I leave the followed track, remind every 2 min, beep. */
    val offTrackAlert: Flow<Boolean> = store.data.map { it[OFF_TRACK_ALERT] ?: true }
    val offTrackReminder: Flow<Boolean> = store.data.map { it[OFF_TRACK_REMINDER] ?: true }
    val offTrackSound: Flow<Boolean> = store.data.map { it[OFF_TRACK_SOUND] ?: false }

    suspend fun setOffTrackAlert(enabled: Boolean) {
        store.edit { it[OFF_TRACK_ALERT] = enabled }
    }

    suspend fun setOffTrackReminder(enabled: Boolean) {
        store.edit { it[OFF_TRACK_REMINDER] = enabled }
    }

    suspend fun setOffTrackSound(enabled: Boolean) {
        store.edit { it[OFF_TRACK_SOUND] = enabled }
    }

    /** Version of the distance and climb computation the stored rides were counted with. */
    val statsVersion: Flow<Int> = store.data.map { it[STATS_VERSION] ?: 1 }

    suspend fun setStatsVersion(version: Int) {
        store.edit { it[STATS_VERSION] = version }
    }

    private companion object {
        val STATS_VERSION = intPreferencesKey("stats_version")
        val SMOOTH_TRACK = booleanPreferencesKey("smooth_track")
        val OFF_TRACK_ALERT = booleanPreferencesKey("off_track_alert")
        val OFF_TRACK_REMINDER = booleanPreferencesKey("off_track_reminder")
        val OFF_TRACK_SOUND = booleanPreferencesKey("off_track_sound")
        val SERVER_URL = stringPreferencesKey("server_url")
        val LOCK_SCREEN_MAP = booleanPreferencesKey("lock_screen_map")
        val OFFLINE_FIRST = booleanPreferencesKey("offline_first")
        val OFFLINE_MOBILE_DATA = booleanPreferencesKey("offline_mobile_data")
    }
}
