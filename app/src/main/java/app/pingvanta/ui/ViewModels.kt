package app.pingvanta.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pingvanta.AppContainer
import app.pingvanta.PingvantaApp
import app.pingvanta.alert.AlarmLauncher
import app.pingvanta.data.AppSettings
import app.pingvanta.data.Backup
import app.pingvanta.data.CheckEntity
import app.pingvanta.data.DayBucket
import app.pingvanta.data.EventWithMonitor
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorType
import app.pingvanta.data.ThemeMode
import app.pingvanta.data.UptimeCount
import app.pingvanta.monitor.RunResult
import app.pingvanta.monitor.StatusCodeSpec
import app.pingvanta.monitor.Targets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

const val DAY_MS = 24L * 60 * 60 * 1000

/** Creates a ViewModel with access to the app's dependency container. */
@Composable
fun <VM : ViewModel> appViewModel(type: Class<VM>, key: String? = null, build: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as PingvantaApp).container
    val factory = remember(key) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = build(container) as T
        }
    }
    return viewModel(modelClass = type, key = key, factory = factory)
}

/** The last [days] local calendar days, oldest first, with null where no checks were recorded. */
fun fillDays(buckets: List<DayBucket>, now: Long, offsetMs: Long, days: Int = 30): List<DayBucket?> {
    val today = (now + offsetMs) / DAY_MS
    val byDay = buckets.associateBy { it.day }
    return (today - days + 1..today).map { byDay[it] }
}

fun dailySince(now: Long, offsetMs: Long, days: Int = 30): Long {
    val today = (now + offsetMs) / DAY_MS
    return (today - days + 1) * DAY_MS - offsetMs
}

// ---------------------------------------------------------------------------------------------
// Dashboard + Monitors
// ---------------------------------------------------------------------------------------------

data class MonitorRow(val monitor: MonitorEntity, val uptimePercent: Double?)

