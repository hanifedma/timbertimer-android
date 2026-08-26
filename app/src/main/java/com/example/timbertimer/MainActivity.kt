package com.example.timbertimer

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.timbertimer.data.local.LocaleStore
import com.example.timbertimer.data.local.ThemeMode
import com.example.timbertimer.data.remote.GoogleSignIn
import com.example.timbertimer.data.remote.SupabaseConfig
import com.example.timbertimer.ui.TimberApp
import com.example.timbertimer.ui.TimberViewModel
import com.example.timbertimer.ui.theme.TimberTimerTheme
import com.example.timbertimer.widget.TodayTodoWidget
import com.example.timbertimer.widget.TodoWidget

class MainActivity : ComponentActivity() {

    private val localeStore by lazy { LocaleStore(this) }

    /**
     * Applies the language override before any resource is resolved, so the
     * first frame is already in the chosen language rather than flashing the
     * device language and correcting itself.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleStore(newBase).wrap(newBase))
    }

    private val viewModel: TimberViewModel by viewModels {
        val container = (application as TimberApplication).container
        TimberViewModel.factory(
            container.repository,
            container.timerEngine,
            container.settings,
            container.auth,
            container.feedback,
            container.googleSignIn,
        )
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The activity may have been started by the OAuth redirect, or by a tap
        // on the home screen widget.
        handleDeepLink(intent)
        handleDestination(intent)
        askForNotificationsOnce()

        setContent {
            val themeMode by viewModel.settings.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // The status bar icons have to follow the *app's* appearance, not
            // the device's. enableEdgeToEdge()'s default reads the system dark
            // mode, so choosing Dark inside the app on a light phone would paint
            // dark icons onto a black bar and make the clock disappear.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle(darkTheme),
                    navigationBarStyle = systemBarStyle(darkTheme),
                )
            }

            TimberTimerTheme(darkTheme = darkTheme) {
                TimberApp(
                    viewModel = viewModel,
                    language = localeStore.languageTag,
                    onLanguageChange = { tag ->
                        if (tag != localeStore.languageTag) {
                            localeStore.languageTag = tag
                            // Resources are bound at attachBaseContext, so the
                            // activity has to be rebuilt for the new language
                            // to take effect.
                            recreate()
                        }
                    },
                    onSignIn = ::signInWithGoogle,
                    onSignInWithBrowser = ::signInWithBrowser,
                    onAddWidget = { requestPinWidget(TodoWidget::class.java) },
                    onAddTodayWidget = { requestPinWidget(TodayTodoWidget::class.java) },
                    onIgnoreBatteryOptimisation = ::requestIgnoreBatteryOptimisation,
                    onAllowDoNotDisturb = ::requestDoNotDisturbAccess,
                    onAllowFullScreen = ::requestFullScreenAlerts,
                )
            }
        }
    }

    /** Reached when the browser returns while this activity is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        handleDestination(intent)
    }

    /**
     * Coming back to the front is the moment to reconcile: a timer may have been
     * started, finished or edited elsewhere while this device was away, and the
     * shared rows are the only place that shows.
     */
    override fun onStart() {
        super.onStart()
        // Coming back may mean coming back from the Do Not Disturb access list,
        // and a channel's bypass can only be set as it is created — so this is
        // the moment to build the ones that now can be. Retiring the old channel
        // takes whatever was posted on it down with it, hence the repost.
        val container = (application as TimberApplication).container
        if (container.notifications.ensureChannels()) container.timerEngine.onWatchdogTick()
        viewModel.onResume()
    }

    override fun onStop() {
        viewModel.onPause()
        super.onStop()
    }

