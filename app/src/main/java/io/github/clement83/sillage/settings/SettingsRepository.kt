package io.github.clement83.sillage.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val LOCK_SCREEN_MAP = booleanPreferencesKey("lock_screen_map")
        val OFFLINE_FIRST = booleanPreferencesKey("offline_first")
        val OFFLINE_MOBILE_DATA = booleanPreferencesKey("offline_mobile_data")
    }
}
