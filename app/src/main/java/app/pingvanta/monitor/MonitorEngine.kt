package app.pingvanta.monitor

import androidx.room.withTransaction
import app.pingvanta.data.AppDatabase
import app.pingvanta.data.CheckEntity
import app.pingvanta.data.EventEntity
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.MonitorType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

interface ConnectivityProbe {
    /** True when the device has a validated internet connection. */
    fun isOnline(): Boolean
}

/** Receives exactly one call per real status transition. */
interface AlertSink {
    fun onTransition(monitor: MonitorEntity, outcome: CheckOutcome, previousSuccessAt: Long?, at: Long)
}

data class TransitionDecision(val changed: Boolean, val alert: Boolean)

object Transitions {
    /**
     * A status that stays the same never alerts. The first result of a new monitor
     * (UNKNOWN -> UP/DOWN) is recorded as a baseline and does not alert either.
     */
    fun decide(old: MonitorStatus, new: MonitorStatus): TransitionDecision {
        val changed = old != new
        return TransitionDecision(changed = changed, alert = changed && old != MonitorStatus.UNKNOWN)
    }
}

sealed interface RunResult {
    data class Skipped(val reason: String) : RunResult
    data class Checked(val outcome: CheckOutcome, val decision: TransitionDecision) : RunResult

    companion object {
        const val OFFLINE = "Device offline"
    }
}

/**
 * Runs checks, persists results and detects status transitions. Every scheduler (WorkManager,
 * the live service, manual "check now") goes through here, so a per-monitor lock plus the stored
 * status guarantee one alert per transition no matter how many callers overlap.
 */
class MonitorEngine(
    private val db: AppDatabase,
    private val checkers: Map<MonitorType, Checker>,
    private val connectivity: ConnectivityProbe,
    private val alerts: AlertSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 3_000,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun runCheck(monitorId: Long, force: Boolean = false): RunResult {
        val lock = locks.getOrPut(monitorId) { Mutex() }
        return lock.withLock { runLocked(monitorId, force) }
    }

    private suspend fun runLocked(monitorId: Long, force: Boolean): RunResult {
        val monitorDao = db.monitorDao()
        val monitor = monitorDao.get(monitorId) ?: return RunResult.Skipped("Monitor deleted")
        if (monitor.paused && !force) return RunResult.Skipped("Paused")

        if (!force) {
            // Two schedulers firing close together should produce one check, not two.
            val last = monitor.lastCheckedAt
            val guard = minOf(monitor.intervalSeconds * 500L, 30_000L)
            if (last != null && clock() - last < guard) return RunResult.Skipped("Checked recently")
        }

        // With no internet on the phone itself every target would look down. Skip instead.
        if (!connectivity.isOnline()) return RunResult.Skipped(RunResult.OFFLINE)

        val checker = checkers.getValue(monitor.type)
        var outcome = checker.check(monitor)
        if (!outcome.up && monitor.status == MonitorStatus.UP) {
            // Confirm a failure once before declaring an outage, to avoid alerting on a blip.
            delay(retryDelayMs)
            if (!connectivity.isOnline()) return RunResult.Skipped(RunResult.OFFLINE)
            outcome = checker.check(monitor)
        }
        if (!outcome.up && !connectivity.isOnline()) return RunResult.Skipped(RunResult.OFFLINE)

        val at = clock()
        val newStatus = if (outcome.up) MonitorStatus.UP else MonitorStatus.DOWN
        var decision = TransitionDecision(changed = false, alert = false)
        var alertMonitor: MonitorEntity? = null

        db.withTransaction<Unit> {
            // Re-read inside the transaction: the monitor may have been edited or deleted meanwhile.
            val fresh = monitorDao.get(monitorId) ?: return@withTransaction
            decision = Transitions.decide(fresh.status, newStatus)
            db.checkDao().insert(
                CheckEntity(
                    monitorId = monitorId,
                    timestamp = at,
                    up = outcome.up,
                    responseMs = outcome.responseMs,
                    httpCode = outcome.httpCode,
                    error = outcome.error,
                ),
            )
            monitorDao.updateRuntime(
                id = monitorId,
                status = newStatus,
                checkedAt = at,
                responseMs = outcome.responseMs,
                httpCode = outcome.httpCode,
                error = outcome.error,
                lastSuccessAt = if (outcome.up) at else fresh.lastSuccessAt,
                lastStatusChangeAt = if (decision.changed) at else fresh.lastStatusChangeAt,
            )
            if (decision.changed) {
                db.eventDao().closeOpen(monitorId, at)
                db.eventDao().insert(
                    EventEntity(
                        monitorId = monitorId,
                        status = newStatus,
                        startedAt = at,
                        reason = outcome.error,
                        responseMs = outcome.responseMs,
                        initial = fresh.status == MonitorStatus.UNKNOWN,
                    ),
                )
            }
            if (decision.alert) alertMonitor = fresh.copy(status = newStatus)
        }

        alertMonitor?.let { alerts.onTransition(it, outcome, monitor.lastSuccessAt, at) }
        return RunResult.Checked(outcome, decision)
    }

    /** Drops check rows older than the retention window (monthly stats need 30 days). */
    suspend fun prune() {
        db.checkDao().deleteOlderThan(clock() - RETENTION_MS)
    }

    companion object {
        const val RETENTION_MS = 32L * 24 * 60 * 60 * 1000
    }
}
