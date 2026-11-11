package app.pingvanta

import app.pingvanta.data.Backup
import app.pingvanta.data.DayBucket
import app.pingvanta.data.EventEntity
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.MonitorType
import app.pingvanta.monitor.FailureReasons
import app.pingvanta.monitor.StatusCodeSpec
import app.pingvanta.monitor.Targets
import app.pingvanta.monitor.Transitions
import app.pingvanta.ui.DAY_MS
import app.pingvanta.ui.Format
import app.pingvanta.ui.fillDays
import app.pingvanta.ui.totalDowntimeMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringWriter
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class StatusCodeSpecTest {
    @Test
    fun `default range accepts 2xx and 3xx only`() {
        val spec = StatusCodeSpec.parse(StatusCodeSpec.DEFAULT)!!
        assertTrue(spec.matches(200))
        assertTrue(spec.matches(301))
        assertFalse(spec.matches(404))
        assertFalse(spec.matches(500))
        assertFalse(spec.matches(503))
    }

    @Test
    fun `mixed list of codes and ranges`() {
        val spec = StatusCodeSpec.parse(" 200-299, 401 ,403")!!
        assertTrue(spec.matches(204))
        assertTrue(spec.matches(401))
        assertTrue(spec.matches(403))
        assertFalse(spec.matches(402))
        assertFalse(spec.matches(302))
    }

    @Test
    fun `invalid input is rejected`() {
        assertNull(StatusCodeSpec.parse(""))
        assertNull(StatusCodeSpec.parse("abc"))
        assertNull(StatusCodeSpec.parse("300-200"))
        assertNull(StatusCodeSpec.parse("99"))
        assertNull(StatusCodeSpec.parse("200-700"))
        assertNull(StatusCodeSpec.parse("200-300-400"))
        assertTrue(StatusCodeSpec.isValid("200"))
    }
}

class TransitionsTest {
    @Test
    fun `unchanged status never alerts`() {
        for (status in MonitorStatus.entries) {
            val decision = Transitions.decide(status, status)
            assertFalse(decision.changed)
            assertFalse(decision.alert)
        }
    }

    @Test
    fun `first result is a baseline without alert`() {
        assertTrue(Transitions.decide(MonitorStatus.UNKNOWN, MonitorStatus.UP).changed)
        assertFalse(Transitions.decide(MonitorStatus.UNKNOWN, MonitorStatus.UP).alert)
        assertFalse(Transitions.decide(MonitorStatus.UNKNOWN, MonitorStatus.DOWN).alert)
    }

    @Test
    fun `real transitions alert`() {
        assertTrue(Transitions.decide(MonitorStatus.UP, MonitorStatus.DOWN).alert)
        assertTrue(Transitions.decide(MonitorStatus.DOWN, MonitorStatus.UP).alert)
    }
}

class TargetsTest {
    @Test
    fun `bare host gets https`() {
        assertEquals("https://www.google.com/", Targets.httpUrl("www.google.com").toString())
        assertEquals("http://example.com/x", Targets.httpUrl("http://example.com/x").toString())
        assertNull(Targets.httpUrl(""))
        assertNull(Targets.httpUrl("ftp://example.com"))
    }

    @Test
    fun `host extraction`() {
        assertEquals("example.com", Targets.host("example.com"))
        assertEquals("example.com", Targets.host("example.com:8080"))
        assertEquals("example.com", Targets.host("tcp://example.com:22/path"))
        assertEquals("github.com", Targets.host("https://github.com/"))
        assertEquals("::1", Targets.host("[::1]:443"))
        assertEquals(8080, Targets.embeddedPort("example.com:8080"))
        assertNull(Targets.embeddedPort("example.com"))
    }
}

class FailureReasonsTest {
    @Test
    fun `network errors map to readable reasons`() {
        assertEquals("DNS lookup failed", FailureReasons.describe(UnknownHostException("nope")))
        assertEquals("Connection timeout", FailureReasons.describe(SocketTimeoutException("timeout")))
        assertEquals("Connection refused", FailureReasons.describe(ConnectException("refused")))
        assertTrue(FailureReasons.describe(SSLHandshakeException("bad cert")).startsWith("SSL handshake failed"))
        assertTrue(FailureReasons.describe(IOException("reset")).startsWith("Connection error"))
    }
}

class BackupTest {
    @Test
    fun `monitors survive a JSON round trip without runtime state`() {
        val original = listOf(
            MonitorEntity(
                id = 7,
                name = "Google",
                target = "https://www.google.com",
                intervalSeconds = 60,
                expectedCodes = "200-299",
                alarmOnDown = true,
                repeatAlarm = true,
                status = MonitorStatus.DOWN,
                lastError = "Connection timeout",
            ),
            MonitorEntity(name = "SSH", target = "example.com", type = MonitorType.TCP, port = 22),
        )
        val parsed = Backup.monitorsFromJson(Backup.monitorsToJson(original))
        assertEquals(2, parsed.size)
        val google = parsed[0]
        assertEquals(0L, google.id)
        assertEquals("Google", google.name)
        assertEquals(60, google.intervalSeconds)
        assertEquals("200-299", google.expectedCodes)
        assertTrue(google.alarmOnDown)
        assertTrue(google.repeatAlarm)
        assertEquals(MonitorStatus.UNKNOWN, google.status)
        assertNull(google.lastError)
        assertEquals(MonitorType.TCP, parsed[1].type)
        assertEquals(22, parsed[1].port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `garbage is rejected`() {
        Backup.monitorsFromJson("not json")
    }

    @Test
    fun `csv escapes commas and quotes`() {
        assertEquals("plain", Backup.csv("plain"))
        assertEquals("\"a,b\"", Backup.csv("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Backup.csv("say \"hi\""))
        val out = StringWriter()
        Backup.writeHistoryCsv(out, emptyList(), emptyList())
        assertTrue(out.toString().startsWith("record,monitor,target"))
    }
}

class StatsTest {
    @Test
    fun `downtime is clipped to the window and counts ongoing outages`() {
        val now = 1_000_000L
        val events = listOf(
            EventEntity(monitorId = 1, status = MonitorStatus.DOWN, startedAt = 100_000, endedAt = 160_000),
            EventEntity(monitorId = 1, status = MonitorStatus.UP, startedAt = 160_000, endedAt = 900_000),
            EventEntity(monitorId = 1, status = MonitorStatus.DOWN, startedAt = 900_000, endedAt = null),
        )
        assertEquals(160_000L, totalDowntimeMs(events, 0, now))
        assertEquals(110_000L, totalDowntimeMs(events, 150_000, now))
    }

    @Test
    fun `days without checks are null`() {
        val now = 100 * DAY_MS + 5_000
        val filled = fillDays(listOf(DayBucket(day = 99, up = 9, total = 10, avgMs = 120.0)), now, 0, days = 3)
        assertEquals(3, filled.size)
        assertNull(filled[0])
        assertNotNull(filled[1])
        assertNull(filled[2])
    }

    @Test
    fun formatting() {
        assertEquals("100%", Format.percent(100.0))
        assertEquals("99.50%", Format.percent(99.5))
        assertEquals("—", Format.percent(null))
        assertEquals("1 min", Format.interval(60))
        assertEquals("1 h", Format.interval(3600))
        assertEquals("2m 5s", Format.duration(125_000))
        assertEquals("never", Format.relative(null, 0))
    }
}
