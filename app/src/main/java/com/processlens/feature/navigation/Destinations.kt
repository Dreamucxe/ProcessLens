package com.processlens.feature.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Routes (Sections 33, 45).
 *
 * Held as constants with builder functions rather than raw strings scattered through
 * the screens: a mistyped route in Compose Navigation fails at runtime with an
 * unhelpful message, and process identifiers in particular contain characters
 * (colons in `com.app:remote`, slashes in some component names) that must be encoded
 * before they enter a path segment.
 */
object Routes {
    const val OVERVIEW = "overview"
    const val PROCESSES = "processes"
    const val PROCESS_TREE = "process_tree"
    const val INVESTIGATE = "investigate"
    const val APPS = "apps"
    const val SETTINGS = "settings"

    const val BATTERY = "battery"
    const val NETWORK = "network"

    /**
     * Which applications hold which permissions (Section 21).
     *
     * Distinct from [CAPABILITIES], which is about what *this device* exposes to
     * ProcessLens. The two were briefly the same route, and the conflation was
     * misleading in both directions.
     */
    const val PERMISSIONS = "permissions"
    const val CAPABILITIES = "capabilities"
    const val FAVORITES = "favorites"
    const val ACCESS = "access"
    const val SEARCH = "search"
    const val COMPARE = "compare"
    const val CPU = "cpu"
    const val MEMORY = "memory"

    private const val PROCESS_BASE = "process"
    private const val APP_BASE = "app"
    private const val TIMELINE_BASE = "timeline"
    private const val THREADS_BASE = "threads"

    const val ARG_PROCESS_ID = "processId"
    const val ARG_PACKAGE = "packageName"
    const val ARG_INVESTIGATION_ID = "investigationId"
    const val ARG_PID = "pid"

    const val PROCESS_DETAIL = "$PROCESS_BASE/{$ARG_PROCESS_ID}"
    const val APP_DETAIL = "$APP_BASE/{$ARG_PACKAGE}"
    const val TIMELINE = "$TIMELINE_BASE/{$ARG_INVESTIGATION_ID}"
    const val THREADS = "$THREADS_BASE/{$ARG_PID}"

    fun processDetail(id: String): String = "$PROCESS_BASE/${encode(id)}"
    fun appDetail(packageName: String): String = "$APP_BASE/${encode(packageName)}"
    fun timeline(investigationId: Long): String = "$TIMELINE_BASE/$investigationId"
    fun threads(pid: Int): String = "$THREADS_BASE/$pid"

    /**
     * Process ids can contain `:` (private process suffixes) and `/`. Both break a
     * navigation path segment, so they are percent-encoded on the way in and decoded
     * in the view model.
     */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (t: IllegalArgumentException) {
        value
    }
}

/**
 * The bottom bar (Section 33).
 *
 * Five items with **Investigate** in the centre, drawn larger and in the accent
 * colour, because Section 0.1 specifies exactly that emphasis — the recording action
 * is the app's reason to exist and should be reachable without a menu.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val isCentre: Boolean = false,
) {
    OVERVIEW(Routes.OVERVIEW, "Overview", Icons.Outlined.Dashboard),
    PROCESSES(Routes.PROCESSES, "Processes", Icons.AutoMirrored.Outlined.List),
    INVESTIGATE(Routes.INVESTIGATE, "Investigate", Icons.Outlined.CenterFocusStrong, isCentre = true),
    APPS(Routes.APPS, "Apps", Icons.Outlined.Apps),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Outlined.Tune),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
