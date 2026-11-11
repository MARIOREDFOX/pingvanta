package app.pingvanta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pingvanta.R
import app.pingvanta.data.MonitorStatus
import app.pingvanta.ui.DAY_MS
import app.pingvanta.ui.Format
import app.pingvanta.ui.HistoryViewModel
import app.pingvanta.ui.appViewModel
import app.pingvanta.ui.components.AppCard
import app.pingvanta.ui.components.EmptyState
import app.pingvanta.ui.components.EventRow
import app.pingvanta.ui.components.Metric
import app.pingvanta.ui.components.SectionHeader
import app.pingvanta.ui.components.StatTile
import app.pingvanta.ui.components.UptimeBars
import app.pingvanta.ui.components.rememberNow
import app.pingvanta.ui.totalDowntimeMs

@Composable
fun HistoryScreen(onOpenMonitor: (Long) -> Unit) {
    val vm = appViewModel(HistoryViewModel::class.java) { HistoryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now by rememberNow()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.monitors.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_history),
                    title = "No history yet",
                    message = "Once you add a monitor, every status change, outage duration and uptime figure is kept here.",
                )
            }
            else -> {
                val events = state.events.map { it.event }
                val incidents = events.filter { it.status == MonitorStatus.DOWN }
                val downtime = totalDowntimeMs(events, now - 30 * DAY_MS, now)
                val longest = incidents.maxOfOrNull { (it.endedAt ?: now) - it.startedAt }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "filter") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = state.filterId == null,
                                    onClick = { vm.setFilter(null) },
                                    label = { Text("All monitors") },
                                )
                            }
                            items(state.monitors, key = { it.id }) { monitor ->
                                FilterChip(
                                    selected = state.filterId == monitor.id,
                                    onClick = { vm.setFilter(monitor.id) },
                                    label = { Text(monitor.name) },
                                )
                            }
                        }
                    }

                    item(key = "uptime") {
                        Row(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatTile("Daily", Format.percent(state.uptimeDay.percent), Modifier.weight(1f))
                            StatTile("Weekly", Format.percent(state.uptimeWeek.percent), Modifier.weight(1f))
                            StatTile("Monthly", Format.percent(state.uptimeMonth.percent), Modifier.weight(1f))
                        }
                    }

                    item(key = "chart") {
                        AppCard(Modifier.padding(horizontal = 16.dp)) {
                            Column {
                                Text("Daily uptime, last 30 days", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(12.dp))
                                UptimeBars(state.daily)
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Metric("OUTAGES", incidents.size.toString(), Modifier.weight(0.7f))
                                    Metric("TOTAL DOWNTIME 30D", Format.duration(downtime), Modifier.weight(1.2f))
                                    Metric(
                                        "LONGEST OUTAGE",
                                        longest?.let { Format.duration(it) } ?: "—",
                                        Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    item(key = "events-header") {
                        SectionHeader("Status changes", Modifier.padding(horizontal = 16.dp))
                    }

                    if (state.events.isEmpty()) {
                        item(key = "events-empty") {
                            Text(
                                "No status changes recorded yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        items(state.events, key = { "e-${it.event.id}" }) { row ->
                            AppCard(
                                Modifier.padding(horizontal = 16.dp),
                                onClick = { onOpenMonitor(row.event.monitorId) },
                                padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                EventRow(row, now, showMonitor = state.filterId == null)
                            }
                        }
                    }
                }
            }
        }
    }
}
