package com.example.timbertimer.timer

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.timbertimer.MainActivity
import com.example.timbertimer.R
import com.example.timbertimer.TimberApplication
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.local.LocaleStore
import com.example.timbertimer.data.local.ThemeMode
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.ui.components.TreeArt
import com.example.timbertimer.ui.components.rememberTreePalette
import com.example.timbertimer.ui.theme.TimberTimerTheme
import kotlinx.coroutines.launch

/**
 * The screen a finished rest puts in front of you.
 *
 * Reached two ways: as the full-screen intent on the alarm notification — which
 * is what wakes a dark, locked phone and shows this over the lock screen — and
 * by tapping the notification body. Both land here, so there is one place where
 * the alarm can be answered.
 *
 * It is a separate activity rather than a dialog inside [MainActivity] for one
 * reason: a full-screen intent has to be able to launch into a locked device
 * without unlocking it, and everything in the main app is the user's data. This
 * screen shows nothing but the fact that a rest ended and how long it ran, so
 * it is safe to display above the keyguard — no records, no task names, no
 * account. Dismissing needs no unlock; *starting a focus session* does, and
 * that is what [MainActivity] is deep-linked for.
 */
class RestAlarmActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleStore(newBase).wrap(newBase))
    }

    private val container get() = (application as TimberApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showOverLockScreen()

        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle()
            val ringing by container.restAlarm.ringing.collectAsStateWithLifecycle()
            val book by container.repository.projects.collectAsStateWithLifecycle()

            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Answered from somewhere else — the notification action, another
            // device, or the app itself — so there is nothing left to ask.
            LaunchedEffect(ringing) {
                if (ringing == null) finish()
            }

            TimberTimerTheme(darkTheme = darkTheme) {
                RestAlarmScreen(
                    restProject = book[Projects.REST_ID],
                    durationMinutes = ringing?.durationMinutes ?: 0,
                    loud = ringing?.loud ?: false,
                    onDismiss = ::dismiss,
                    onSilence = { container.restAlarm.silence() },
                    onExtend = ::extend,
                    onFocus = ::openFocus,
                )
            }
        }
    }

    /**
     * Turns the screen on and shows this above the keyguard.
     *
     * The two flag sets do the same job on either side of API 27; the newer
     * calls are the ones that still work when the activity is re-shown rather
     * than created, which is what happens when a second alarm arrives while
     * this one is up.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // Keeps the display awake while the sheet is up, so an alarm answered
        // by walking over to the phone is not answered by a black screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Back is Dismiss.
     *
     * Not "leave it ringing": the user has demonstrably seen the alarm to press
     * anything at all, and a phone that keeps shouting after being acknowledged
     * is the behaviour that gets alarms turned off for good.
     */
    @Deprecated("Back handling for a single-purpose alarm screen")
    override fun onBackPressed() {
        dismiss()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun dismiss() {
        container.restAlarm.dismiss()
        finish()
    }

    /**
     * Another five minutes — a fresh rest, not a revival of the one that ended.
     * See [TimerEngine.restAgainFromAlarm] for why it cannot be an extension.
     *
     * Silenced here rather than left to the coroutine, so the noise stops on
     * the press instead of whenever the work is scheduled.
     */
    private fun extend() {
        container.restAlarm.dismiss()
        lifecycleScope.launch { container.timerEngine.restAgainFromAlarm() }
        finish()
    }

    private fun openFocus() {
        container.restAlarm.dismiss()
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .setFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_FOCUS)
        )
        finish()
    }
}

@Composable
private fun RestAlarmScreen(
    restProject: Project,
    durationMinutes: Int,
    loud: Boolean,
    onDismiss: () -> Unit,
    onSilence: () -> Unit,
    onExtend: () -> Unit,
    onFocus: () -> Unit,
) {
    // Landscape on a phone leaves barely enough height for the buttons, so the
    // tree is the thing that gives way rather than the way out.
    val compact = LocalConfiguration.current.screenHeightDp < 480

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!compact) {
                TreeArt(
                    species = restProject.species,
                    palette = rememberTreePalette(restProject),
                    modifier = Modifier.size(120.dp),
                )
                Spacer(Modifier.height(20.dp))
            }

            Text(
                text = stringResource(R.string.rest_alarm_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(
                    R.string.rest_alarm_body,
                    Time.formatMinutes(
                        durationMinutes,
                        stringResource(R.string.unit_m),
                        stringResource(R.string.unit_h),
                    ),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            // Capped so the primary action does not become a metre-wide bar on
            // a tablet, and centred either way.
            val actions = Modifier.fillMaxWidth().widthIn(max = 420.dp)

            Button(onClick = onDismiss, modifier = actions) {
                Text(stringResource(R.string.rest_alarm_dismiss))
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(onClick = onExtend, modifier = actions) {
                Text(stringResource(R.string.rest_alarm_extend))
            }

            Spacer(Modifier.height(10.dp))

            TextButton(onClick = onFocus, modifier = actions) {
                Text(stringResource(R.string.rest_alarm_focus))
            }

            // Offered only while there is something to silence. Once the ring
            // has run its course this would be a button that does nothing.
            if (loud) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onSilence, modifier = actions) {
                    Text(
                        text = stringResource(R.string.rest_alarm_silence),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
