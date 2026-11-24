package io.github.clement83.sillage.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.clement83.sillage.R
import io.github.clement83.sillage.ui.history.HistoryScreen
import io.github.clement83.sillage.ui.history.RecordingDetailScreen
import io.github.clement83.sillage.ui.map.MapScreen
import io.github.clement83.sillage.ui.tracks.TrackDetailScreen
import io.github.clement83.sillage.ui.tracks.TracksScreen
import io.github.clement83.sillage.importer.ImportException
import io.github.clement83.sillage.importer.ImportOutcome
import io.github.clement83.sillage.ui.settings.SettingsScreen
import io.github.clement83.sillage.singletrack.SingletrackLink
import io.github.clement83.sillage.ui.outings.AccountScreen
import io.github.clement83.sillage.ui.outings.OpenLinkScreen
import io.github.clement83.sillage.ui.outings.OutingScreen
import io.github.clement83.sillage.ui.outings.OutingsScreen
import io.github.clement83.sillage.ui.scan.ScanScreen
import android.net.Uri

private enum class TopLevel(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Map("map", R.string.nav_map, Icons.Outlined.Map),
    Outings("outings", R.string.nav_outings, Icons.Outlined.Groups),
    Tracks("tracks", R.string.nav_tracks, Icons.Outlined.Route),
    History("history", R.string.nav_history, Icons.Outlined.History),
    Settings("settings", R.string.nav_settings, Icons.Outlined.Settings),
}

