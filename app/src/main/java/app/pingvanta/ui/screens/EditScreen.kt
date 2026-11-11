package app.pingvanta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pingvanta.background.MonitorScheduler
import app.pingvanta.data.MonitorType
import app.pingvanta.ui.EditForm
import app.pingvanta.ui.EditViewModel
import app.pingvanta.ui.Format
import app.pingvanta.ui.appViewModel
import app.pingvanta.ui.components.AppCard
import app.pingvanta.ui.theme.StatusColors

val IntervalOptions = listOf(60, 120, 300, 600, 900, 1800, 3600)
val TimeoutOptions = listOf(5, 10, 15, 30, 60)
private val CodePresets = listOf("200-299", "200-399", "200-399,401,403")

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun EditScreen(monitorId: Long?, onDone: () -> Unit) {
    val vm = appViewModel(EditViewModel::class.java, key = "edit-${monitorId ?: "new"}") { EditViewModel(it, monitorId) }
    val form by vm.form.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (monitorId == null) "Add monitor" else "Edit monitor") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(
                        onClick = { vm.save(onDone) },
                        enabled = form.loaded && !form.saving,
                        modifier = Modifier.testTag("save"),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!form.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TargetCard(form, vm)
                ScheduleCard(form, vm, settings.liveMonitoring)
                AlertsCard(form, vm)
                Button(
                    onClick = { vm.save(onDone) },
                    enabled = !form.saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(if (monitorId == null) "Start monitoring" else "Save changes")
                }
            }
        }
    }
}

@Composable
private fun TargetCard(form: EditForm, vm: EditViewModel) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("What to monitor")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonitorType.entries.forEach { type ->
                    FilterChip(
                        selected = form.type == type,
                        onClick = { vm.setType(type) },
                        label = { Text(Format.typeLabel(type)) },
                    )
                }
            }
            Text(
                when (form.type) {
                    MonitorType.HTTP -> "Sends a real GET request and compares the response code with the codes you expect."
                    MonitorType.TCP -> "Opens a TCP connection to the host and port. Up means the connection was accepted."
                    MonitorType.PING -> "Sends an ICMP echo using the system ping tool. Hosts or networks that block " +
                        "ICMP will show as down, and a few devices do not allow apps to ping at all."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.target,
                onValueChange = { value -> vm.edit { it.copy(target = value) } },
                modifier = Modifier.fillMaxWidth().testTag("field-target"),
                label = { Text(if (form.type == MonitorType.HTTP) "URL" else "Host name or IP address") },
                placeholder = {
                    Text(if (form.type == MonitorType.HTTP) "https://www.google.com" else "example.com")
                },
                isError = form.targetError != null,
                supportingText = if (form.targetError != null) {
                    { Text(form.targetError.orEmpty()) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            if (form.type == MonitorType.TCP) {
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { value -> vm.edit { it.copy(port = value.filter(Char::isDigit).take(5)) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") },
                    isError = form.portError != null,
                    supportingText = if (form.portError != null) {
                        { Text(form.portError.orEmpty()) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
            }
            OutlinedTextField(
                value = form.name,
                onValueChange = { value -> vm.edit { it.copy(name = value) } },
                modifier = Modifier.fillMaxWidth().testTag("field-name"),
                label = { Text("Name (optional)") },
                placeholder = { Text("Google") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            if (form.type == MonitorType.HTTP) {
                OutlinedTextField(
                    value = form.expectedCodes,
                    onValueChange = { value -> vm.edit { it.copy(expectedCodes = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Expected HTTP status codes") },
                    isError = form.codesError != null,
                    supportingText = {
                        Text(form.codesError ?: "Any other code, or no response, counts as down. Example: 200-299,301")
                    },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodePresets.forEach { preset ->
                        FilterChip(
                            selected = form.expectedCodes.trim() == preset,
                            onClick = { vm.edit { it.copy(expectedCodes = preset) } },
                            label = { Text(preset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(form: EditForm, vm: EditViewModel, liveMonitoring: Boolean) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Check every")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntervalOptions.forEach { seconds ->
                    FilterChip(
                        selected = form.intervalSeconds == seconds,
                        onClick = { vm.edit { it.copy(intervalSeconds = seconds) } },
                        label = { Text(Format.interval(seconds)) },
                    )
                }
            }
            if (form.intervalSeconds < MonitorScheduler.MIN_PERIODIC_MINUTES * 60) {
                Text(
                    if (liveMonitoring) {
                        "Android's background scheduler cannot run more often than every 15 minutes, so this " +
                            "interval uses the live monitoring service, which shows an ongoing notification. " +
                            "Timing is approximate and can slow down while the phone is in deep sleep."
                    } else {
                        "Live monitoring is switched off in Settings, so this monitor will be checked about " +
                            "every 15 minutes while the app is closed. Turn it on to use this interval."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (liveMonitoring) MaterialTheme.colorScheme.onSurfaceVariant else StatusColors.Degraded,
                )
            } else {
                Text(
                    "Android may delay background checks, especially in battery saver or deep sleep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            CardTitle("Request timeout")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeoutOptions.forEach { seconds ->
                    FilterChip(
                        selected = form.timeoutSeconds == seconds,
                        onClick = { vm.edit { it.copy(timeoutSeconds = seconds) } },
                        label = { Text("$seconds s") },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertsCard(form: EditForm, vm: EditViewModel) {
    AppCard {
        Column {
            CardTitle("Notifications")
            SwitchRow(
                "Notify when it goes down",
                form.notifyOnDown,
                { value -> vm.edit { it.copy(notifyOnDown = value) } },
            )
            SwitchRow(
                "Notify when it comes back up",
                form.notifyOnUp,
                { value -> vm.edit { it.copy(notifyOnUp = value) } },
            )
            Spacer(Modifier.height(8.dp))
            CardTitle("Alarm")
            SwitchRow(
                "Sound an alarm when it goes down",
                form.alarmOnDown,
                { value -> vm.edit { it.copy(alarmOnDown = value) } },
                subtitle = "Plays on the alarm volume, even when the phone is on silent",
            )
            SwitchRow(
                "Sound an alarm when it comes back up",
                form.alarmOnUp,
                { value -> vm.edit { it.copy(alarmOnUp = value) } },
            )
            SwitchRow(
                "Repeat until acknowledged",
                form.repeatAlarm,
                { value -> vm.edit { it.copy(repeatAlarm = value) } },
                subtitle = "Otherwise the alarm rings for 30 seconds. Repeating stops after 15 minutes.",
                enabled = form.alarmOnDown || form.alarmOnUp,
            )
        }
    }
}
