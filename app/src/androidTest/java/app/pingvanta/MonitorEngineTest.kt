package app.pingvanta

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pingvanta.data.AppDatabase
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.MonitorType
import app.pingvanta.monitor.AlertSink
import app.pingvanta.monitor.CheckOutcome
import app.pingvanta.monitor.Checker
import app.pingvanta.monitor.ConnectivityProbe
import app.pingvanta.monitor.MonitorEngine
import app.pingvanta.monitor.RunResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Engine behaviour against a real (in-memory) Room database with a scripted checker. */
@RunWith(AndroidJUnit4::class)
class MonitorEngineTest {
    private lateinit var db: AppDatabase
    private lateinit var engine: MonitorEngine
    private var nextUp = true
    private var online = true
    private val alerts = ArrayList<MonitorStatus>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val checker = object : Checker {
            override suspend fun check(monitor: MonitorEntity) =
                if (nextUp) CheckOutcome(up = true, responseMs = 42, httpCode = 200)
                else CheckOutcome(up = false, error = "Connection timeout")
        }
        engine = MonitorEngine(
            db = db,
            checkers = MonitorType.entries.associateWith { checker },
            connectivity = object : ConnectivityProbe {
                override fun isOnline() = online
            },
            alerts = object : AlertSink {
                override fun onTransition(monitor: MonitorEntity, outcome: CheckOutcome, previousSuccessAt: Long?, at: Long) {
                    alerts += monitor.status
                }
            },
            retryDelayMs = 0,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun alertsOncePerTransitionAndNeverForRepeats() = runBlocking {
        val id = db.monitorDao().insert(MonitorEntity(name = "Google", target = "https://www.google.com"))

        engine.runCheck(id, force = true)
        assertEquals(MonitorStatus.UP, db.monitorDao().get(id)!!.status)
        assertTrue("baseline must not alert", alerts.isEmpty())

        nextUp = false
        engine.runCheck(id, force = true)
        engine.runCheck(id, force = true)
        engine.runCheck(id, force = true)
        assertEquals(listOf(MonitorStatus.DOWN), alerts)
        val down = db.monitorDao().get(id)!!
        assertEquals(MonitorStatus.DOWN, down.status)
        assertEquals("Connection timeout", down.lastError)
        assertNotNull(down.lastSuccessAt)

        nextUp = true
        engine.runCheck(id, force = true)
        engine.runCheck(id, force = true)
        assertEquals(listOf(MonitorStatus.DOWN, MonitorStatus.UP), alerts)

        val events = db.eventDao().observeEvents(id, 10).first().map { it.event }
        assertEquals(3, events.size)
        assertNull("latest event is still open", events.first().endedAt)
        assertNotNull("the outage has an end time", events[1].endedAt)
        assertEquals(6, db.checkDao().observeUptime(id, 0).first().total)
    }

    @Test
    fun offlineDeviceSkipsInsteadOfMarkingDown() = runBlocking {
        val id = db.monitorDao().insert(MonitorEntity(name = "Example", target = "https://example.com"))
        engine.runCheck(id, force = true)
        online = false
        nextUp = false
        val result = engine.runCheck(id, force = true)
        assertTrue(result is RunResult.Skipped)
        assertEquals(MonitorStatus.UP, db.monitorDao().get(id)!!.status)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun pausedMonitorsAndRapidRepeatsAreSkipped() = runBlocking {
        val id = db.monitorDao().insert(MonitorEntity(name = "GitHub", target = "https://github.com"))
        assertTrue(engine.runCheck(id) is RunResult.Checked)
        assertTrue("second scheduler firing right after", engine.runCheck(id) is RunResult.Skipped)
        db.monitorDao().setPaused(id, true)
        assertTrue(engine.runCheck(id) is RunResult.Skipped)
    }
}
