package app.pingvanta.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pingvanta.data.CheckEntity
import app.pingvanta.data.DayBucket
import app.pingvanta.ui.Format
import app.pingvanta.ui.theme.StatusColors
import app.pingvanta.ui.theme.uptimeColor

/** Evenly thins a list so the chart never draws more than [max] points. */
fun <T> downsample(items: List<T>, max: Int): List<T> {
    if (items.size <= max || max < 2) return items
    val step = (items.size - 1).toDouble() / (max - 1)
    return (0 until max).map { items[(it * step).toInt().coerceAtMost(items.size - 1)] }
}

/** Response-time line for successful checks; failed checks are marked in red along the baseline. */
@Composable
fun ResponseTimeChart(checks: List<CheckEntity>, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val points = downsample(checks, 240)
    val successes = points.filter { it.up && it.responseMs != null }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("No checks recorded yet", style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
        return
    }

    val maxMs = (successes.maxOfOrNull { it.responseMs ?: 0L } ?: 0L).coerceAtLeast(1L)
    val avgMs = if (successes.isEmpty()) null else successes.sumOf { it.responseMs ?: 0L } / successes.size
    val minMs = successes.minOfOrNull { it.responseMs ?: 0L }
    val tMin = points.first().timestamp
    val tMax = points.last().timestamp

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Metric("AVERAGE", Format.responseTime(avgMs))
            Metric("FASTEST", Format.responseTime(minMs))
            Metric("SLOWEST", Format.responseTime(if (successes.isEmpty()) null else maxMs))
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height
            val top = h * 0.08f
            val usable = h - top
            for (i in 0..3) {
                val y = top + usable * i / 3f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            fun x(t: Long): Float = if (tMax == tMin) w / 2f else (t - tMin).toFloat() / (tMax - tMin).toFloat() * w
            fun y(ms: Long): Float = h - (ms.toFloat() / maxMs.toFloat()) * usable

            // Split into runs of consecutive successes so outages show as gaps in the line.
            val runs = ArrayList<MutableList<CheckEntity>>()
            var current: MutableList<CheckEntity>? = null
            for (p in points) {
                if (p.up && p.responseMs != null) {
                    val run = current ?: ArrayList<CheckEntity>().also {
                        runs.add(it)
                        current = it
                    }
                    run.add(p)
                } else {
                    current = null
                    drawLine(
                        StatusColors.Down,
                        Offset(x(p.timestamp), h),
                        Offset(x(p.timestamp), h - usable * 0.18f),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            for (run in runs) {
                if (run.size == 1) {
                    val only = run[0]
                    drawCircle(line, radius = 4f, center = Offset(x(only.timestamp), y(only.responseMs ?: 0L)))
                    continue
                }
                val stroke = Path()
                val fill = Path()
                run.forEachIndexed { index, p ->
                    val px = x(p.timestamp)
                    val py = y(p.responseMs ?: 0L)
                    if (index == 0) {
                        stroke.moveTo(px, py)
                        fill.moveTo(px, h)
                        fill.lineTo(px, py)
                    } else {
                        stroke.lineTo(px, py)
                        fill.lineTo(px, py)
                    }
                }
                fill.lineTo(x(run.last().timestamp), h)
                fill.close()
                drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), line.copy(alpha = 0f))))
                drawPath(stroke, line, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(Format.time(tMin), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text("peak $maxMs ms", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text(Format.time(tMax), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

/** One rounded bar per day, coloured by that day's uptime. Grey means no data. */
@Composable
fun UptimeBars(daily: List<DayBucket?>, modifier: Modifier = Modifier, height: Dp = 44.dp) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val count = daily.size.coerceAtLeast(1)
            val gap = size.width * 0.012f
            val barWidth = (size.width - gap * (count - 1)) / count
            daily.forEachIndexed { index, bucket ->
                val percent = bucket?.let { if (it.total == 0) null else it.up * 100.0 / it.total }
                drawRoundRect(
                    color = if (percent == null) empty else uptimeColor(percent),
                    topLeft = Offset(index * (barWidth + gap), 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(barWidth / 2.2f, barWidth / 2.2f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("${daily.size} days ago", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

/** 24 hourly segments ending now. */
@Composable
fun AvailabilityStrip(checks: List<CheckEntity>, now: Long, modifier: Modifier = Modifier) {
    val hourMs = 60 * 60 * 1000L
    val start = now - 24 * hourMs
    val up = IntArray(24)
    val total = IntArray(24)
    checks.forEach { c ->
        val slot = ((c.timestamp - start) / hourMs).toInt()
        if (slot in 0..23) {
            total[slot]++
            if (c.up) up[slot]++
        }
    }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val gap = size.width * 0.01f
            val barWidth = (size.width - gap * 23) / 24
            for (i in 0 until 24) {
                val percent = if (total[i] == 0) null else up[i] * 100.0 / total[i]
                drawRoundRect(
                    color = if (percent == null) empty else uptimeColor(percent),
                    topLeft = Offset(i * (barWidth + gap), 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(6f, 6f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("24h ago", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text("Now", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

/** Animated ring showing an uptime percentage. */
@Composable
fun UptimeRing(percent: Double?, modifier: Modifier = Modifier, diameter: Dp = 112.dp) {
    val target = ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val progress by animateFloatAsState(target, tween(900), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = uptimeColor(percent)
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.1f
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokeWidth))
            if (percent != null) {
                drawArc(
                    color,
                    -90f,
                    360f * progress,
                    false,
                    Offset(inset, inset),
                    arcSize,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                Format.percent(percent),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("uptime", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
