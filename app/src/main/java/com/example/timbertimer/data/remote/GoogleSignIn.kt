package com.example.timbertimer.data.remote

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google sign-in through Android's own credential sheet.
 *
 * The alternative — handing off to a browser and coming back through Supabase's
 * callback — works, but Google's prompt then names that callback
 * (`<project>.supabase.co`), because that is the site it is actually returning
 * the user to. No amount of branding changes it: the address is a property of
 * the redirect, not of the consent screen. Asking Google for an ID token *here*
 * removes the redirect entirely, so the sheet is the platform's own, it carries
 * this app's name and icon, and it never mentions Supabase.
 *
 * Everything that can go wrong — no Play Services, no Google account on the
 * device, an unregistered signing certificate — comes back as
 * [Result.Unavailable], and the caller falls back to the browser flow. That is
 * deliberate: this is a nicer way in, never the only way in.
 */
class GoogleSignIn(private val webClientId: String = SupabaseConfig.GOOGLE_WEB_CLIENT_ID) {

    val isConfigured: Boolean get() = webClientId.isNotBlank()

    /**
     * Set once the sheet has proved unusable on this device, so a second tap
     * goes straight to the browser instead of failing the same way again.
     * Deliberately not persisted: a missing Play Services update or an absent
     * Google account can be fixed, and the next launch should try afresh.
     */
    private var unusable = false

    /**
     * Shows the sheet and returns the ID token Google minted for this request.
     *
     * [context] must be an Activity: the sheet is drawn over it.
     */
    suspend fun requestIdToken(context: Context): Result {
        if (!isConfigured || unusable) return Result.Unavailable(null)

        // Google receives the hash and Supabase the original, which is how the
        // token is proved to belong to this sign-in rather than a replayed one.
        val rawNonce = randomNonce()
        val option = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(sha256Hex(rawNonce))
            .build()

        return try {
            val response = CredentialManager.create(context).getCredential(
                context = context,
                request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                unusable = true
                return Result.Unavailable(null)
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            Result.Token(idToken, rawNonce)
        } catch (cancelled: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (error: GetCredentialException) {
            // No account, no Play Services, or this build's signing certificate
            // is not registered against the Android OAuth client.
            unusable = true
            Result.Unavailable(error.message)
        } catch (error: Exception) {
            unusable = true
            Result.Unavailable(error.message)
        }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    sealed interface Result {
        /** [rawNonce] is what Supabase re-hashes to verify [idToken]. */
        data class Token(val idToken: String, val rawNonce: String) : Result

        /** The user dismissed the sheet — not an error, and not worth a fallback. */
        data object Cancelled : Result

        /** This device or this build cannot use the sheet; try the browser. */
        data class Unavailable(val reason: String?) : Result
    }
}
