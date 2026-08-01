package com.example.timbertimer.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A signed-in Supabase user plus the tokens needed to act on their behalf. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?,
) {
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: "Signed in"

    /**
     * Treats the token as expired a minute early so a request started just
     * before the boundary does not arrive after it.
     */
    fun isExpired(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds >= expiresAtEpochSeconds - EXPIRY_SKEW_SECONDS

    private companion object {
        const val EXPIRY_SKEW_SECONDS = 60L
    }
}

private val Context.authDataStore by preferencesDataStore(name = "timber-auth")

/**
 * Persists the session in app-private DataStore so sign-in survives restarts.
 * The PKCE verifier is kept here too, because the OAuth round trip goes through
 * an external browser and the app process can be killed while it is away.
 */
class SessionStore(context: Context) {

    private val dataStore = context.applicationContext.authDataStore

    val sessionFlow: Flow<Session?> = dataStore.data.map { prefs ->
        val accessToken = prefs[KEY_ACCESS] ?: return@map null
        val refreshToken = prefs[KEY_REFRESH] ?: return@map null
        val userId = prefs[KEY_USER_ID] ?: return@map null
        Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = prefs[KEY_EXPIRES_AT] ?: 0L,
            userId = userId,
            email = prefs[KEY_EMAIL],
            displayName = prefs[KEY_NAME],
            avatarUrl = prefs[KEY_AVATAR],
        )
    }

    suspend fun current(): Session? = sessionFlow.first()

    suspend fun save(session: Session) {
        dataStore.edit { prefs ->
            prefs[KEY_ACCESS] = session.accessToken
            prefs[KEY_REFRESH] = session.refreshToken
            prefs[KEY_EXPIRES_AT] = session.expiresAtEpochSeconds
            prefs[KEY_USER_ID] = session.userId
            session.email?.let { prefs[KEY_EMAIL] = it } ?: prefs.remove(KEY_EMAIL)
            session.displayName?.let { prefs[KEY_NAME] = it } ?: prefs.remove(KEY_NAME)
            session.avatarUrl?.let { prefs[KEY_AVATAR] = it } ?: prefs.remove(KEY_AVATAR)
        }
    }

    /**
     * Signs out completely. This store holds nothing but auth state, including
     * any half-finished PKCE verifier, so all of it should go.
     */
    suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    suspend fun saveVerifier(verifier: String) {
        dataStore.edit { it[KEY_VERIFIER] = verifier }
    }

    /** Reads and immediately clears the verifier — valid for one exchange only. */
    suspend fun takeVerifier(): String? {
        val verifier = dataStore.data.first()[KEY_VERIFIER]
        if (verifier != null) {
            dataStore.edit { it.remove(KEY_VERIFIER) }
        }
        return verifier
    }

    private companion object {
        val KEY_ACCESS = stringPreferencesKey("access_token")
        val KEY_REFRESH = stringPreferencesKey("refresh_token")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_EMAIL = stringPreferencesKey("email")
        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_AVATAR = stringPreferencesKey("avatar_url")
        val KEY_VERIFIER = stringPreferencesKey("pkce_verifier")
    }
}
