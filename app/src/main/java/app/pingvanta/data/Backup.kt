package app.pingvanta.data

import app.pingvanta.monitor.StatusCodeSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.time.Instant

/** JSON import/export of monitor configuration and CSV export of history. */
object Backup {
    const val FORMAT_VERSION = 1

    fun monitorsToJson(monitors: List<MonitorEntity>, exportedAt: Long = System.currentTimeMillis()): String {
        val array = JSONArray()
        monitors.forEach { m ->
            array.put(
                JSONObject()
                    .put("name", m.name)
                    .put("target", m.target)
                    .put("type", m.type.name)
                    .put("port", m.port)
                    .put("intervalSeconds", m.intervalSeconds)
                    .put("timeoutSeconds", m.timeoutSeconds)
                    .put("expectedCodes", m.expectedCodes)
                    .put("notifyOnUp", m.notifyOnUp)
                    .put("notifyOnDown", m.notifyOnDown)
                    .put("alarmOnUp", m.alarmOnUp)
                    .put("alarmOnDown", m.alarmOnDown)
                    .put("repeatAlarm", m.repeatAlarm)
                    .put("paused", m.paused),
            )
        }
        return JSONObject()
            .put("app", "Pingvanta")
            .put("formatVersion", FORMAT_VERSION)
            .put("exportedAt", Instant.ofEpochMilli(exportedAt).toString())
            .put("monitors", array)
            .toString(2)
    }

    /** Throws IllegalArgumentException with a readable message when the file is not usable. */
    fun monitorsFromJson(text: String): List<MonitorEntity> {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This file is not valid JSON")
        }
        val array = root.optJSONArray("monitors")
            ?: throw IllegalArgumentException("No \"monitors\" list found in this file")
        val result = ArrayList<MonitorEntity>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val target = o.optString("target").trim()
            if (target.isEmpty()) continue
            val type = runCatching { MonitorType.valueOf(o.optString("type", "HTTP")) }.getOrDefault(MonitorType.HTTP)
            val codes = o.optString("expectedCodes", StatusCodeSpec.DEFAULT)
            result += MonitorEntity(
                name = o.optString("name").trim().ifEmpty { target },
                target = target,
                type = type,
                port = o.optInt("port", 443).coerceIn(1, 65535),
                intervalSeconds = o.optInt("intervalSeconds", 300).coerceIn(60, 86_400),
                timeoutSeconds = o.optInt("timeoutSeconds", 15).coerceIn(1, 120),
                expectedCodes = if (StatusCodeSpec.isValid(codes)) codes else StatusCodeSpec.DEFAULT,
                notifyOnUp = o.optBoolean("notifyOnUp", true),
                notifyOnDown = o.optBoolean("notifyOnDown", true),
                alarmOnUp = o.optBoolean("alarmOnUp", false),
                alarmOnDown = o.optBoolean("alarmOnDown", false),
                repeatAlarm = o.optBoolean("repeatAlarm", false),
                paused = o.optBoolean("paused", false),
            )
        }
        return result
    }

    fun writeHistoryCsv(out: Writer, events: List<EventWithMonitor>, checks: List<CheckExportRow>) {
        out.write("record,monitor,target,time_utc,status,response_ms,http_code,ended_utc,duration_seconds,detail\n")
        events.forEach { row ->
            val e = row.event
            val duration = e.endedAt?.let { (it - e.startedAt) / 1000 }
            out.write(
                listOf(
                    "status_change",
                    csv(row.monitorName),
                    csv(row.monitorTarget),
                    iso(e.startedAt),
                    e.status.name,
                    e.responseMs?.toString().orEmpty(),
                    "",
                    e.endedAt?.let { iso(it) }.orEmpty(),
                    duration?.toString().orEmpty(),
                    csv(e.reason.orEmpty()),
                ).joinToString(",") + "\n",
            )
        }
        checks.forEach { c ->
            out.write(
                listOf(
                    "check",
                    csv(c.monitorName),
                    csv(c.monitorTarget),
                    iso(c.timestamp),
                    if (c.up) "UP" else "DOWN",
                    c.responseMs?.toString().orEmpty(),
                    c.httpCode?.toString().orEmpty(),
                    "",
                    "",
                    csv(c.error.orEmpty()),
                ).joinToString(",") + "\n",
            )
        }
        out.flush()
    }

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
