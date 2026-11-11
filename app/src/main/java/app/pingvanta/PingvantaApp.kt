package app.pingvanta

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.pingvanta.alert.Channels
import app.pingvanta.alert.Notifier
import app.pingvanta.background.MonitorScheduler
import app.pingvanta.data.AppDatabase
import app.pingvanta.data.MonitorRepository
import app.pingvanta.data.MonitorType
import app.pingvanta.data.SettingsStore
import app.pingvanta.monitor.HttpChecker
import app.pingvanta.monitor.MonitorEngine
import app.pingvanta.monitor.NetworkMonitor
import app.pingvanta.monitor.PingChecker
import app.pingvanta.monitor.TcpChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-rolled dependency container; one instance per process. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "pingvanta.db").build()
    val settings = SettingsStore(context, appScope)
    val network = NetworkMonitor(context)
    val notifier = Notifier(context)

    val engine = MonitorEngine(
        db = db,
        checkers = mapOf(
            MonitorType.HTTP to HttpChecker(HttpChecker.defaultClient()),
            MonitorType.TCP to TcpChecker(),
            MonitorType.PING to PingChecker(),
        ),
        connectivity = network,
        alerts = notifier,
    )

    val scheduler = MonitorScheduler(context, db.monitorDao(), settings)
    val repository = MonitorRepository(db, engine, scheduler, appScope)
}

class PingvantaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
        container = AppContainer(this)
        // Re-assert schedules on every process start; unique work names make this idempotent.
        container.appScope.launch {
            runCatching { container.scheduler.syncAll() }
        }
    }
}
