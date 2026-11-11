package app.pingvanta.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pingvanta.BuildConfig
import app.pingvanta.alert.AlarmLauncher
import app.pingvanta.alert.Channels
import app.pingvanta.alert.canPostNotifications
import app.pingvanta.background.LiveMonitorService
import app.pingvanta.background.MonitorScheduler
import app.pingvanta.data.ThemeMode
import app.pingvanta.ui.Format
import app.pingvanta.ui.SettingsViewModel
import app.pingvanta.ui.appViewModel
import app.pingvanta.ui.components.AppCard
import app.pingvanta.ui.theme.StatusColors
import java.time.LocalDate

private fun Context.launch(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun channelSettingsIntent(context: Context, channel: String): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channel)

private fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri == null) return "System default alarm"
    return runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }.getOrNull()
        ?: "Custom sound"
}

@Composable
private fun StatusLine(label: String, ok: Boolean, okText: String, badText: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (ok) okText else badText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) StatusColors.Up else StatusColors.Degraded,
        )
    }
}

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun SettingsScreen() {
    val vm = appViewModel(SettingsViewModel::class.java) { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val monitors by vm.monitors.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Bumped on resume so permission states are re-read after returning from system settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val notificationsOk = remember(refresh) { canPostNotifications(context) }
    val batteryExempt = remember(refresh) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName)
    }
    val exactAlarmOk = remember(refresh) { AlarmLauncher.canUseExactAlarms(context) }
    val liveRunning = remember(refresh, settings.liveMonitoring, monitors) { LiveMonitorService.running }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (!granted) context.launch(appNotificationSettingsIntent(context))
    }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            vm.setAlarmSound(picked?.toString())
        }
    }
    val exportMonitorsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) vm.exportMonitors(context.contentResolver, uri) }
    val importMonitorsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMonitors(context.contentResolver, uri)
    }
    val exportHistoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) vm.exportHistory(context.contentResolver, uri) }

    val activeMonitors = monitors.filter { !it.paused }
    val shortInterval = activeMonitors.count { it.intervalSeconds < MonitorScheduler.MIN_PERIODIC_MINUTES * 60 }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Appearance -----------------------------------------------------------------
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle("Theme")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.theme == mode,
                                onClick = { vm.setTheme(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // Defaults -------------------------------------------------------------------
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle("Defaults for new monitors")
                    Text("Check interval", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntervalOptions.forEach { seconds ->
                            FilterChip(
                                selected = settings.defaultIntervalSeconds == seconds,
                                onClick = { vm.setDefaultInterval(seconds) },
                                label = { Text(Format.interval(seconds)) },
                            )
                        }
                    }
                    Text("Request timeout", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeoutOptions.forEach { seconds ->
                            FilterChip(
                                selected = settings.defaultTimeoutSeconds == seconds,
                                onClick = { vm.setDefaultTimeout(seconds) },
                                label = { Text("$seconds s") },
                            )
                        }
                    }
                    SwitchRow("Alarm when a monitor goes down", settings.defaultAlarmOnDown, vm::setDefaultAlarm)
                    SwitchRow("Repeat alarm until acknowledged", settings.defaultRepeatAlarm, vm::setDefaultRepeat)
                }
            }

            // Alerts ---------------------------------------------------------------------
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle("Notifications and alarm")
                    StatusLine("Notification permission", notificationsOk, "Allowed", "Blocked")
                    if (!notificationsOk) {
                        Note(
                            "Pingvanta cannot alert you until notifications are allowed.",
                            color = StatusColors.Degraded,
                        )
                        OutlinedButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    context.launch(appNotificationSettingsIntent(context))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Allow notifications") }
                    }
                    Note(
                        "Sound, vibration and priority of the \"online\" and \"offline\" notifications are " +
                            "controlled per channel by Android. Open each channel to change them.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { context.launch(channelSettingsIntent(context, Channels.UP)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Online sound") }
                        OutlinedButton(
                            onClick = { context.launch(channelSettingsIntent(context, Channels.DOWN)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Offline sound") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.testNotification(true) }, modifier = Modifier.weight(1f)) {
                            Text("Test online")
                        }
                        OutlinedButton(onClick = { vm.testNotification(false) }, modifier = Modifier.weight(1f)) {
                            Text("Test offline")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Alarm sound", style = MaterialTheme.typography.bodyLarge)
                    Note(ringtoneTitle(context, settings.alarmSoundUri))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    .putExtra(
                                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                        settings.alarmSoundUri?.let { Uri.parse(it) }
                                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                                    )
                                try {
                                    ringtoneLauncher.launch(intent)
                                } catch (e: ActivityNotFoundException) {
                                    context.launch(Intent(Settings.ACTION_SOUND_SETTINGS))
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Choose sound") }
                        OutlinedButton(onClick = { vm.testAlarm(context) }, modifier = Modifier.weight(1f)) {
                            Text("Test alarm")
                        }
                    }
                    SwitchRow("Vibrate with the alarm", settings.vibrate, vm::setVibrate)
                    Note(
                        "Alarm volume: alarms play on the phone's Alarm volume, not the ring or media volume, " +
                            "and they sound even when the phone is on silent. If the test alarm is too quiet, " +
                            "raise the Alarm slider in sound settings. Do Not Disturb can still mute alarms " +
                            "if it is set to block them.",
                    )
                    OutlinedButton(
                        onClick = { context.launch(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open sound settings") }
                    StatusLine("Background alarm launch", exactAlarmOk, "Ready", "Limited")
                    if (!exactAlarmOk) {
                        Note(
                            "Exact alarms are not allowed for this app, so an alarm raised while the app is " +
                                "closed falls back to an alarm-style notification.",
                            color = StatusColors.Degraded,
                        )
                    }
                }
            }

            // Background -----------------------------------------------------------------
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle("Background monitoring")
                    StatusLine(
                        "Scheduled monitors",
                        activeMonitors.isNotEmpty(),
                        "${activeMonitors.size} active",
                        "None",
                    )
                    Note(
                        "Every monitor has a persistent WorkManager job that survives app restarts and reboots. " +
                            "Android does not let these jobs run more often than every 15 minutes, and it may " +
                            "delay them further in battery saver or deep sleep.",
                    )
                    SwitchRow(
                        "Live monitoring",
                        settings.liveMonitoring,
                        vm::setLiveMonitoring,
                        subtitle = "Keeps a foreground service running for monitors checked more often than " +
                            "every 15 minutes. Shows an ongoing notification and uses more battery.",
                    )
                    StatusLine(
                        "Live service",
                        liveRunning,
                        "Running",
                        if (settings.liveMonitoring && shortInterval > 0) "Not running" else "Not needed",
                    )
                    if (shortInterval > 0 && !settings.liveMonitoring) {
                        Note(
                            "$shortInterval monitor${if (shortInterval == 1) " has" else "s have"} an interval " +
                                "under 15 minutes and will be checked about every 15 minutes instead.",
                            color = StatusColors.Degraded,
                        )
                    }
                    StatusLine("Battery optimisation", batteryExempt, "Unrestricted", "Restricted")
                    if (!batteryExempt) {
                        Note(
                            "With battery optimisation on, Android can pause checks and network access while " +
                                "the phone sleeps. Allow unrestricted battery use for the most reliable alerts.",
                        )
                        OutlinedButton(
                            onClick = {
                                context.launch(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Allow unrestricted battery use") }
                    }
                }
            }

            // Data -----------------------------------------------------------------------
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle("Data")
                    OutlinedButton(
                        onClick = { exportHistoryLauncher.launch("pingvanta-history-${LocalDate.now()}.csv") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export monitoring history (CSV)") }
                    OutlinedButton(
                        onClick = { exportMonitorsLauncher.launch("pingvanta-monitors-${LocalDate.now()}.json") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export monitor configurations (JSON)") }
                    OutlinedButton(
                        onClick = { importMonitorsLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import monitor configurations") }
                    Note("History keeps individual checks for 32 days. Status changes are kept until the monitor is deleted.")
                }
            }

            // About ----------------------------------------------------------------------
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardTitle("About")
                    BrandTitle()
                    Note("Version ${BuildConfig.VERSION_NAME}")
                    Note(
                        "A personal uptime monitor. All checks run from this phone and all data stays on it; " +
                            "nothing is sent anywhere except the requests to the addresses you monitor.",
                    )
                }
            }
        }
    }
}
