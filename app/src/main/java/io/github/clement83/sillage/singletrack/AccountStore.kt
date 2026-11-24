package io.github.clement83.sillage.singletrack

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(name = "account")

/** The singletrack account paired with the app (#1728). */
data class Account(
    val server: String,
    /** The app token, decrypted: never logged, never shown. */
    val token: String,
    val pairedAt: Long,
    /** Last successful refresh of "My outings", null before the first one. */
    val lastSyncAt: Long?,
)

/**
 * Where the app token lives: in DataStore, encrypted by a key of the Android Keystore ([TokenCipher]).
 * A token that cannot be decrypted (app data restored on another phone, key lost) reads as "not
 * paired": the user pairs again.
 */
class AccountStore(context: Context) {

    private val store = context.applicationContext.accountStore

    val account: Flow<Account?> = store.data.map { prefs ->
        val server = prefs[SERVER] ?: return@map null
        val token = prefs[TOKEN]?.let(TokenCipher::decrypt) ?: return@map null
        Account(server, token, prefs[PAIRED_AT] ?: 0, prefs[LAST_SYNC])
    }

    suspend fun current(): Account? = account.first()

    suspend fun save(server: String, token: String, now: Long = System.currentTimeMillis()) {
        val encrypted = TokenCipher.encrypt(token)
        store.edit {
            it.clear()
            it[SERVER] = server
            it[TOKEN] = encrypted
            it[PAIRED_AT] = now
        }
    }

    suspend fun markSynced(now: Long = System.currentTimeMillis()) {
        store.edit { if (it.contains(SERVER)) it[LAST_SYNC] = now }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val TOKEN = stringPreferencesKey("token")
        val PAIRED_AT = longPreferencesKey("paired_at")
        val LAST_SYNC = longPreferencesKey("last_sync")
    }
}
