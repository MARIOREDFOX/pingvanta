package app.pingvanta.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.pingvanta.alert.canPostNotifications
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pingvanta.R
import app.pingvanta.data.MonitorStatus
import app.pingvanta.ui.HomeState
import app.pingvanta.ui.HomeViewModel
import app.pingvanta.ui.appViewModel
import app.pingvanta.ui.components.AppCard
import app.pingvanta.ui.components.EmptyState
import app.pingvanta.ui.components.EventRow
import app.pingvanta.ui.components.MonitorCard
import app.pingvanta.ui.components.SectionHeader
import app.pingvanta.ui.components.StatTile
import app.pingvanta.ui.components.UptimeRing
import app.pingvanta.ui.components.rememberNow
import app.pingvanta.ui.theme.StatusColors

@Composable
fun BrandTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF4353E8), Color(0xFF7B5CFF))),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_logo),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("Pingvanta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun OfflineBanner(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(StatusColors.Degraded.copy(alpha = 0.16f), MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_cloud_off),
                contentDescription = null,
                tint = StatusColors.Degraded,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Your phone is offline. Checks are paused and will resume when the connection returns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun DashboardScreen(onOpenMonitor: (Long) -> Unit, onAddMonitor: () -> Unit) {
    val vm = appViewModel(HomeViewModel::class.java) { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val now by rememberNow()
    val context = LocalContext.current
    var notificationsBlocked by remember { mutableStateOf(!canPostNotifications(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsBlocked = !canPostNotifications(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BrandTitle() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (state.rows.isNotEmpty()) {
                        IconButton(onClick = vm::checkAll, enabled = !refreshing) {
                            if (refreshing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Check all monitors now")
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddMonitor,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add monitor") },
                modifier = Modifier.testTag("fab-add"),
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_logo),
                    title = "Nothing monitored yet",
                    message = "Add a website, a TCP port or a host to ping. Pingvanta checks it in the " +
                        "background and alerts you when its availability changes.",
                    actionLabel = "Add your first monitor",
                    onAction = onAddMonitor,
                )
            }
            else -> DashboardContent(state, now, padding, notificationsBlocked, onOpenMonitor)
        }
    }
}

@Composable
private fun DashboardContent(
    state: HomeState,
    now: Long,
    padding: PaddingValues,
    notificationsBlocked: Boolean,
    onOpenMonitor: (Long) -> Unit,
) {
    val active = state.rows.filter { !it.monitor.paused }
    val up = active.count { it.monitor.status == MonitorStatus.UP }
    val down = active.count { it.monitor.status == MonitorStatus.DOWN }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "offline") { OfflineBanner(!state.online) }

        if (notificationsBlocked) {
            item(key = "notifications-blocked") {
                Text(
                    "Notifications are blocked, so Pingvanta cannot alert you. Allow them in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusColors.Down.copy(alpha = 0.14f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        item(key = "hero") {
            AppCard(padding = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UptimeRing(state.overallUptime)
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text(
                            when {
                                down > 0 -> "$down monitor${if (down == 1) "" else "s"} down"
                                up == 0 -> "Waiting for first results"
                                else -> "All systems operational"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = if (down > 0) StatusColors.Down else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Overall uptime across all monitors, last 30 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item(key = "stats") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Monitors", state.rows.size.toString(), Modifier.weight(1f).testTag("stat-total"))
                    StatTile("Changed in 24h", state.recentChanges.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Up", up.toString(), Modifier.weight(1f), accent = StatusColors.Up)
                    StatTile("Down", down.toString(), Modifier.weight(1f), accent = StatusColors.Down)
                }
            }
        }

        item(key = "monitors-header") { SectionHeader("Monitors") }
        items(state.rows, key = { "m-${it.monitor.id}" }) { row ->
            MonitorCard(
                monitor = row.monitor,
                uptimePercent = row.uptimePercent,
                now = now,
                onClick = { onOpenMonitor(row.monitor.id) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "activity-header") { SectionHeader("Latest activity") }
        item(key = "activity") {
            AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                if (state.activity.isEmpty()) {
                    Text(
                        "No status changes yet. They will appear here after the first checks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    Column {
                        state.activity.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            EventRow(row, now)
                        }
                    }
                }
            }
        }
    }
}
