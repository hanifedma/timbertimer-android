package com.example.timbertimer.data.remote

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
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
 * The sheet is [GetSignInWithGoogleOption], the full branded flow, and only
 * that one — see [requestIdToken] for why the compact one-tap option was
 * dropped rather than kept as a first choice.
 *
 * None of this works until the build is registered: Google issues an ID token
 * only to an app whose package name *and* signing certificate fingerprint are
 * on an Android OAuth client in the same project as [webClientId]. That
 * registration cannot be done from code, which is why every failure here ends
 * somewhere the user can still get in.
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

        // One option, the branded "Sign in with Google" flow.
        //
        // This used to lead with GetGoogleIdOption — the compact one-tap sheet —
        // and keep the branded flow as a fallback for a device with no Google
        // account. That fallback was unreachable, and the reason is worth
        // recording: GetGoogleIdOption's refusals do not arrive as failures.
        // "[16] Account reauth failed." is delivered as a *cancellation*, which
        // the chain read as the user changing their mind, so it stopped there
        // and the flow that would have worked was never tried.
        //
        // Leading with the branded flow removes the trap rather than patching
        // it. It is also the strictly more capable of the two: it re-authorises
        // an account that needs it instead of giving up, and it can add one that
        // is not on the device yet — which is what the fallback existed for. The
        // one thing lost is a slightly shorter sheet for a returning user, and
        // that is a fair trade for a door that opens.
        val branded = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(hashedNonce)
            .build()

        return attempt(context, branded, rawNonce)
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
        // Two very different things arrive here, and telling them apart is the
        // whole point of this branch.
        //
        // One is a real dismissal: the user tapped outside the sheet, which is
        // an answer and deserves silence. The other is Play Services *refusing*
        // the request — a mis-registered signing certificate, an account that
        // needs re-authenticating — which it reports by finishing its own
        // activity as "cancelled", with the reason only in the message
        // ("[16] Account reauth failed.", "[28444] Developer console is not set
        // up correctly.", …).
        //
        // Treating the second as the first is what left sign-in silently doing
        // nothing at all: no message, no fallback, straight back to the screen
        // it started from. A refusal is a failure, so it is reported as one and
        // takes the browser route, which does not depend on any of this.
        if (cancelled.isProviderFailure()) {
            Log.w(TAG, "Play Services refused the credential request", cancelled)
            Result.Unavailable(cancelled.message, persistent = true)
        } else {
            Log.i(TAG, "Credential sheet dismissed by the user")
            Result.Cancelled
        }
    } catch (empty: NoCredentialException) {
        Log.i(TAG, "No Google credential available on this device", empty)
        Result.NoAccount
    } catch (missing: GetCredentialProviderConfigurationException) {
        // No Play Services, or a build of Android with no credential provider
        // at all. Nothing on this device can show the sheet.
        Log.w(TAG, "No credential provider on this device", missing)
        Result.Unavailable(missing.message, persistent = true)
    } catch (error: GetCredentialException) {
        // Most often this build's signing certificate is not registered against
        // an Android OAuth client for this package. Not latched: it is also what
        // a dropped connection looks like, and that fixes itself.
        Log.w(TAG, "Credential request failed: ${error.type}", error)
        Result.Unavailable(error.message)
    } catch (error: Exception) {
        Log.w(TAG, "Credential request failed", error)
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

    /**
     * True when a "cancellation" is really Play Services refusing the request.
     *
     * Its refusals carry their status code in the message — `[16] Account
     * reauth failed.`, `[28444] Developer console is not set up correctly.` —
     * while a dismissal by the user carries no code at all. That bracketed
     * number is the only thing separating the two by the time it reaches here.
     */
    private fun GetCredentialCancellationException.isProviderFailure(): Boolean =
        PROVIDER_STATUS_CODE.containsMatchIn(message.orEmpty())

    private companion object {
        const val TAG = "GoogleSignIn"

        /** A leading `[16]`-style Play Services status code. */
        val PROVIDER_STATUS_CODE = Regex("""\[\d+]""")
    }

    sealed interface Result {
        /** [rawNonce] is what Supabase re-hashes to verify [idToken]. */
        data class Token(val idToken: String, val rawNonce: String) : Result

        /** The user dismissed the sheet — not an error, and not worth a fallback. */
        data object Cancelled : Result

        /** No Google account on the device, and the branded flow added none. */
        data object NoAccount : Result

        /**
         * This device or this build cannot use the sheet; try the browser.
         *
         * [persistent] separates a refusal that is a fact about this
         * installation — an unregistered signing certificate, no credential
         * provider on the device — from one that is only a fact about this
         * moment, like a request that timed out. Only the first kind is worth
         * remembering; latching the second would take the sheet away from a
         * device that was merely offline for a second.
         */
        data class Unavailable(val reason: String?, val persistent: Boolean = false) : Result
    }
}
