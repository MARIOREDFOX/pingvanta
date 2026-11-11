package app.pingvanta.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pingvanta.PingvantaApp
import app.pingvanta.R
import app.pingvanta.ui.screens.DashboardScreen
import app.pingvanta.ui.screens.DetailScreen
import app.pingvanta.ui.screens.EditScreen
import app.pingvanta.ui.screens.HistoryScreen
import app.pingvanta.ui.screens.MonitorsScreen
import app.pingvanta.ui.screens.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val MONITORS = "monitors"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{id}"
    const val EDIT = "edit?id={id}"

    fun detail(id: Long) = "detail/$id"
    fun edit(id: Long?) = if (id == null) "edit" else "edit?id=$id"
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Painter)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Dashboard") { painterResource(R.drawable.ic_dashboard) },
    Tab(Routes.MONITORS, "Monitors") { painterResource(R.drawable.ic_monitors) },
    Tab(Routes.HISTORY, "History") { painterResource(R.drawable.ic_history) },
    Tab(Routes.SETTINGS, "Settings") { rememberVectorPainter(Icons.Filled.Settings) },
)

@Composable
fun AppRoot(openMonitorId: Long? = null, onMonitorOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = route == null || tabs.any { it.route == route }

    // Ask for the notification permission once, on first launch (Android 13+).
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val missing = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (missing) {
            val settings = (context.applicationContext as PingvantaApp).container.settings
            if (!settings.current().notificationPromptShown) {
                settings.setNotificationPromptShown()
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(openMonitorId) {
        if (openMonitorId != null) {
            nav.navigate(Routes.detail(openMonitorId)) { launchSingleTop = true }
            onMonitorOpened()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(visible = topLevel, enter = expandVertically(), exit = shrinkVertically()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon(), contentDescription = null) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("tab-${tab.route}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenMonitor = { nav.navigate(Routes.detail(it)) },
                    onAddMonitor = { nav.navigate(Routes.edit(null)) },
                )
            }
            composable(Routes.MONITORS) {
                MonitorsScreen(
                    onOpenMonitor = { nav.navigate(Routes.detail(it)) },
                    onAddMonitor = { nav.navigate(Routes.edit(null)) },
                    onEditMonitor = { nav.navigate(Routes.edit(it)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenMonitor = { nav.navigate(Routes.detail(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1L
                DetailScreen(
                    monitorId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate(Routes.edit(id)) },
                )
            }
            composable(
                Routes.EDIT,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                val id = entry.arguments?.getLong("id")?.takeIf { it >= 0 }
                EditScreen(monitorId = id, onDone = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
