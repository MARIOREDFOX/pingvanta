package app.pingvanta

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.pingvanta.ui.AppRoot
import app.pingvanta.ui.theme.PingvantaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Monitor to open, set when the activity is launched from a notification. */
    private val pendingMonitorId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as PingvantaApp).container
        if (savedInstanceState == null) pendingMonitorId.value = monitorIdFrom(intent)

        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            PingvantaTheme(settings.theme) {
                AppRoot(
                    openMonitorId = pendingMonitorId.value,
                    onMonitorOpened = { pendingMonitorId.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The app is now in the foreground, where Android allows the live service to start.
        val container = (application as PingvantaApp).container
        lifecycleScope.launch { runCatching { container.scheduler.syncLiveService() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        monitorIdFrom(intent)?.let { pendingMonitorId.value = it }
    }

    private fun monitorIdFrom(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_MONITOR_ID, -1L)?.takeIf { it >= 0 }

    companion object {
        const val EXTRA_MONITOR_ID = "monitorId"
    }
}
