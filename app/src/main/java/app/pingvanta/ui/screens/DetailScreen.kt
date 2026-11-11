package app.pingvanta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pingvanta.background.MonitorScheduler
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.MonitorType
import app.pingvanta.ui.DAY_MS
import app.pingvanta.ui.DetailState
import app.pingvanta.ui.DetailViewModel
import app.pingvanta.ui.Format
import app.pingvanta.ui.appViewModel
import app.pingvanta.ui.components.AppCard
import app.pingvanta.ui.components.AvailabilityStrip
import app.pingvanta.ui.components.EventRow
import app.pingvanta.ui.components.Metric
import app.pingvanta.ui.components.ResponseTimeChart
import app.pingvanta.ui.components.SectionHeader
import app.pingvanta.ui.components.StatTile
import app.pingvanta.ui.components.StatusDot
import app.pingvanta.ui.components.StatusPill
import app.pingvanta.ui.components.UptimeBars
import app.pingvanta.ui.components.rememberNow
import app.pingvanta.ui.theme.StatusColors
import app.pingvanta.ui.theme.color
import app.pingvanta.ui.totalDowntimeMs

@Composable
fun DetailScreen(monitorId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val vm = appViewModel(DetailViewModel::class.java, key = "detail-$monitorId") { DetailViewModel(it, monitorId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val now by rememberNow()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val monitor = state.monitor
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(monitor?.name ?: "Monitor", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (monitor != null) {
                        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit monitor") }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete monitor")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            monitor == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This monitor no longer exists.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> DetailContent(
                state = state,
                monitor = monitor,
                now = now,
                padding = padding,
                checking = checking,
                onCheckNow = vm::checkNow,
                onTogglePause = { vm.setPaused(!monitor.paused) },
                onEdit = onEdit,
            )
        }
    }

    if (confirmDelete && monitor != null) {
        DeleteMonitorDialog(
            name = monitor.name,
            onConfirm = {
                confirmDelete = false
                vm.delete(onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailContent(
    state: DetailState,
    monitor: MonitorEntity,
    now: Long,
    padding: PaddingValues,
    checking: Boolean,
    onCheckNow: () -> Unit,
    onTogglePause: () -> Unit,
    onEdit: () -> Unit,
) {
    val events = state.events.map { it.event }
    val incidents = state.events.filter { it.event.status == MonitorStatus.DOWN }
    val downtime30d = totalDowntimeMs(events, now - 30 * DAY_MS, now)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "status") {
            AppCard(padding = PaddingValues(18.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(monitor.status, monitor.paused)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                monitor.paused -> "Paused"
                                monitor.status == MonitorStatus.UP -> "Online"
                                monitor.status == MonitorStatus.DOWN -> "Offline"
                                else -> "Waiting for first check"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (monitor.paused) StatusColors.Unknown else monitor.status.color(),
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(monitor.status, monitor.paused)
                    }
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer {
                        Text(
                            Format.targetLabel(monitor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (monitor.status == MonitorStatus.DOWN && monitor.lastError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Reason: ${monitor.lastError}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusColors.Down,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric("RESPONSE", Format.responseTime(monitor.lastResponseMs), Modifier.weight(1f))
                        Metric(
                            if (monitor.type == MonitorType.HTTP) "HTTP CODE" else "TYPE",
                            if (monitor.type == MonitorType.HTTP) monitor.lastHttpCode?.toString() ?: "—"
                            else Format.typeLabel(monitor.type),
                            Modifier.weight(1f),
                        )
                        Metric("CHECKED", Format.relative(monitor.lastCheckedAt, now), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(
                            if (monitor.status == MonitorStatus.DOWN) "DOWN SINCE" else "IN THIS STATE SINCE",
                            Format.dateTime(monitor.lastStatusChangeAt),
                            Modifier.weight(1f),
                        )
                        Metric("LAST SUCCESS", Format.dateTime(monitor.lastSuccessAt), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onCheckNow, enabled = !checking, modifier = Modifier.weight(1f)) {
                            if (checking) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Checking")
                            } else {
                                Text("Check now")
                            }
                        }
                        OutlinedButton(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
                            Text(if (monitor.paused) "Resume" else "Pause")
                        }
                    }
                }
            }
        }

        item(key = "availability") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Last 24 hours", Format.percent(state.uptime24h.percent), Modifier.weight(1f))
                StatTile("Last 7 days", Format.percent(state.uptime7d.percent), Modifier.weight(1f))
                StatTile("Last 30 days", Format.percent(state.uptime30d.percent), Modifier.weight(1f))
            }
        }

        item(key = "response") {
            AppCard {
                Column {
                    Text("Response time, last 24 hours", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    ResponseTimeChart(state.checks)
                }
            }
        }

        item(key = "strip") {
            AppCard {
                Column {
                    Text("Availability by hour, last 24 hours", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    AvailabilityStrip(state.checks, now)
                }
            }
        }

        item(key = "daily") {
            AppCard {
                Column {
                    Text("Daily uptime, last 30 days", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    UptimeBars(state.daily)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric("INCIDENTS", incidents.size.toString(), Modifier.weight(1f))
                        Metric("TOTAL DOWNTIME 30D", Format.duration(downtime30d), Modifier.weight(1f))
                    }
                }
            }
        }

        item(key = "incidents-header") { SectionHeader("Recent incidents") }
        item(key = "incidents") {
            AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                if (incidents.isEmpty()) {
                    Text(
                        "No downtime recorded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    Column {
                        incidents.take(5).forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            EventRow(row, now, showMonitor = false)
                        }
                    }
                }
            }
        }

        item(key = "history-header") { SectionHeader("Monitoring history") }
        item(key = "history") {
            AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                if (state.events.isEmpty()) {
                    Text(
                        "Status changes will be listed here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    Column {
                        state.events.take(30).forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            EventRow(row, now, showMonitor = false)
                        }
                    }
                }
            }
        }

        item(key = "config-header") { SectionHeader("Configuration") }
        item(key = "config") {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConfigRow("Type", Format.typeLabel(monitor.type))
                    ConfigRow("Address", Format.targetLabel(monitor))
                    ConfigRow("Check interval", Format.interval(monitor.intervalSeconds))
                    ConfigRow("Timeout", "${monitor.timeoutSeconds} s")
                    if (monitor.type == MonitorType.HTTP) ConfigRow("Expected status codes", monitor.expectedCodes)
                    ConfigRow(
                        "Notifications",
                        listOfNotNull(
                            "when up".takeIf { monitor.notifyOnUp },
                            "when down".takeIf { monitor.notifyOnDown },
                        ).joinToString(", ").ifEmpty { "off" },
                    )
                    ConfigRow(
                        "Alarm",
                        listOfNotNull(
                            "when up".takeIf { monitor.alarmOnUp },
                            "when down".takeIf { monitor.alarmOnDown },
                            "repeats until acknowledged".takeIf {
                                monitor.repeatAlarm && (monitor.alarmOnUp || monitor.alarmOnDown)
                            },
                        ).joinToString(", ").ifEmpty { "off" },
                    )
                    if (monitor.intervalSeconds < MonitorScheduler.MIN_PERIODIC_MINUTES * 60) {
                        Text(
                            "Intervals under 15 minutes rely on the live monitoring service. If it is off " +
                                "or Android stops it, this monitor is checked about every 15 minutes instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit configuration") }
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f),
        )
    }
}
