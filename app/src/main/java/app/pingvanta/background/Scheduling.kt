package app.pingvanta.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pingvanta.PingvantaApp
import app.pingvanta.data.MonitorDao
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.SettingsStore
import app.pingvanta.monitor.RunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Owns all background scheduling.
 *
 * WorkManager is the persistent baseline: one uniquely named periodic job per monitor, which
 * survives app restarts and reboots. Android enforces a 15-minute minimum period and may defer
 * runs further (Doze, battery saver, app standby), so intervals below 15 minutes are served by
 * [LiveMonitorService] while the WorkManager job remains as a safety net.
 */
class MonitorScheduler(
    private val context: Context,
    private val monitorDao: MonitorDao,
    private val settings: SettingsStore,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Makes scheduled work match the database. Safe to call any number of times. */
    suspend fun syncAll() {
        val monitors = monitorDao.getAll()
        monitors.forEach { if (it.paused) cancel(it.id) else schedule(it) }
        enqueueMaintenance()
        syncLiveService(monitors)
    }

    fun schedule(monitor: MonitorEntity) {
        val minutes = (monitor.intervalSeconds / 60L).coerceAtLeast(MIN_PERIODIC_MINUTES)
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(MonitorWorker.KEY_MONITOR_ID to monitor.id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag(TAG_MONITOR)
            .build()
        // UPDATE keeps the existing schedule and never creates a second worker for the same monitor.
        workManager.enqueueUniquePeriodicWork(workName(monitor.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(monitorId: Long) {
        workManager.cancelUniqueWork(workName(monitorId))
    }

    /** Re-checks everything as soon as the device regains connectivity. */
    fun enqueueSweepOnConnect() {
        val request = OneTimeWorkRequestBuilder<SweepWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(WORK_SWEEP, ExistingWorkPolicy.KEEP, request)
    }

    private fun enqueueMaintenance() {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(WORK_MAINTENANCE, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    suspend fun syncLiveService(monitors: List<MonitorEntity>? = null) {
        val list = monitors ?: monitorDao.getAll()
        if (needsLiveService(list, settings.current().liveMonitoring)) {
            LiveMonitorService.start(context)
        } else {
            LiveMonitorService.stop(context)
        }
    }

    companion object {
        const val MIN_PERIODIC_MINUTES = 15L
        const val TAG_MONITOR = "monitor-check"
        const val WORK_SWEEP = "sweep-on-connect"
        const val WORK_MAINTENANCE = "maintenance"

        fun workName(monitorId: Long) = "monitor-$monitorId"

        fun needsLiveService(monitors: List<MonitorEntity>, liveEnabled: Boolean): Boolean =
            liveEnabled && monitors.any { !it.paused && it.intervalSeconds < MIN_PERIODIC_MINUTES * 60 }
    }
}

class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_MONITOR_ID, -1L)
        if (id < 0) return Result.failure()
        val container = (applicationContext as PingvantaApp).container
        return try {
            val result = container.engine.runCheck(id)
            if (result is RunResult.Skipped && result.reason == RunResult.OFFLINE) {
                container.scheduler.enqueueSweepOnConnect()
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_MONITOR_ID = "monitorId"
    }
}

class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PingvantaApp).container
        return try {
            val monitors = container.db.monitorDao().getAll().filter { !it.paused }
            coroutineScope {
                monitors.forEach { monitor -> launch { container.engine.runCheck(monitor.id) } }
            }
            Result.success()
        } catch (e: Exception) {
            Result.success()
        }
    }
}

class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PingvantaApp).container
        runCatching { container.engine.prune() }
        return Result.success()
    }
}

/** WorkManager restores its own jobs after a reboot; this also restarts the live service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as PingvantaApp).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                container.scheduler.syncAll()
            } finally {
                pending.finish()
            }
        }
    }
}
