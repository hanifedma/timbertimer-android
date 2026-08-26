package com.example.timbertimer.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.math.pow

/**
 * Live updates from Supabase Realtime, so a timer started on the laptop appears
 * on the phone at once rather than at the next poll.
 *
 * Realtime speaks the Phoenix channel protocol over a websocket. Rather than
 * applying the row deltas it sends, a change of any kind simply triggers a full
 * reconcile through the normal sync path. That keeps one code path responsible
 * for merging — the delta payloads would otherwise need their own conflict
 * handling, and any disagreement between the two paths would be a bug that only
 * showed up when two devices were used at once.
 *
 * [connected] is what the caller uses to decide whether it still needs to poll.
 * A project whose tables are not published for replication will refuse the join,
 * and that has to degrade to polling rather than to silence.
 */
class RealtimeClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
) {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private var userId: String? = null
    private var tokenProvider: (suspend () -> String?)? = null
    private var onChange: (() -> Unit)? = null

    /** Set while a connection is wanted; cleared by [disconnect] so retries stop. */
    private var active = false
    private var attempt = 0
    private var ref = 0

    fun connect(
        userId: String,
        tokenProvider: suspend () -> String?,
        onChange: () -> Unit,
    ) {
        if (active && this.userId == userId) return
        disconnect()
        this.userId = userId
        this.tokenProvider = tokenProvider
        this.onChange = onChange
        active = true
        attempt = 0
        openSocket()
    }

    fun disconnect() {
        active = false
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.close(NORMAL_CLOSURE, null)
        webSocket = null
        userId = null
        _connected.value = false
    }

    private fun openSocket() {
        val user = userId ?: return
        scope.launch {
            val token = tokenProvider?.invoke()
            if (token == null) {
                // Signed out, or the refresh token was revoked. Stay quiet
                // instead of hammering a socket that cannot authenticate.
                _connected.value = false
                return@launch
            }

            val request = Request.Builder()
                .url(
                    "${SupabaseConfig.URL}/realtime/v1/websocket" +
                        "?apikey=${SupabaseConfig.ANON_KEY}&vsn=1.0.0"
                )
                .build()

            val socket = runCatching { httpClient.newWebSocket(request, Listener(user, token)) }
                .getOrNull() ?: return@launch

            if (active) {
                webSocket = socket
            } else {
                // Signed out while the token was being fetched. Without this the
                // orphaned socket would be assigned after disconnect() already
                // ran and would stay open with nothing tracking it.
                socket.close(NORMAL_CLOSURE, null)
            }
        }
    }

    private inner class Listener(
        private val user: String,
        private val token: String,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            webSocket.send(joinMessage(user, token))
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (message["event"]?.jsonPrimitive?.contentOrNullSafe()) {
                "phx_reply" -> {
                    // Heartbeats reply on the "phoenix" topic and say nothing
                    // about whether the subscription took. Counting those as
                    // success would report "live" even when the join had been
                    // refused — for instance when the tables are not published
                    // for replication — so only the channel's own reply counts.
                    if (message["topic"]?.jsonPrimitive?.contentOrNullSafe() != TOPIC) return
                    val status = message["payload"]?.jsonObject
                        ?.get("status")?.jsonPrimitive?.contentOrNullSafe()
                    val joined = status == "ok"
                    val wasConnected = _connected.value
                    _connected.value = joined
                    // Changes that happened while the socket was down were never
                    // delivered, so a subscription coming back up reconciles once
                    // rather than waiting for the next edit to notice.
                    if (joined && !wasConnected) onChange?.invoke()
                }

                // Any insert/update/delete on any of the five tables: reconcile.
                "postgres_changes" -> onChange?.invoke()

                "system" -> Unit
            }
        }

        /**
         * The server asking to hang up — a restart on Supabase's side, or the
         * JWT the socket was opened with finally ageing out.
         *
         * Answering matters more than it looks. OkHttp does not complete a
         * close the remote peer started; until this side calls close() too, the
         * socket sits half-shut and [onClosed] never runs. That left the one
         * state nothing recovers from: `connected` still true, so the caller
         * had stopped polling, and no failure ever arrived to trigger a
         * reconnect — live sync simply stopped, with the app still saying it
         * was live.
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            webSocket.close(NORMAL_CLOSURE, null)
            // A close the server chose is not a close this app asked for, so it
            // reconnects — disconnect() is what stops that, by clearing `active`.
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connected.value = false
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            if (code != NORMAL_CLOSURE) scheduleReconnect()
        }
    }

    /**
     * Phoenix drops a connection that stops sending heartbeats. The same tick
     * refreshes the access token, because Realtime closes the socket once the
     * JWT it was given expires.
     */
    private fun startHeartbeat(socket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (active) {
                delay(HEARTBEAT_MS)
                if (!active) break
                socket.send(heartbeatMessage())
                tokenProvider?.invoke()?.let { fresh -> socket.send(accessTokenMessage(fresh)) }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!active || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            // Exponential backoff, capped, so a server outage does not turn into
            // a reconnect storm or drain the battery.
            val backoff = min(MAX_BACKOFF_MS, (BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong())
            attempt++
            delay(backoff)
            if (active) openSocket()
        }
    }

    private fun nextRef(): String = (++ref).toString()

    private fun joinMessage(user: String, token: String): String {
        // Row level security already limits what this account can see, but the
        // filter also keeps the socket from carrying rows it would only discard.
        val filter = "user_id=eq.$user"
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("topic", TOPIC)
                put("event", "phx_join")
                putJsonObject("payload") {
                    putJsonObject("config") {
                        put("postgres_changes", buildJsonArray {
                            add(changeSpec(SupabaseConfig.SESSIONS_TABLE, filter))
                            add(changeSpec(SupabaseConfig.ACTIVE_TIMERS_TABLE, filter))
                            add(changeSpec(SupabaseConfig.ACTIVE_RESTS_TABLE, filter))
                            add(changeSpec(SupabaseConfig.NOTES_TABLE, filter))
                            add(changeSpec(SupabaseConfig.PROJECTS_TABLE, filter))
                        })
                    }
                    put("access_token", token)
                }
                put("ref", nextRef())
            },
        )
    }

    private fun changeSpec(table: String, filter: String): JsonObject = buildJsonObject {
        put("event", "*")
        put("schema", "public")
        put("table", table)
        put("filter", filter)
    }

    private fun heartbeatMessage(): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("topic", "phoenix")
            put("event", "heartbeat")
            putJsonObject("payload") {}
            put("ref", nextRef())
        },
    )

    private fun accessTokenMessage(token: String): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("topic", TOPIC)
            put("event", "access_token")
            putJsonObject("payload") { put("access_token", token) }
            put("ref", nextRef())
        },
    )

    private companion object {
        const val TOPIC = "realtime:timbertimer"
        const val NORMAL_CLOSURE = 1000
        const val HEARTBEAT_MS = 25_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}

/** `content` throws on JSON null; this returns null instead. */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()