data class HomeState(
    val loading: Boolean = true,
    val rows: List<MonitorRow> = emptyList(),
    val overallUptime: Double? = null,
    val recentChanges: Int = 0,
    val activity: List<EventWithMonitor> = emptyList(),
    val online: Boolean = true,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val now = System.currentTimeMillis()
    private val checking = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = checking.asStateFlow()

    val state: StateFlow<HomeState> = combine(
        container.db.monitorDao().observeAll(),
        container.db.checkDao().observeUptimeByMonitor(now - 30 * DAY_MS),
        container.db.eventDao().observeEvents(null, 12),
        container.db.eventDao().observeChangedMonitorCount(now - DAY_MS),
        container.network.online,
    ) { monitors, uptimes, events, changed, online ->
        val byId = uptimes.associateBy { it.monitorId }
        val rows = monitors.map { m ->
            val u = byId[m.id]
            MonitorRow(m, if (u == null || u.total == 0) null else u.up * 100.0 / u.total)
        }
        val up = uptimes.sumOf { it.up }
        val total = uptimes.sumOf { it.total }
        HomeState(
            loading = false,
            rows = rows,
            overallUptime = if (total == 0) null else up * 100.0 / total,
            recentChanges = changed,
            activity = events,
            online = online,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun checkAll() {
        container.repository.checkAllNow()
        viewModelScope.launch {
            checking.value = true
            kotlinx.coroutines.delay(1_200)
            checking.value = false
        }
    }

    fun setPaused(id: Long, paused: Boolean) {
        viewModelScope.launch { container.repository.setPaused(id, paused) }
    }

    fun checkNow(id: Long) {
        viewModelScope.launch { runCatching { container.repository.checkNow(id) } }
    }

    fun delete(id: Long) {
        viewModelScope.launch { container.repository.delete(id) }
    }
}

// ---------------------------------------------------------------------------------------------
// Monitor detail
// ---------------------------------------------------------------------------------------------

data class DetailState(
    val loading: Boolean = true,
    val monitor: MonitorEntity? = null,
    val checks: List<CheckEntity> = emptyList(),
    val uptime24h: UptimeCount = UptimeCount(0, 0),
    val uptime7d: UptimeCount = UptimeCount(0, 0),
    val uptime30d: UptimeCount = UptimeCount(0, 0),
    val daily: List<DayBucket?> = emptyList(),
    val events: List<EventWithMonitor> = emptyList(),
    val loadedAt: Long = 0,
)

class DetailViewModel(private val container: AppContainer, private val monitorId: Long) : ViewModel() {
    private val now = System.currentTimeMillis()
    private val offset = TimeZone.getDefault().getOffset(now).toLong()
    private val checkDao = container.db.checkDao()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<DetailState> = combine(
        container.db.monitorDao().observe(monitorId),
        checkDao.observeChecks(monitorId, now - DAY_MS),
        combine(
            checkDao.observeUptime(monitorId, now - DAY_MS),
            checkDao.observeUptime(monitorId, now - 7 * DAY_MS),
            checkDao.observeUptime(monitorId, now - 30 * DAY_MS),
        ) { a, b, c -> Triple(a, b, c) },
        checkDao.observeDaily(monitorId, dailySince(now, offset), offset),
        container.db.eventDao().observeEvents(monitorId, 200),
    ) { monitor, checks, uptimes, daily, events ->
        DetailState(
            loading = false,
            monitor = monitor,
            checks = checks,
            uptime24h = uptimes.first,
            uptime7d = uptimes.second,
            uptime30d = uptimes.third,
            daily = fillDays(daily, System.currentTimeMillis(), offset),
            events = events,
            loadedAt = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    fun checkNow() {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            val result = runCatching { container.repository.checkNow(monitorId) }.getOrNull()
            _checking.value = false
            _message.value = when (result) {
                is RunResult.Checked ->
                    if (result.outcome.up) "Check passed in ${result.outcome.responseMs ?: 0} ms"
                    else "Check failed: ${result.outcome.error ?: "unknown reason"}"
                is RunResult.Skipped ->
                    if (result.reason == RunResult.OFFLINE) "Your phone is offline, so the check was skipped"
                    else "Check skipped: ${result.reason}"
                null -> "Check could not be run"
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { container.repository.setPaused(monitorId, paused) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            container.repository.delete(monitorId)
            onDeleted()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Add / edit
// ---------------------------------------------------------------------------------------------

data class EditForm(
    val loaded: Boolean = false,
    val id: Long = 0,
    val name: String = "",
    val target: String = "",
    val type: MonitorType = MonitorType.HTTP,
    val port: String = "443",
    val intervalSeconds: Int = 300,
    val timeoutSeconds: Int = 15,
    val expectedCodes: String = StatusCodeSpec.DEFAULT,
    val notifyOnUp: Boolean = true,
    val notifyOnDown: Boolean = true,
    val alarmOnUp: Boolean = false,
    val alarmOnDown: Boolean = false,
    val repeatAlarm: Boolean = false,
    val targetError: String? = null,
    val portError: String? = null,
    val codesError: String? = null,
    val saving: Boolean = false,
)

class EditViewModel(private val container: AppContainer, private val monitorId: Long?) : ViewModel() {
    private val _form = MutableStateFlow(EditForm())
    val form: StateFlow<EditForm> = _form.asStateFlow()

    val settings: StateFlow<AppSettings> = container.settings.state
    private var original: MonitorEntity? = null

    init {
        viewModelScope.launch {
            val existing = monitorId?.let { container.db.monitorDao().get(it) }
            original = existing
            if (existing != null) {
                _form.value = EditForm(
                    loaded = true,
                    id = existing.id,
                    name = existing.name,
                    target = existing.target,
                    type = existing.type,
                    port = existing.port.toString(),
                    intervalSeconds = existing.intervalSeconds,
                    timeoutSeconds = existing.timeoutSeconds,
                    expectedCodes = existing.expectedCodes,
                    notifyOnUp = existing.notifyOnUp,
                    notifyOnDown = existing.notifyOnDown,
                    alarmOnUp = existing.alarmOnUp,
                    alarmOnDown = existing.alarmOnDown,
                    repeatAlarm = existing.repeatAlarm,
                )
            } else {
                val defaults = container.settings.current()
                _form.value = EditForm(
                    loaded = true,
                    intervalSeconds = defaults.defaultIntervalSeconds,
                    timeoutSeconds = defaults.defaultTimeoutSeconds,
                    alarmOnDown = defaults.defaultAlarmOnDown,
                    repeatAlarm = defaults.defaultRepeatAlarm,
                )
            }
        }
    }

    fun edit(transform: (EditForm) -> EditForm) {
        _form.update { transform(it).copy(targetError = null, portError = null, codesError = null) }
    }

    fun setType(type: MonitorType) {
        _form.update { current ->
            val port = when {
                type == MonitorType.TCP && current.type != MonitorType.TCP ->
                    Targets.embeddedPort(current.target)?.toString() ?: current.port
                else -> current.port
            }
            current.copy(type = type, port = port, targetError = null, portError = null, codesError = null)
        }
    }

    fun save(onSaved: () -> Unit) {
        val f = _form.value
        if (f.saving) return
        val target = f.target.trim()
        var targetError: String? = null
        var portError: String? = null
        var codesError: String? = null
        var port = f.port.trim().toIntOrNull() ?: 0

        when (f.type) {
            MonitorType.HTTP -> {
                val url = Targets.httpUrl(target)
                if (url == null) targetError = "Enter a valid URL, for example https://example.com"
                if (!StatusCodeSpec.isValid(f.expectedCodes)) {
                    codesError = "Use codes or ranges between 100 and 599, e.g. 200-299,301"
                }
                port = url?.port ?: 443
            }
            MonitorType.TCP -> {
                if (Targets.host(target).isEmpty()) targetError = "Enter a host name or IP address"
                if (port !in 1..65535) portError = "Port must be between 1 and 65535"
            }
            MonitorType.PING -> {
                if (Targets.host(target).isEmpty()) targetError = "Enter a host name or IP address"
                port = 0
            }
        }
        if (targetError != null || portError != null || codesError != null) {
            _form.update { it.copy(targetError = targetError, portError = portError, codesError = codesError) }
            return
        }

        val normalizedTarget = when (f.type) {
            MonitorType.HTTP -> if (target.contains("://")) target else "https://$target"
            else -> Targets.host(target)
        }
        val name = f.name.trim().ifEmpty { Targets.host(normalizedTarget).removePrefix("www.") }
        val base = original ?: MonitorEntity(name = name, target = normalizedTarget)
        val entity = base.copy(
            name = name,
            target = normalizedTarget,
            type = f.type,
            port = port,
            intervalSeconds = f.intervalSeconds,
            timeoutSeconds = f.timeoutSeconds,
            expectedCodes = if (f.type == MonitorType.HTTP) f.expectedCodes.trim() else StatusCodeSpec.DEFAULT,
            notifyOnUp = f.notifyOnUp,
            notifyOnDown = f.notifyOnDown,
            alarmOnUp = f.alarmOnUp,
            alarmOnDown = f.alarmOnDown,
            repeatAlarm = f.repeatAlarm,
        )
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            if (original == null) container.repository.add(entity) else container.repository.update(entity)
            onSaved()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

data class HistoryState(
    val loading: Boolean = true,
    val monitors: List<MonitorEntity> = emptyList(),
    val filterId: Long? = null,
    val events: List<EventWithMonitor> = emptyList(),
    val uptimeDay: UptimeCount = UptimeCount(0, 0),
    val uptimeWeek: UptimeCount = UptimeCount(0, 0),
    val uptimeMonth: UptimeCount = UptimeCount(0, 0),
    val daily: List<DayBucket?> = emptyList(),
    val loadedAt: Long = 0,
)

class HistoryViewModel(container: AppContainer) : ViewModel() {
    private val now = System.currentTimeMillis()
    private val offset = TimeZone.getDefault().getOffset(now).toLong()
    private val filter = MutableStateFlow<Long?>(null)
    private val checkDao = container.db.checkDao()
    private val eventDao = container.db.eventDao()

    private data class Stats(
        val events: List<EventWithMonitor>,
        val day: UptimeCount,
        val week: UptimeCount,
        val month: UptimeCount,
        val daily: List<DayBucket>,
    )

    val state: StateFlow<HistoryState> = combine(
        container.db.monitorDao().observeAll(),
        filter,
        filter.flatMapLatest { id ->
            combine(
                eventDao.observeEvents(id, 500),
                checkDao.observeUptime(id, now - DAY_MS),
                checkDao.observeUptime(id, now - 7 * DAY_MS),
                checkDao.observeUptime(id, now - 30 * DAY_MS),
                checkDao.observeDaily(id, dailySince(now, offset), offset),
            ) { events, day, week, month, daily -> Stats(events, day, week, month, daily) }
        },
    ) { monitors, filterId, stats ->
        HistoryState(
            loading = false,
            monitors = monitors,
            filterId = filterId,
            events = stats.events,
            uptimeDay = stats.day,
            uptimeWeek = stats.week,
            uptimeMonth = stats.month,
            daily = fillDays(stats.daily, System.currentTimeMillis(), offset),
            loadedAt = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun setFilter(id: Long?) {
        filter.value = id
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.state

    val monitors: StateFlow<List<MonitorEntity>> = container.db.monitorDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settings.setTheme(mode) }
    fun setDefaultInterval(seconds: Int) = viewModelScope.launch { container.settings.setDefaultInterval(seconds) }
    fun setDefaultTimeout(seconds: Int) = viewModelScope.launch { container.settings.setDefaultTimeout(seconds) }
    fun setDefaultAlarm(value: Boolean) = viewModelScope.launch { container.settings.setDefaultAlarmOnDown(value) }
    fun setDefaultRepeat(value: Boolean) = viewModelScope.launch { container.settings.setDefaultRepeatAlarm(value) }
    fun setVibrate(value: Boolean) = viewModelScope.launch { container.settings.setVibrate(value) }
    fun setAlarmSound(uri: String?) = viewModelScope.launch { container.settings.setAlarmSound(uri) }

    fun setLiveMonitoring(value: Boolean) = viewModelScope.launch {
        container.settings.setLiveMonitoring(value)
        container.scheduler.syncLiveService()
    }

    fun testNotification(up: Boolean) {
        container.notifier.sendTest(up)
    }

    fun testAlarm(context: android.content.Context) {
        AlarmLauncher.trigger(
            context,
            "Test alarm",
            "This is how a Pingvanta alarm sounds.\nTap Acknowledge to stop it.",
            null,
            repeat = false,
        )
    }

    fun exportMonitors(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        _message.value = runCatching {
            withContext(Dispatchers.IO) {
                val monitors = container.db.monitorDao().getAll()
                val json = Backup.monitorsToJson(monitors)
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("Could not open the file")
                monitors.size
            }
        }.fold(
            onSuccess = { "Exported $it monitor${if (it == 1) "" else "s"}" },
            onFailure = { "Export failed: ${it.message}" },
        )
    }

    fun importMonitors(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        _message.value = runCatching {
            withContext(Dispatchers.IO) {
                val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: error("Could not open the file")
                val parsed = Backup.monitorsFromJson(text)
                val added = container.repository.importAll(parsed)
                added to parsed.size
            }
        }.fold(
            onSuccess = { (added, total) ->
                if (total == 0) "No monitors found in that file"
                else "Imported $added of $total monitors" + if (added < total) " (duplicates skipped)" else ""
            },
            onFailure = { "Import failed: ${it.message}" },
        )
    }

    fun exportHistory(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        _message.value = runCatching {
            withContext(Dispatchers.IO) {
                val events = container.db.eventDao().exportRows()
                val checks = container.db.checkDao().exportRows()
                resolver.openOutputStream(uri)?.bufferedWriter()?.use { Backup.writeHistoryCsv(it, events, checks) }
                    ?: error("Could not open the file")
                events.size + checks.size
            }
        }.fold(
            onSuccess = { "Exported $it history rows" },
            onFailure = { "Export failed: ${it.message}" },
        )
    }
}
