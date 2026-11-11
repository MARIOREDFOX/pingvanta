package app.pingvanta.data

import androidx.room.withTransaction
import app.pingvanta.background.MonitorScheduler
import app.pingvanta.monitor.MonitorEngine
import app.pingvanta.monitor.RunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Single entry point for changing monitors; keeps the database and the schedulers in step. */
class MonitorRepository(
    private val db: AppDatabase,
    private val engine: MonitorEngine,
    private val scheduler: MonitorScheduler,
    private val appScope: CoroutineScope,
) {
    private val monitorDao = db.monitorDao()

    suspend fun add(monitor: MonitorEntity): Long {
        val id = monitorDao.insert(
            monitor.copy(
                id = 0,
                status = MonitorStatus.UNKNOWN,
                lastCheckedAt = null,
                lastResponseMs = null,
                lastHttpCode = null,
                lastError = null,
                lastSuccessAt = null,
                lastStatusChangeAt = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
        scheduler.syncAll()
        // First real check straight away, outside the caller's lifecycle.
        if (!monitor.paused) appScope.launch { runCatching { engine.runCheck(id, force = true) } }
        return id
    }

    /** Saves configuration. If what is being checked changed, the monitor starts over from UNKNOWN. */
    suspend fun update(edited: MonitorEntity) {
        val current = monitorDao.get(edited.id) ?: return
        val targetChanged = current.target != edited.target || current.type != edited.type ||
            current.port != edited.port || current.expectedCodes != edited.expectedCodes
        db.withTransaction<Unit> {
            if (targetChanged) {
                db.eventDao().closeOpen(edited.id, System.currentTimeMillis())
                monitorDao.update(
                    edited.copy(
                        status = MonitorStatus.UNKNOWN,
                        lastCheckedAt = null,
                        lastResponseMs = null,
                        lastHttpCode = null,
                        lastError = null,
                        lastSuccessAt = null,
                        lastStatusChangeAt = null,
                        createdAt = current.createdAt,
                    ),
                )
            } else {
                // Keep whatever runtime state the engine has written most recently.
                monitorDao.update(
                    edited.copy(
                        status = current.status,
                        lastCheckedAt = current.lastCheckedAt,
                        lastResponseMs = current.lastResponseMs,
                        lastHttpCode = current.lastHttpCode,
                        lastError = current.lastError,
                        lastSuccessAt = current.lastSuccessAt,
                        lastStatusChangeAt = current.lastStatusChangeAt,
                        createdAt = current.createdAt,
                    ),
                )
            }
        }
        scheduler.syncAll()
        if (targetChanged && !edited.paused) {
            appScope.launch { runCatching { engine.runCheck(edited.id, force = true) } }
        }
    }

    suspend fun delete(id: Long) {
        scheduler.cancel(id)
        monitorDao.delete(id)
        scheduler.syncAll()
    }

    suspend fun setPaused(id: Long, paused: Boolean) {
        monitorDao.setPaused(id, paused)
        scheduler.syncAll()
        if (!paused) appScope.launch { runCatching { engine.runCheck(id, force = true) } }
    }

    suspend fun checkNow(id: Long): RunResult = engine.runCheck(id, force = true)

    fun checkAllNow() {
        appScope.launch {
            monitorDao.getAll().filter { !it.paused }.forEach { monitor ->
                launch { runCatching { engine.runCheck(monitor.id, force = true) } }
            }
        }
    }

    suspend fun importAll(monitors: List<MonitorEntity>): Int {
        val existing = monitorDao.getAll().map { Triple(it.type, it.target.trim().lowercase(), it.port) }.toSet()
        var added = 0
        for (monitor in monitors) {
            val key = Triple(monitor.type, monitor.target.trim().lowercase(), monitor.port)
            if (key in existing) continue
            add(monitor)
            added++
        }
        return added
    }
}
