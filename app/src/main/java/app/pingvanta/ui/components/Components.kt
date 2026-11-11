package app.pingvanta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pingvanta.data.EventWithMonitor
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.ui.Format
import app.pingvanta.ui.theme.StatusColors
import app.pingvanta.ui.theme.color
import app.pingvanta.ui.theme.uptimeColor
import kotlinx.coroutines.delay

/** Current time that refreshes every 15 seconds so "2m ago" labels stay honest. */
@Composable
fun rememberNow(): State<Long> = produceState(initialValue = System.currentTimeMillis()) {
    while (true) {
        delay(15_000)
        value = System.currentTimeMillis()
    }
}

@Composable
fun StatusDot(status: MonitorStatus, paused: Boolean = false, modifier: Modifier = Modifier) {
    val target = if (paused) StatusColors.Unknown else status.color()
    val color by animateColorAsState(target, tween(400), label = "dotColor")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "pulseScale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "pulseAlpha",
    )
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (!paused && status != MonitorStatus.UNKNOWN) {
            Box(
                Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        this.alpha = pulseAlpha
                    }
                    .background(color, CircleShape),
            )
        }
        Box(Modifier.size(9.dp).background(color, CircleShape))
    }
}

@Composable
fun StatusPill(status: MonitorStatus, paused: Boolean = false, modifier: Modifier = Modifier) {
    val target = if (paused) StatusColors.Unknown else status.color()
    val color by animateColorAsState(target, tween(400), label = "pillColor")
    Text(
        text = Format.statusLabel(status, paused),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accent != null) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MonitorCard(
    monitor: MonitorEntity,
    uptimePercent: Double?,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    AppCard(modifier = modifier.testTag("monitor-card-${monitor.id}"), onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(monitor.status, monitor.paused)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        monitor.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Format.targetLabel(monitor),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(monitor.status, monitor.paused)
                trailing?.invoke()
            }
            if (monitor.status == MonitorStatus.DOWN && !monitor.paused && monitor.lastError != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    monitor.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Down,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("RESPONSE", Format.responseTime(monitor.lastResponseMs), Modifier.weight(1f))
                Metric(
                    "UPTIME 30D",
                    Format.percent(uptimePercent),
                    Modifier.weight(1f),
                    valueColor = if (uptimePercent == null) null else uptimeColor(uptimePercent),
                )
                Metric("EVERY", Format.interval(monitor.intervalSeconds), Modifier.weight(0.8f))
                Metric("CHECKED", Format.relative(monitor.lastCheckedAt, now), Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun EventRow(row: EventWithMonitor, now: Long, showMonitor: Boolean = true, modifier: Modifier = Modifier) {
    val event = row.event
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .background(event.status.color(), CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (showMonitor) "${row.monitorName}: ${Format.eventTitle(event).lowercase()}" else Format.eventTitle(event),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detail = buildString {
                append(Format.dateTime(event.startedAt))
                if (event.status == MonitorStatus.DOWN) {
                    val end = event.endedAt
                    append(if (end == null) " · ongoing for " else " · lasted ")
                    append(Format.duration((end ?: now) - event.startedAt))
                } else if (event.responseMs != null) {
                    append(" · ").append(event.responseMs).append(" ms")
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.status == MonitorStatus.DOWN && !event.reason.isNullOrBlank()) {
                Text(event.reason, style = MaterialTheme.typography.bodySmall, color = StatusColors.Down)
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: Painter,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