    /** The widget asks for a screen by name; the shell picks it up and clears it. */
    private fun handleDestination(intent: Intent?) {
        val destination = intent?.getStringExtra(EXTRA_DESTINATION) ?: return
        viewModel.requestDestination(destination)
        // Consumed, so a rotation or a return from the background does not send
        // the user back to the same tab against their will.
        intent.removeExtra(EXTRA_DESTINATION)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == SupabaseConfig.REDIRECT_SCHEME && data.host == SupabaseConfig.REDIRECT_HOST) {
            viewModel.handleAuthCallback(data)
        }
    }

    /**
     * Asked once, at first launch. A focus timer whose whole point is to run
     * while the phone is in a pocket needs its notification, so this is not
     * deferred to the first Start — being refused there would leave a session
     * already running with nothing to show for it.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Asks the launcher to place [widget]. Most people never find the
     * long-press-the-wallpaper gesture, and launchers that cannot do this say so
     * rather than leaving the button looking broken.
     */
    private fun requestPinWidget(widget: Class<*>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, widget)
        val requested = manager != null &&
            manager.isRequestPinAppWidgetSupported &&
            runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false)
        if (!requested) {
            Toast.makeText(this, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens Android's own exemption dialog.
     *
     * There is no way to grant this silently, and no way to make an app truly
     * immune: several manufacturers kill background work whatever this says. It
     * is the strongest thing the platform offers, so it is offered — and if the
     * app is already exempt, that is reported rather than showing a dialog that
     * would look like it did nothing.
     */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimisation() {
        val power = getSystemService(PowerManager::class.java)
        if (power != null && power.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.settings_battery_already, Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some builds hide the per-app dialog; the list screen still exists.
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    /**
     * Opens Android's Do Not Disturb access list.
     *
     * An app cannot let itself through Do Not Disturb — the platform ignores the
     * request on every channel until the user grants policy access here. Once
     * granted, [onStart] rebuilds the channels so the finished-session alert is
     * actually heard while the phone is silenced, which is exactly when a focus
     * session is most likely to be running.
     */
    private fun requestDoNotDisturbAccess() {
        val notifications = (application as TimberApplication).container.notifications
        if (notifications.canBypassDoNotDisturb()) {
            Toast.makeText(this, R.string.settings_dnd_already, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_dnd_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens the platform's full-screen-alert setting for this app.
     *
     * From Android 14 the permission is declared but not granted, unless the
     * store filed the app as an alarm or calling app — so the manifest entry is
     * only half of it, and this is the other half. Older versions grant it at
     * install and have no screen to send anyone to, which is what the "already"
     * branch covers.
     */
    private fun requestFullScreenAlerts() {
        val notifications = (application as TimberApplication).container.notifications
        if (notifications.canUseFullScreen()) {
            Toast.makeText(this, R.string.settings_full_screen_already, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(this, R.string.settings_full_screen_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_full_screen_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "com.example.timbertimer.DESTINATION"
        const val DESTINATION_TASKS = "tasks"

        /** Where "Start focusing" lands, so the invitation opens on the form. */
        const val DESTINATION_FOCUS = "focus"
    }

    /**
     * Signs in with Android's own credential sheet, and falls back to the
     * browser only when the device cannot show one.
     *
     * The sheet is the better door: it carries this app's name and icon and
     * never mentions the Supabase callback, which the browser flow has to travel
     * through and which Google therefore names on its prompt. But it needs Play
     * Services, a Google account on the device, and this build's signing
     * certificate registered against an Android OAuth client.
     *
     * When any of that is missing the browser still works, so it is still
     * offered — but the reason is said out loud first. Dropping to the browser
     * silently is what made a fixable setup problem look like the app's normal
     * behaviour.
     */
    private fun signInWithGoogle() {
        lifecycleScope.launch {
            val container = (application as TimberApplication).container
            when (val result = container.googleSignIn.requestIdToken(this@MainActivity)) {
                is GoogleSignIn.Result.Token -> {
                    if (viewModel.completeGoogleSignIn(result.idToken, result.rawNonce)) return@launch
                    // Google minted a token and Supabase would not take it —
                    // almost always because this client id is not on the Google
                    // provider's Authorized Client IDs list. The browser flow
                    // does not go through that check, so it is worth a try.
                    viewModel.notify(R.string.auth_native_rejected)
                    openAuthInBrowser()
                }

                // Dismissing the sheet is an answer, not a failure.
                GoogleSignIn.Result.Cancelled -> Unit

                // Nothing to choose from, and the branded flow added nothing.
                GoogleSignIn.Result.NoAccount -> viewModel.notify(R.string.auth_no_google_account)

                is GoogleSignIn.Result.Unavailable -> {
                    viewModel.notify(R.string.auth_sheet_unavailable)
                    openAuthInBrowser()
                }
            }
        }
    }

    /**
     * The browser route, asked for directly.
     *
     * Offered in Settings rather than only reached by a native failure the app
     * managed to classify, because Play Services has more ways to refuse than
     * can be enumerated — and this route depends on none of them.
     */
    private fun signInWithBrowser() {
        lifecycleScope.launch { openAuthInBrowser() }
    }

    private suspend fun openAuthInBrowser() {
        val url = viewModel.authorizeUrl()
        // Null means the verifier could not be stored, which would otherwise
        // fail silently — the one thing sign-in must never do again.
        if (url == null) {
            viewModel.notify(R.string.auth_verify_failed)
            return
        }
        openInCustomTab(url)
    }

    /**
     * The fallback: a Custom Tab rather than a WebView, because it shares the
     * device browser's session, so an account already signed in on the phone
     * needs no password, and the address bar proves the page really is Google's.
     */
    private fun openInCustomTab(url: String) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        try {
            intent.launchUrl(this, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            // No browser at all: fall back to whatever can view a link, and say
            // so plainly if nothing can.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.auth_no_browser, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Transparent bars, with icon contrast chosen for the appearance the app is
 * actually drawing — `dark` means a dark background, so light icons.
 */
private fun systemBarStyle(darkTheme: Boolean): SystemBarStyle =
    if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
