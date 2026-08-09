package com.example.timbertimer

import com.example.timbertimer.data.remote.SupabaseException
import com.example.timbertimer.data.remote.isMissingSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Telling "this database has no such column" apart from "that request failed".
 *
 * The app drops `project_id` from its writes, and stops syncing projects at all,
 * once it believes the database predates the projects migration. Believing that
 * because of a lost packet is expensive and invisible: every later record would
 * be saved without its project, and a project made on another device would never
 * arrive, with nothing on screen to explain it. Only a server that actually said
 * so may set that belief.
 */
class SchemaFallbackTest {

    // ---------- the server really said the schema is missing ----------

    @Test
    fun `PostgREST's missing-column and missing-table codes count`() {
        assertTrue(
            SupabaseException(
                "Could not find the 'project_id' column of 'focus_sessions' in the schema cache",
                rejected = true,
                code = "PGRST204",
            ).isMissingSchema()
        )
        assertTrue(
            SupabaseException(
                "Could not find the table 'public.projects' in the schema cache",
                rejected = true,
                code = "PGRST205",
            ).isMissingSchema()
        )
    }

    @Test
    fun `Postgres's own undefined column and table codes count`() {
        assertTrue(
            SupabaseException("column does not exist", rejected = true, code = "42703")
                .isMissingSchema()
        )
        assertTrue(
            SupabaseException("relation does not exist", rejected = true, code = "42P01")
                .isMissingSchema()
        )
    }

    @Test
    fun `an unlabelled rejection is read from its wording`() {
        // Older PostgREST builds answer without a code.
        assertTrue(
            SupabaseException(
                "Could not find the 'project_id' column of 'focus_sessions' in the schema cache",
                rejected = true,
            ).isMissingSchema()
        )
        assertTrue(
            SupabaseException("relation \"public.projects\" does not exist", rejected = true)
                .isMissingSchema()
        )
    }

    // ---------- everything else must not ----------

    @Test
    fun `a transport failure never counts`() {
        assertFalse(IOException("broken pipe").isMissingSchema())
        assertFalse(UnknownHostException("no dns").isMissingSchema())
        assertFalse(SocketTimeoutException("timeout").isMissingSchema())
        // The tunnel case: the request never reached a server at all.
        assertFalse(SupabaseException("Network is unreachable").isMissingSchema())
    }

    @Test
    fun `a refusal that is not about the schema never counts`() {
        // An expired token, a policy refusal, a constraint violation — all
        // reasons to retry the *whole* request rather than a smaller one.
        assertFalse(SupabaseException("JWT expired", rejected = true, code = "PGRST301").isMissingSchema())
        assertFalse(
            SupabaseException(
                "new row violates row-level security policy",
                rejected = true,
                code = "42501",
            ).isMissingSchema()
        )
        assertFalse(
            SupabaseException("duplicate key value", rejected = true, code = "23505").isMissingSchema()
        )
        assertFalse(SupabaseException("Supabase request failed (HTTP 500).", rejected = true).isMissingSchema())
    }

    @Test
    fun `a code, when there is one, is the whole answer`() {
        // The wording of an unrelated failure must not trip the message
        // fallback: a code present means the code decides.
        assertFalse(
            SupabaseException(
                "Could not find the schema cache entry",
                rejected = true,
                code = "23505",
            ).isMissingSchema()
        )
    }
}
