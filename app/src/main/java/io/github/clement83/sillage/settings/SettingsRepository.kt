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

    val serverUrl: Flow<String> = store.data.map { it[SERVER_URL] ?: ServerUrl.DEFAULT }

    /** Saves the address if it is valid; returns the stored canonical form, or null if rejected. */
    suspend fun setServerUrl(input: String): String? {
        val url = ServerUrl.normalize(input) ?: return null
        store.edit { it[SERVER_URL] = url }
        return url
    }

    /** Show the recording map over the lock screen (#1725). On by default, can be turned off. */
    val lockScreenMap: Flow<Boolean> = store.data.map { it[LOCK_SCREEN_MAP] ?: true }

    suspend fun setLockScreenMap(enabled: Boolean) {
        store.edit { it[LOCK_SCREEN_MAP] = enabled }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val LOCK_SCREEN_MAP = booleanPreferencesKey("lock_screen_map")
    }
}
