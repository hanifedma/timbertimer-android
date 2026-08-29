package com.example.timbertimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.data.local.LocaleStore
import com.example.timbertimer.data.local.RestAlertStyle
import com.example.timbertimer.data.local.ThemeMode
import com.example.timbertimer.data.model.DataMode
import com.example.timbertimer.data.remote.Session
import com.example.timbertimer.ui.components.Panel
import com.example.timbertimer.ui.components.SegmentedRow

/**
 * Account, appearance, sound and the destructive button.
 *
 * Sign-in is the same Google account the website uses, against the same Supabase
 * project, so records cross between them without an import step.
 */
@Composable
fun SettingsScreen(
    session: Session?,
    dataMode: DataMode,
    themeMode: ThemeMode,
    language: String,
    soundEnabled: Boolean,
    volume: Float,
    vibrate: Boolean,
    idleReminder: Boolean,
    backgroundSync: Boolean,
    restTally: Boolean,
    restAlert: RestAlertStyle,
    onThemeChange: (ThemeMode) -> Unit,
    onLanguageChange: (String) -> Unit,
    onSoundToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeSettled: () -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onIdleReminderChange: (Boolean) -> Unit,
    onBackgroundSyncChange: (Boolean) -> Unit,
    onRestTallyChange: (Boolean) -> Unit,
    onRestAlertChange: (RestAlertStyle) -> Unit,
    onIgnoreBatteryOptimisation: () -> Unit,
    onAllowDoNotDisturb: () -> Unit,
    onAllowFullScreen: () -> Unit,
    onAddWidget: () -> Unit,
    onAddTodayWidget: () -> Unit,
    onAddProjectsWidget: () -> Unit,
    onManageProjects: () -> Unit,
    projectsSyncBlocked: Boolean,
    onSignIn: () -> Unit,
    onSignInWithBrowser: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(
            kicker = stringResource(R.string.account_kicker),
            title = stringResource(R.string.account_title),
        ) {
            Text(
                text = session?.label ?: stringResource(
                    if (dataMode == DataMode.CLOUD) R.string.account_status_cloud
                    else R.string.account_status_local
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (session != null) R.string.brand_cloud_garden else R.string.brand_local_garden
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            if (session == null) {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_continue_google))
                }
                // The way out when the account sheet will not complete — a
                // mis-registered signing certificate, an account needing
                // re-authentication. The browser route depends on none of
                // that, so it is always offered rather than only reached by
                // a failure the app managed to detect. The website carries
                // the same escape hatch, in the same words.
                TextButton(
                    onClick = onSignInWithBrowser,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.account_other_way),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_sign_out))
                }
            }
        }

        Panel(kicker = stringResource(R.string.project_kicker)) {
            OutlinedButton(onClick = onManageProjects, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.project_manage), textAlign = TextAlign.Center)
            }
            Text(
                text = stringResource(R.string.project_manage_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Failing silently here is what makes a project made on the laptop
            // look like it simply never arrived.
            if (projectsSyncBlocked) {
                Text(
                    text = stringResource(R.string.project_sync_blocked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Panel(kicker = stringResource(R.string.settings_appearance)) {
            SegmentedRow(
                options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                selected = themeMode,
                label = {
                    stringResource(
                        when (it) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        }
                    )
                },
                onSelect = onThemeChange,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            SegmentedRow(
                options = listOf(LocaleStore.SYSTEM, LocaleStore.ENGLISH, LocaleStore.KOREAN),
                selected = language,
                label = {
                    when (it) {
                        LocaleStore.ENGLISH -> "English"
                        LocaleStore.KOREAN -> "한국어"
                        else -> stringResource(R.string.settings_language_system)
                    }
                },
                onSelect = onLanguageChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Panel(kicker = stringResource(R.string.settings_sound)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(if (soundEnabled) R.string.sound_on else R.string.sound_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = soundEnabled, onCheckedChange = { onSoundToggle() })
            }

            if (soundEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_volume),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        text = "  ${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    // Auditioned on release rather than on every pixel of drag,
                    // which would stack a dozen overlapping chimes.
                    onValueChangeFinished = onVolumeSettled,
                    valueRange = 0f..2f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_vibrate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = vibrate, onCheckedChange = onVibrateChange)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_idle_reminder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = idleReminder, onCheckedChange = onIdleReminderChange)
            }
        }

        // Its own panel rather than a fourth row in the one above, because it
        // is not governed by anything there. The switches above are about the
        // chime that ends a focus session; this is about an alarm, and the two
        // answer to different intentions.
        Panel(kicker = stringResource(R.string.settings_rest_alert)) {
            SegmentedRow(
                options = listOf(
                    RestAlertStyle.BOTH,
                    RestAlertStyle.SOUND,
                    RestAlertStyle.VIBRATE,
                    RestAlertStyle.SILENT,
                ),
                selected = restAlert,
                label = {
                    stringResource(
                        when (it) {
                            RestAlertStyle.BOTH -> R.string.settings_rest_alert_both
                            RestAlertStyle.SOUND -> R.string.settings_rest_alert_sound
                            RestAlertStyle.VIBRATE -> R.string.settings_rest_alert_vibrate
                            RestAlertStyle.SILENT -> R.string.settings_rest_alert_silent
                        }
                    )
                },
                onSelect = onRestAlertChange,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.settings_rest_alert_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = onAllowFullScreen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_full_screen), textAlign = TextAlign.Center)
            }
            Text(
                text = stringResource(R.string.settings_full_screen_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Panel(kicker = stringResource(R.string.settings_background_sync)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_background_sync),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = backgroundSync, onCheckedChange = onBackgroundSyncChange)
            }
            Text(
                text = stringResource(R.string.settings_background_sync_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = onIgnoreBatteryOptimisation, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_battery), textAlign = TextAlign.Center)
            }
            Text(
                text = stringResource(R.string.settings_battery_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = onAllowDoNotDisturb, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_dnd), textAlign = TextAlign.Center)
            }
            Text(
                text = stringResource(R.string.settings_dnd_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Panel(kicker = stringResource(R.string.widget_name)) {
            OutlinedButton(onClick = onAddWidget, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.widget_pin), textAlign = TextAlign.Center)
            }
            Text(
                text = stringResource(R.string.widget_description),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = onAddTodayWidget, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.widget_today_pin), textAlign = TextAlign.Center)
            }
            Text(
                text = stringResource(R.string.widget_today_description),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = onAddProjectsWidget, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_add_projects_widget),
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = stringResource(R.string.widget_projects_description),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Panel(kicker = stringResource(R.string.notif_channel_rest_tally)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_rest_tally),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = restTally, onCheckedChange = onRestTallyChange)
            }
            // Says out loud that the notification is meant to be hard to get rid
            // of, and that this switch is the way. Someone who finds an
            // unswipeable notification and no explanation assumes a bug.
            Text(
                text = stringResource(R.string.settings_rest_tally_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel(kicker = stringResource(R.string.settings_data)) {
            Button(
                onClick = onDeleteAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.account_delete_all))
            }
            Text(
                text = stringResource(
                    if (dataMode == DataMode.CLOUD) R.string.confirm_delete_all_cloud
                    else R.string.confirm_delete_all_local
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
