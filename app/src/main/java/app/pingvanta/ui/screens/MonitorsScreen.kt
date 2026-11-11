package app.pingvanta.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pingvanta.R
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.ui.HomeViewModel
import app.pingvanta.ui.MonitorRow
import app.pingvanta.ui.appViewModel
import app.pingvanta.ui.components.EmptyState
import app.pingvanta.ui.components.MonitorCard
import app.pingvanta.ui.components.rememberNow

private enum class MonitorFilter(val label: String) { ALL("All"), UP("Up"), DOWN("Down"), PAUSED("Paused") }

@Composable
fun MonitorsScreen(onOpenMonitor: (Long) -> Unit, onAddMonitor: () -> Unit, onEditMonitor: (Long) -> Unit) {
    val vm = appViewModel(HomeViewModel::class.java) { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now by rememberNow()
    var filter by rememberSaveable { mutableStateOf(MonitorFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MonitorEntity?>(null) }

    val visible = state.rows.filter { row ->
        val m = row.monitor
        val matchesFilter = when (filter) {
            MonitorFilter.ALL -> true
            MonitorFilter.UP -> !m.paused && m.status == MonitorStatus.UP
            MonitorFilter.DOWN -> !m.paused && m.status == MonitorStatus.DOWN
            MonitorFilter.PAUSED -> m.paused
        }
        val q = query.trim()
        matchesFilter && (q.isEmpty() || m.name.contains(q, ignoreCase = true) || m.target.contains(q, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monitors") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddMonitor, modifier = Modifier.testTag("fab-add")) {
                Icon(Icons.Filled.Add, contentDescription = "Add monitor")
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_monitors),
                    title = "No monitors",
                    message = "Monitors you add are listed here, where you can pause, edit or remove them.",
                    actionLabel = "Add monitor",
                    onAction = onAddMonitor,
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search by name or address") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MonitorFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                if (visible.isEmpty()) {
                    Text(
                        "No monitors match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.monitor.id }) { row ->
                        MonitorCard(
                            monitor = row.monitor,
                            uptimePercent = row.uptimePercent,
                            now = now,
                            onClick = { onOpenMonitor(row.monitor.id) },
                            modifier = Modifier.animateItem(),
                            trailing = {
                                MonitorMenu(
                                    row = row,
                                    onCheck = { vm.checkNow(row.monitor.id) },
                                    onTogglePause = { vm.setPaused(row.monitor.id, !row.monitor.paused) },
                                    onEdit = { onEditMonitor(row.monitor.id) },
                                    onDelete = { deleting = row.monitor },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    deleting?.let { monitor ->
        DeleteMonitorDialog(
            name = monitor.name,
            onConfirm = {
                vm.delete(monitor.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun MonitorMenu(
    row: MonitorRow,
    onCheck: () -> Unit,
    onTogglePause: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${row.monitor.name}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Check now") }, onClick = { open = false; onCheck() })
            DropdownMenuItem(
                text = { Text(if (row.monitor.paused) "Resume" else "Pause") },
                onClick = { open = false; onTogglePause() },
            )
            DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
fun DeleteMonitorDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = { Text("The monitor and all of its history will be removed. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
