package com.processlens.feature.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.feature.access.AccessScreen
import com.processlens.feature.applications.AppDetailScreen
import com.processlens.feature.applications.AppListScreen
import com.processlens.feature.battery.BatteryScreen
import com.processlens.feature.capabilities.CapabilitiesScreen
import com.processlens.feature.compare.CompareScreen
import com.processlens.feature.cpu.CpuScreen
import com.processlens.feature.favorites.FavoritesScreen
import com.processlens.feature.investigate.InvestigateScreen
import com.processlens.feature.memory.MemoryScreen
import com.processlens.feature.network.NetworkScreen
import com.processlens.feature.overview.OverviewScreen
import com.processlens.feature.permissions.PermissionsScreen
import com.processlens.feature.processes.ProcessListScreen
import com.processlens.feature.processes.ProcessTreeScreen
import com.processlens.feature.processes.ThreadsScreen
import com.processlens.feature.processdetail.ProcessDetailScreen
import com.processlens.feature.search.SearchScreen
import com.processlens.feature.settings.SettingsScreen
import com.processlens.feature.timeline.TimelineScreen

/**
 * The navigation graph and the shell around it (Sections 33, 45).
 *
 * One `NavHost` with a flat route space rather than nested graphs: the app's
 * navigation is genuinely flat — any screen can lead to a process detail or an app
 * detail — and nested graphs would add back-stack subtleties for no benefit.
 *
 * Transitions are short horizontal slides, and [ProcessLensTheme.motion] scales them
 * to zero when reduced motion is on, in which case screens cross-fade instantly
 * rather than jumping (Section 49).
 */
@Composable
fun ProcessLensNavHost(
    isCompact: Boolean,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val navigateTop: (TopLevelDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            // Single-top with a pop back to the start destination: tapping a bar item
            // must not grow the back stack, and returning to Overview must not
            // require five back presses.
            popUpTo(Routes.OVERVIEW) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (isCompact) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    NavGraph(navController)
                }
                // The bar is hidden on detail screens: they are pushed, not switched
                // between, and keeping a tab bar under a pushed screen invites a user
                // to lose their place in a drill-down.
                if (TopLevelDestination.fromRoute(currentRoute) != null) {
                    ProcessLensBottomBar(
                        current = currentRoute,
                        onNavigate = navigateTop,
                        isRecording = isRecording,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                ProcessLensRail(
                    current = currentRoute,
                    onNavigate = navigateTop,
                    isRecording = isRecording,
                )
                Box(Modifier.weight(1f)) {
                    NavGraph(navController)
                }
            }
        }
    }
}

@Composable
private fun NavGraph(navController: NavHostController) {
    val motion = ProcessLensTheme.motion
    val slideMillis = motion.duration(240)
    val fadeMillis = motion.duration(160)

    NavHost(
        navController = navController,
        startDestination = Routes.OVERVIEW,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(slideMillis),
            ) + fadeIn(tween(fadeMillis))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(slideMillis),
            ) + fadeOut(tween(fadeMillis))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(slideMillis),
            ) + fadeIn(tween(fadeMillis))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(slideMillis),
            ) + fadeOut(tween(fadeMillis))
        },
    ) {
        graph(navController)
    }
}

/**
 * Every route in one place.
 *
 * Screens receive plain lambdas rather than the `NavController` itself, so a screen
 * is testable in isolation and cannot navigate somewhere its caller did not permit.
 */
