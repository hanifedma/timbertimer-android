package com.example.timbertimer.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Google sign-in against Supabase GoTrue using the authorization-code flow with
 * PKCE.
 *
 * PKCE rather than the implicit flow because the redirect comes back through a
 * custom scheme: another app can register the same scheme and receive the
 * callback, so tokens must never travel in the redirect itself. With PKCE the
 * intercepted code is useless without the verifier, which never leaves this app.
 */
class SupabaseAuth(
    context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val sessionStore = SessionStore(context)

    val sessionFlow: Flow<Session?> = sessionStore.sessionFlow

    suspend fun currentSession(): Session? = sessionStore.current()

    /**
     * Builds the URL to open in a Custom Tab, generating and persisting a fresh
     * PKCE verifier for the exchange that follows.
     */
    suspend fun buildAuthorizeUrl(): String {
        val verifier = generateVerifier()
        sessionStore.saveVerifier(verifier)
        return Uri.parse("${SupabaseConfig.AUTH_PATH}/authorize").buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", SupabaseConfig.REDIRECT_URL)
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "s256")
            .build()
            .toString()
    }

    /**
     * Extracts the authorization code from the deep link the browser returned.
     * Supabase reports failures as `error`/`error_description` on the same URL,
     * which are surfaced instead of being mistaken for a missing code.
     */
    fun readCallback(uri: Uri): CallbackResult {
        val error = uri.getQueryParameter("error_description")
            ?: uri.getQueryParameter("error")
            ?: uri.fragmentParam("error_description")
            ?: uri.fragmentParam("error")
        if (!error.isNullOrBlank()) return CallbackResult.Failed(error)

        val code = uri.getQueryParameter("code") ?: uri.fragmentParam("code")
        // A null message means "no reason given" — the caller renders its own
        // localized "cancelled" text rather than a hardcoded English string.
        return if (code.isNullOrBlank()) CallbackResult.Failed(null) else CallbackResult.Code(code)
    }

    /** Trades the authorization code for a session and persists it. */
    suspend fun exchangeCode(code: String): Session {
        val verifier = sessionStore.takeVerifier() ?: throw MissingVerifierException()
        val payload = buildJsonBody("auth_code" to code, "code_verifier" to verifier)
        val token = postToken("pkce", payload)
        return token.toSession().also { sessionStore.save(it) }
    }

    /**
     * Trades an ID token that Google handed to the app directly for a session.
     *
     * This is the whole point of the native sheet: the browser is never
     * involved, so Google's prompt names *this app* rather than the Supabase
     * callback the redirect flow has to travel through.
     *
     * [rawNonce] is the value Google was asked to hash into the token, and
     * Supabase re-hashes it to check the token was minted for this request and
     * not replayed from another one.
     */
    suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String): Session {
        val payload = buildJsonBody(
            "provider" to "google",
            "id_token" to idToken,
            "nonce" to rawNonce,
        )
        return postToken("id_token", payload).toSession().also { sessionStore.save(it) }
    }

    /**
     * Returns a usable access token, refreshing first when the current one has
     * expired. Returns null when signed out, or when the refresh token has been
     * revoked — in which case the stale session is cleared so the UI drops back
     * to local mode rather than retrying forever.
     */
    suspend fun validAccessToken(): String? {
        val session = sessionStore.current() ?: return null
        if (!session.isExpired(nowSeconds())) return session.accessToken
        return try {
            val payload = buildJsonBody("refresh_token" to session.refreshToken)
            val refreshed = postToken("refresh_token", payload).toSession(fallback = session)
            sessionStore.save(refreshed)
            refreshed.accessToken
        } catch (error: Exception) {
            // Only a refusal means the grant is really gone. A refresh that failed
            // because the phone is offline must not sign the user out and strand
            // their cloud records behind a sign-in screen.
            if (error is SupabaseException && error.rejected) sessionStore.clear()
            null
        }
    }

    suspend fun signOut() {
        val token = sessionStore.current()?.accessToken
        // Best effort: revoke server-side, but always clear locally. If the
        // device is offline the user still expects to end up signed out.
        if (token != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("${SupabaseConfig.AUTH_PATH}/logout")
                        .addHeader("apikey", SupabaseConfig.ANON_KEY)
                        .addHeader("Authorization", "Bearer $token")
                        .post(ByteArray(0).toRequestBody())
                        .build()
                    client.newCall(request).execute().close()
                }
            }
        }
        sessionStore.clear()
    }

    private suspend fun postToken(grantType: String, body: String): TokenResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${SupabaseConfig.AUTH_PATH}/token?grant_type=$grantType")
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Content-Type", JSON_MEDIA_TYPE)
                .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SupabaseException(readError(payload, response.code), rejected = true)
                }
                json.decodeFromString(TokenResponse.serializer(), payload)
            }
        }

    private fun readError(payload: String, code: Int): String =
        runCatching { json.decodeFromString(ApiError.serializer(), payload).bestMessage() }
            .getOrNull()
            ?: "Supabase request failed (HTTP $code)."

    private fun TokenResponse.toSession(fallback: Session? = null): Session {
        val user = user
        val userId = user?.id ?: fallback?.userId
        ?: throw SupabaseException("Supabase did not return a user for this session.")
        return Session(
            accessToken = accessToken,
            refreshToken = refreshToken.ifBlank { fallback?.refreshToken.orEmpty() },
            expiresAtEpochSeconds = nowSeconds() + expiresIn,
            userId = userId,
            email = user?.email ?: fallback?.email,
            displayName = user?.metadata?.fullName ?: user?.metadata?.name ?: fallback?.displayName,
            avatarUrl = user?.metadata?.avatarUrl ?: fallback?.avatarUrl,
        )
    }

    private fun buildJsonBody(vararg pairs: Pair<String, String>): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } },
        )

    private fun generateVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, BASE64_URL_FLAGS)
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, BASE64_URL_FLAGS)
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /** Reads a parameter from the URL fragment, which [Uri] does not parse itself. */
    private fun Uri.fragmentParam(name: String): String? =
        fragment?.split('&')
            ?.mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
            }
            ?.firstOrNull { it.first == name }
            ?.second
            ?.let { Uri.decode(it) }

    sealed interface CallbackResult {
        data class Code(val value: String) : CallbackResult

        /** [message] is the provider's own wording, or null if it gave none. */
        data class Failed(val message: String?) : CallbackResult
    }

    companion object {
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val BASE64_URL_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Failure carrying a message already fit to show to the user.
 *
 * [rejected] separates "the server said no" from "the request never arrived".
 * Only the former should ever discard a stored session.
 */
class SupabaseException(
    message: String,
    val rejected: Boolean = false,
) : Exception(message)

/**
 * The PKCE verifier was gone when the redirect came back — usually because app
 * data was cleared while the browser was open. Typed rather than messaged so the
 * UI can show it in the user's language.
 */
class MissingVerifierException : Exception("Sign-in could not be verified.")
