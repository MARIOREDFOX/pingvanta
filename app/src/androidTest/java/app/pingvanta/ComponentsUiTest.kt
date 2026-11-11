package app.pingvanta

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.ThemeMode
import app.pingvanta.ui.components.MonitorCard
import app.pingvanta.ui.screens.DeleteMonitorDialog
import app.pingvanta.ui.theme.PingvantaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun monitorCardShowsStatusAndMetrics() {
        val now = System.currentTimeMillis()
        var clicks = 0
        rule.setContent {
            PingvantaTheme(ThemeMode.DARK) {
                MonitorCard(
                    monitor = MonitorEntity(
                        id = 1,
                        name = "Google",
                        target = "https://www.google.com",
                        intervalSeconds = 60,
                        status = MonitorStatus.UP,
                        lastResponseMs = 120,
                        lastCheckedAt = now,
                    ),
                    uptimePercent = 99.5,
                    now = now,
                    onClick = { clicks++ },
                )
            }
        }
        rule.onNodeWithText("Google").assertIsDisplayed()
        rule.onNodeWithText("https://www.google.com").assertIsDisplayed()
        rule.onNodeWithText("UP").assertIsDisplayed()
        rule.onNodeWithText("120 ms").assertIsDisplayed()
        rule.onNodeWithText("99.50%").assertIsDisplayed()
        rule.onNodeWithText("1 min").assertIsDisplayed()
        rule.onNodeWithText("Google").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun downMonitorShowsFailureReason() {
        rule.setContent {
            PingvantaTheme(ThemeMode.LIGHT) {
                MonitorCard(
                    monitor = MonitorEntity(
                        id = 2,
                        name = "Example",
                        target = "https://example.com",
                        status = MonitorStatus.DOWN,
                        lastError = "Connection timeout",
                    ),
                    uptimePercent = null,
                    now = System.currentTimeMillis(),
                    onClick = {},
                )
            }
        }
        rule.onNodeWithText("DOWN").assertIsDisplayed()
        rule.onNodeWithText("Connection timeout").assertIsDisplayed()
    }

    @Test
    fun deleteDialogConfirms() {
        var confirmed = false
        rule.setContent {
            PingvantaTheme(ThemeMode.LIGHT) {
                DeleteMonitorDialog(name = "Google", onConfirm = { confirmed = true }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Delete Google?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(true, confirmed)
    }
}