private fun NavGraphBuilder.graph(nav: NavHostController) {
    val toProcess: (String) -> Unit = { nav.navigate(Routes.processDetail(it)) }
    val toApp: (String) -> Unit = { nav.navigate(Routes.appDetail(it)) }
    val toTimeline: (Long) -> Unit = { nav.navigate(Routes.timeline(it)) }
    val back: () -> Unit = { nav.popBackStack() }

    composable(Routes.OVERVIEW) {
        OverviewScreen(
            onOpenProcesses = { nav.navigate(Routes.PROCESSES) },
            onOpenCpu = { nav.navigate(Routes.CPU) },
            onOpenMemory = { nav.navigate(Routes.MEMORY) },
            onOpenBattery = { nav.navigate(Routes.BATTERY) },
            onOpenNetwork = { nav.navigate(Routes.NETWORK) },
            onOpenAccess = { nav.navigate(Routes.ACCESS) },
            onOpenCapabilities = { nav.navigate(Routes.CAPABILITIES) },
            onOpenApps = { nav.navigate(Routes.APPS) },
            onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            onOpenSearch = { nav.navigate(Routes.SEARCH) },
            onOpenFavorites = { nav.navigate(Routes.FAVORITES) },
            onOpenInvestigate = { nav.navigate(Routes.INVESTIGATE) },
            onOpenTimeline = toTimeline,
            onOpenProcess = toProcess,
            onOpenApp = toApp,
        )
    }

    composable(Routes.PROCESSES) {
        ProcessListScreen(
            onOpenProcess = toProcess,
            onOpenTree = { nav.navigate(Routes.PROCESS_TREE) },
            onOpenSearch = { nav.navigate(Routes.SEARCH) },
        )
    }

    composable(Routes.PROCESS_TREE) {
        ProcessTreeScreen(onBack = back, onOpenProcess = toProcess)
    }

    composable(
        route = Routes.PROCESS_DETAIL,
        arguments = listOf(navArgument(Routes.ARG_PROCESS_ID) { type = NavType.StringType }),
    ) {
        ProcessDetailScreen(
            onBack = back,
            onOpenApp = toApp,
            onOpenThreads = { pid -> nav.navigate(Routes.threads(pid)) },
            onOpenInvestigate = { nav.navigate(Routes.INVESTIGATE) },
        )
    }

    composable(
        route = Routes.THREADS,
        arguments = listOf(navArgument(Routes.ARG_PID) { type = NavType.IntType }),
    ) {
        ThreadsScreen(onBack = back)
    }

    composable(Routes.INVESTIGATE) {
        InvestigateScreen(
            onOpenTimeline = toTimeline,
            onOpenCompare = { nav.navigate(Routes.COMPARE) },
        )
    }

    composable(
        route = Routes.TIMELINE,
        arguments = listOf(navArgument(Routes.ARG_INVESTIGATION_ID) { type = NavType.LongType }),
    ) {
        TimelineScreen(onBack = back, onOpenProcess = toProcess, onOpenApp = toApp)
    }

    composable(Routes.COMPARE) {
        CompareScreen(onBack = back)
    }

    composable(Routes.APPS) {
        AppListScreen(onOpenApp = toApp, onOpenSearch = { nav.navigate(Routes.SEARCH) })
    }

    composable(
        route = Routes.APP_DETAIL,
        arguments = listOf(navArgument(Routes.ARG_PACKAGE) { type = NavType.StringType }),
    ) {
        AppDetailScreen(onBack = back, onOpenProcess = toProcess)
    }

    composable(Routes.CPU) { CpuScreen(onBack = back, onOpenProcesses = { nav.navigate(Routes.PROCESSES) }) }
    composable(Routes.MEMORY) { MemoryScreen(onBack = back, onOpenProcesses = { nav.navigate(Routes.PROCESSES) }) }
    composable(Routes.BATTERY) { BatteryScreen(onBack = back, onOpenApp = toApp) }
    composable(Routes.NETWORK) { NetworkScreen(onBack = back, onOpenApp = toApp) }
    composable(Routes.PERMISSIONS) { PermissionsScreen(onBack = back, onOpenApp = toApp) }
    composable(Routes.CAPABILITIES) { CapabilitiesScreen(onBack = back, onOpenAccess = { nav.navigate(Routes.ACCESS) }) }
    composable(Routes.ACCESS) { AccessScreen(onBack = back) }
    composable(Routes.FAVORITES) {
        FavoritesScreen(onBack = back, onOpenApp = toApp, onOpenProcess = toProcess, onOpenTimeline = toTimeline)
    }
    composable(Routes.SEARCH) {
        SearchScreen(onBack = back, onOpenApp = toApp, onOpenProcess = toProcess, onOpenTimeline = toTimeline)
    }

    composable(Routes.SETTINGS) {
        SettingsScreen(
            onOpenAccess = { nav.navigate(Routes.ACCESS) },
            onOpenCapabilities = { nav.navigate(Routes.CAPABILITIES) },
            onOpenFavorites = { nav.navigate(Routes.FAVORITES) },
        )
    }
}
