package com.example.timbertimer.data.remote

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
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
 * Two credential options are tried in turn, because they fail in opposite
 * directions. [GetGoogleIdOption] shows the accounts already on the device,
 * which is one tap and the common case, but it has nothing to offer a phone
 * with no Google account signed in. [GetSignInWithGoogleOption] shows the full
 * branded flow, which can add one. Trying the first and falling through to the
 * second on [NoCredentialException] covers both without ever asking the user to
 * understand the difference.
 */
class GoogleSignIn(
    context: Context,
    private val webClientId: String = SupabaseConfig.GOOGLE_WEB_CLIENT_ID,
) {

    private val appContext = context.applicationContext

    val isConfigured: Boolean get() = webClientId.isNotBlank()

    /**
     * Shows the sheet and returns the ID token Google minted for this request.
     *
     * [context] must be an Activity: the sheet is drawn over it.
     */
    suspend fun requestIdToken(context: Context): Result {
        if (!isConfigured) return Result.Unavailable(null)

        // Google receives the hash and Supabase the original, which is how the
        // token is proved to belong to this sign-in rather than a replayed one.
        val rawNonce = randomNonce()
        val hashedNonce = sha256Hex(rawNonce)

        val onDevice = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            // false = offer every Google account on the device, not only the
            // ones that have signed into this app before. The website shares
            // this OAuth client, so filtering would usually leave a list of one
            // with no visible way past it.
            .setFilterByAuthorizedAccounts(false)
            // Skip the silent one-tap so the chooser always appears and
            // switching accounts stays possible.
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()

        val branded = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(hashedNonce)
            .build()

        return when (val first = attempt(context, onDevice, rawNonce)) {
            // No account on the device yet: the branded flow can add one.
            is Result.NoAccount -> attempt(context, branded, rawNonce)
            else -> first
        }
    }

    /**
     * Forgets which account was used, so the next sign-in shows the chooser
     * instead of silently reusing the one just signed out of.
     */
    suspend fun forgetAccount() {
        runCatching {
            CredentialManager.create(appContext).clearCredentialState(
                ClearCredentialStateRequest(ClearCredentialStateRequest.TYPE_CLEAR_CREDENTIAL_STATE)
            )
        }
    }

    private suspend fun attempt(
        context: Context,
        option: CredentialOption,
        rawNonce: String,
    ): Result = try {
        val response = CredentialManager.create(context).getCredential(
            context = context,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Result.Unavailable(null)
        } else {
            Result.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken, rawNonce)
        }
    } catch (cancelled: GetCredentialCancellationException) {
        Result.Cancelled
    } catch (empty: NoCredentialException) {
        Result.NoAccount
    } catch (missing: GetCredentialProviderConfigurationException) {
        // No Play Services, or a build of Android with no credential provider
        // at all. Nothing on this device can show the sheet.
        Result.Unavailable(missing.message)
    } catch (error: GetCredentialException) {
        // Most often this build's signing certificate is not registered against
        // an Android OAuth client for this package. Not latched: it is also what
        // a dropped connection looks like, and that fixes itself.
        Result.Unavailable(error.message)
    } catch (error: Exception) {
        Result.Unavailable(error.message)
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

        /** No Google account on the device, and the branded flow added none. */
        data object NoAccount : Result

        /** This device or this build cannot use the sheet; try the browser. */
        data class Unavailable(val reason: String?) : Result
    }
}