@Composable
fun SillageNavHost() {
    val recorder = appContainer().recorder
    val recording by recorder.state.collectAsStateWithLifecycle()
    val locked by rememberKeyguardLocked()
    // Shown over the lock screen: the recording map only; everything else needs unlocking.
    if (locked && recording.isActive) {
        MapScreen(onRecordingFinished = {}, lockedMode = true)
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val library = appContainer().tracks
    val lastImport by library.lastImport.collectAsStateWithLifecycle()
    val incomingLink by appContainer().incomingLink.collectAsStateWithLifecycle()
    val linkHolder = appContainer().incomingLink

    // A singletrack link scanned in the app or opened from outside: an outing to show, or a pairing QR.
    LaunchedEffect(incomingLink) {
        val link = incomingLink ?: return@LaunchedEffect
        linkHolder.value = null
        when (link) {
            is SingletrackLink.Pair -> nav.navigate(accountRoute(link.server, link.code)) {
                popUpTo(ACCOUNT_ROUTE) { inclusive = true }
            }
            else -> nav.navigate(openRoute(link))
        }
    }

    // A file opened with or shared to Sillage: open the imported track, or say why it failed.
    LaunchedEffect(lastImport) {
        val outcome = lastImport ?: return@LaunchedEffect
        library.consumeImport()
        when (outcome) {
            is ImportOutcome.Imported -> {
                nav.navigate(trackRoute(outcome.trackId))
                snackbar.showSnackbar(context.getString(R.string.import_done))
            }
            is ImportOutcome.Failed -> snackbar.showSnackbar(
                context.getString(
                    when (outcome.reason) {
                        ImportException.Reason.TOO_LARGE -> R.string.import_error_too_large
                        ImportException.Reason.NOT_A_TRACK -> R.string.import_error_not_track
                        ImportException.Reason.NO_POINTS -> R.string.import_error_no_points
                        null -> R.string.import_error_read
                    },
                ),
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TopLevel.Map.route, modifier = Modifier.padding(padding)) {
            composable(TopLevel.Map.route) {
                MapScreen(onRecordingFinished = { id -> nav.navigate(recordingRoute(id)) })
            }
            composable(TopLevel.Outings.route) {
                OutingsScreen(
                    onOpen = { id -> nav.navigate(outingRoute(id)) },
                    onScan = { nav.navigate(SCAN_ROUTE) },
                    onAccount = { nav.navigate(accountRoute(null, null)) },
                )
            }
            composable(SCAN_ROUTE) {
                ScanScreen(
                    onLink = { link ->
                        nav.popBackStack()
                        linkHolder.value = link
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                OPEN_ROUTE,
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("server") { type = NavType.StringType },
                    navArgument("value") { type = NavType.StringType },
                ),
            ) { entry ->
                val args = entry.arguments ?: return@composable
                val server = args.getString("server") ?: return@composable
                val value = args.getString("value") ?: return@composable
                val link = when (args.getString("kind")) {
                    "s" -> SingletrackLink.Outing(server, value)
                    "j" -> SingletrackLink.Join(server, value)
                    else -> SingletrackLink.Invite(server, value)
                }
                OpenLinkScreen(
                    link = link,
                    onOpened = { id ->
                        nav.navigate(outingRoute(id)) { popUpTo(OPEN_ROUTE) { inclusive = true } }
                    },
                    onBack = { nav.popBackStack() },
                    onAccount = { nav.navigate(accountRoute(null, null)) },
                )
            }
            composable(
                "outing/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                OutingScreen(
                    outingId = entry.arguments?.getLong("id") ?: return@composable,
                    onBack = { nav.popBackStack() },
                    onFollow = {
                        nav.navigate(TopLevel.Map.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                ACCOUNT_ROUTE,
                arguments = listOf(
                    navArgument("server") { type = NavType.StringType; nullable = true },
                    navArgument("code") { type = NavType.StringType; nullable = true },
                ),
            ) { entry ->
                val pairedMessage = stringResource(R.string.account_paired_done)
                AccountScreen(
                    qrServer = entry.arguments?.getString("server"),
                    qrCode = entry.arguments?.getString("code"),
                    onBack = { nav.popBackStack() },
                    onScan = { nav.navigate(SCAN_ROUTE) },
                    onPaired = {
                        nav.navigate(TopLevel.Outings.route) {
                            popUpTo(nav.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                        scope.launch { snackbar.showSnackbar(pairedMessage) }
                    },
                )
            }
            composable(TopLevel.Tracks.route) {
                TracksScreen(onOpen = { id -> nav.navigate(trackRoute(id)) })
            }
            composable(
                "track/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                TrackDetailScreen(
                    trackId = entry.arguments?.getLong("id") ?: return@composable,
                    onBack = { nav.popBackStack() },
                    onFollow = {
                        nav.navigate(TopLevel.Map.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(TopLevel.History.route) {
                HistoryScreen(onOpen = { id -> nav.navigate(recordingRoute(id)) })
            }
            composable(
                "recording/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                RecordingDetailScreen(
                    recordingId = entry.arguments?.getLong("id") ?: return@composable,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(TopLevel.Settings.route) {
                SettingsScreen(onAccount = { nav.navigate(accountRoute(null, null)) })
            }
        }
    }
}

private fun recordingRoute(id: Long) = "recording/$id"

private fun trackRoute(id: Long) = "track/$id"

private fun outingRoute(id: Long) = "outing/$id"

private const val SCAN_ROUTE = "scan"
private const val OPEN_ROUTE = "open?kind={kind}&server={server}&value={value}"
private const val ACCOUNT_ROUTE = "account?server={server}&code={code}"

private fun openRoute(link: SingletrackLink): String {
    val (kind, value) = when (link) {
        is SingletrackLink.Outing -> "s" to link.slug
        is SingletrackLink.Invite -> "i" to link.token
        is SingletrackLink.Join -> "j" to link.openToken
        is SingletrackLink.Pair -> error("A pairing link is not an outing")
    }
    return "open?kind=$kind&server=${Uri.encode(link.server)}&value=${Uri.encode(value)}"
}

private fun accountRoute(server: String?, code: String?): String =
    if (server == null || code == null) "account" else "account?server=${Uri.encode(server)}&code=${Uri.encode(code)}"
