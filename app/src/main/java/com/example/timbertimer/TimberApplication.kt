package com.example.timbertimer

import android.app.Application
import android.content.Context
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.TimberRepository
import com.example.timbertimer.data.local.LocalStore
import com.example.timbertimer.data.local.SettingsStore
import com.example.timbertimer.data.local.WidgetNote
import com.example.timbertimer.data.remote.GoogleSignIn
import com.example.timbertimer.data.remote.RealtimeClient
import com.example.timbertimer.data.remote.SupabaseApi
import com.example.timbertimer.data.remote.SupabaseAuth
import com.example.timbertimer.timer.RestAlarm
import com.example.timbertimer.timer.TimerAlarms
import com.example.timbertimer.timer.TimerEngine
import com.example.timbertimer.timer.TimerFeedback
import com.example.timbertimer.timer.TimerNotifications
import com.example.timbertimer.widget.TodayTodoWidget
import com.example.timbertimer.widget.TodoWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class TimberApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannels()
        container.start()
    }
}

/**
 * Hand-rolled dependency graph. The app has one of each of these and no runtime
 * variation to configure, so a container is all the wiring it needs.
 *
 * The scope is deliberately application-wide rather than per-screen: a running
 * session has to finish and be recorded even with every screen gone, which is
 * the whole point of the foreground service.
 */
class AppContainer(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpClient = SupabaseAuth.defaultHttpClient()

    val auth = SupabaseAuth(context, httpClient, json)

    val googleSignIn = GoogleSignIn(context)

    private val api = SupabaseApi(httpClient, json)

    private val local = LocalStore(context)

    val settings = SettingsStore(context)

    val realtime = RealtimeClient(httpClient, json, scope)

    val repository = TimberRepository(local, settings, auth, api, scope)

    val notifications = TimerNotifications(context)

    val feedback = TimerFeedback(context, settings)

    private val alarms = TimerAlarms(context)

    val restAlarm = RestAlarm(
        context = context,
        settings = settings,
        feedback = feedback,
        notifications = notifications,
        scope = scope,
    )

    val timerEngine = TimerEngine(
        context = context,
        local = local,
        settings = settings,
        repository = repository,
        feedback = feedback,
        notifications = notifications,
        alarms = alarms,
        restAlarm = restAlarm,
        liveSync = realtime.connected,
        scope = scope,
    )

    /**
     * Connects the pieces that only make sense once everything exists: the live
     * socket follows whoever is signed in, and the home screen widget follows
     * the to-do list.
     */
    fun start() {
        scope.launch {
            repository.session.collect { session ->
                if (session == null) {
                    realtime.disconnect()
                } else {
                    realtime.connect(
                        userId = session.userId,
                        tokenProvider = { auth.validAccessToken() },
                        onChange = {
                            repository.onRemoteChange()
                            timerEngine.onRemoteChange()
                        },
                    )
                }
            }
        }

        // The Today widget has to notice more than a note changing — it also has
        // to notice the calendar day changing, with nothing about the notes
        // themselves any different, which is what makes it blank again on a new
        // day. Combining with the engine's own clock (already ticking whenever
        // anything is running, the app is foregrounded, or background sync is
        // on) and keying distinctUntilChanged on the date rather than the
        // instant is what catches that without redrawing on every tick.
        scope.launch {
            combine(repository.notes, timerEngine.now) { notes, now ->
                notes to Time.localDateKey(now)
            }
                .distinctUntilChanged()
                .collect { (notes, _) ->
                    local.writeWidgetNotes(
                        notes.map {
                            WidgetNote(
                                id = it.id,
                                text = it.text,
                                done = it.done,
                                list = it.list.wire,
                                forDate = it.forDate,
                            )
                        }
                    )
                    TodoWidget.refresh(context)
                    TodayTodoWidget.refresh(context)
                }
        }
    }
}
