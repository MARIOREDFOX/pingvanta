package app.pingvanta.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultIntervalSeconds: Int = 300,
    val defaultTimeoutSeconds: Int = 15,
    val defaultAlarmOnDown: Boolean = false,
    val defaultRepeatAlarm: Boolean = false,
    /** Alarm sound URI; null means the system default alarm sound. */
    val alarmSoundUri: String? = null,
    val vibrate: Boolean = true,
    /** Keep a foreground service running for monitors with intervals under 15 minutes. */
    val liveMonitoring: Boolean = true,
    val notificationPromptShown: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val interval = intPreferencesKey("default_interval")
        val timeout = intPreferencesKey("default_timeout")
        val alarmOnDown = booleanPreferencesKey("default_alarm_on_down")
        val repeatAlarm = booleanPreferencesKey("default_repeat_alarm")
        val alarmSound = stringPreferencesKey("alarm_sound")
        val vibrate = booleanPreferencesKey("vibrate")
        val live = booleanPreferencesKey("live_monitoring")
        val promptShown = booleanPreferencesKey("notification_prompt_shown")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            defaultIntervalSeconds = p[Keys.interval] ?: 300,
            defaultTimeoutSeconds = p[Keys.timeout] ?: 15,
            defaultAlarmOnDown = p[Keys.alarmOnDown] ?: false,
            defaultRepeatAlarm = p[Keys.repeatAlarm] ?: false,
            alarmSoundUri = p[Keys.alarmSound]?.takeIf { it.isNotBlank() },
            vibrate = p[Keys.vibrate] ?: true,
            liveMonitoring = p[Keys.live] ?: true,
            notificationPromptShown = p[Keys.promptShown] ?: false,
        )
    }

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }
    suspend fun setDefaultInterval(seconds: Int) = edit { it[Keys.interval] = seconds }
    suspend fun setDefaultTimeout(seconds: Int) = edit { it[Keys.timeout] = seconds }
    suspend fun setDefaultAlarmOnDown(value: Boolean) = edit { it[Keys.alarmOnDown] = value }
    suspend fun setDefaultRepeatAlarm(value: Boolean) = edit { it[Keys.repeatAlarm] = value }
    suspend fun setAlarmSound(uri: String?) = edit { it[Keys.alarmSound] = uri ?: "" }
    suspend fun setVibrate(value: Boolean) = edit { it[Keys.vibrate] = value }
    suspend fun setLiveMonitoring(value: Boolean) = edit { it[Keys.live] = value }
    suspend fun setNotificationPromptShown() = edit { it[Keys.promptShown] = true }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
