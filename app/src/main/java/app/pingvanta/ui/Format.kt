package app.pingvanta.ui

import app.pingvanta.data.EventEntity
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.MonitorType
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun percent(value: Double?): String = when {
        value == null -> "—"
        value >= 100.0 -> "100%"
        else -> String.format(Locale.US, "%.2f%%", value)
    }

    fun responseTime(ms: Long?): String = if (ms == null) "—" else "$ms ms"

    fun interval(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    fun duration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun relative(timestamp: Long?, now: Long): String {
        if (timestamp == null) return "never"
        val diff = (now - timestamp).coerceAtLeast(0)
        return when {
            diff < 10_000 -> "just now"
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    fun dateTime(timestamp: Long?): String =
        if (timestamp == null) "—"
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

    fun time(timestamp: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

    fun targetLabel(monitor: MonitorEntity): String = when (monitor.type) {
        MonitorType.HTTP -> monitor.target
        MonitorType.TCP -> "${monitor.target}:${monitor.port}"
        MonitorType.PING -> monitor.target
    }

    fun typeLabel(type: MonitorType): String = when (type) {
        MonitorType.HTTP -> "HTTP(S)"
        MonitorType.TCP -> "TCP port"
        MonitorType.PING -> "Ping (ICMP)"
    }

    fun statusLabel(status: MonitorStatus, paused: Boolean = false): String = when {
        paused -> "PAUSED"
        else -> status.name
    }

    fun eventTitle(event: EventEntity): String = when (event.status) {
        MonitorStatus.UP -> if (event.initial) "Monitoring started, online" else "Back online"
        MonitorStatus.DOWN -> if (event.initial) "Monitoring started, offline" else "Went offline"
        MonitorStatus.UNKNOWN -> "Status unknown"
    }
}

/** Sum of downtime inside [since, now] across the given events. */
fun totalDowntimeMs(events: List<EventEntity>, since: Long, now: Long): Long =
    events.filter { it.status == MonitorStatus.DOWN }.sumOf { event ->
        val start = maxOf(event.startedAt, since)
        val end = minOf(event.endedAt ?: now, now)
        (end - start).coerceAtLeast(0)
    }
