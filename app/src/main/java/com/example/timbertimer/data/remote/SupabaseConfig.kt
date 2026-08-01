package com.example.timbertimer.data.remote

/**
 * Points the app at the same Supabase project the TimberTimer web app uses, so
 * a session planted on the phone shows up on the website and the other way
 * round. The values are copied from the web repo's `src/supabase-config.js`.
 *
 * The publishable ("anon") key is meant to be shipped in clients — it is already
 * public in that repo. It grants nothing on its own: every table has row level
 * security allowing access only to rows where `user_id = auth.uid()`. Never put
 * the service-role key here; that one does bypass RLS.
 */
object SupabaseConfig {

    const val URL = "https://frfnthlokksynreadten.supabase.co"

    const val ANON_KEY = "sb_publishable_ps4gSvjGkpi7lAX4J3EGJA_HZ2CmOhG"

    /**
     * Deep link Supabase sends the browser back to once Google sign-in finishes.
     * This exact value must also be listed under Authentication → URL
     * Configuration → Redirect URLs in the Supabase dashboard, otherwise the
     * provider refuses the redirect and bounces to the website instead.
     */
    const val REDIRECT_SCHEME = "timbertimer"
    const val REDIRECT_HOST = "auth-callback"
    const val REDIRECT_URL = "$REDIRECT_SCHEME://$REDIRECT_HOST"

    const val REST_PATH = "$URL/rest/v1"
    const val AUTH_PATH = "$URL/auth/v1"

    const val SESSIONS_TABLE = "focus_sessions"
    const val ACTIVE_TIMERS_TABLE = "active_focus_timers"
    const val ACTIVE_RESTS_TABLE = "active_rest_timers"
    const val NOTES_TABLE = "notes"
}
