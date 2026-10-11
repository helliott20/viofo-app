package io.github.helliott20.dashlink.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.helliott20.dashlink.session.CameraMode
import androidx.navigation.navArgument
import io.github.helliott20.dashlink.AppContainer
import io.github.helliott20.dashlink.session.Phase
import io.github.helliott20.dashlink.ui.screens.AppSettingsScreen
import io.github.helliott20.dashlink.ui.screens.CameraScreen
import io.github.helliott20.dashlink.ui.screens.DownloadsScreen
import io.github.helliott20.dashlink.ui.screens.HomeScreen
import io.github.helliott20.dashlink.ui.screens.LibraryScreen
import io.github.helliott20.dashlink.ui.screens.LocalPlayerScreen
import io.github.helliott20.dashlink.ui.screens.RemotePlayerScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }

object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val REMOTE = "remote/{path}"
    const val LOCAL = "local/{uri}"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val APP_SETTINGS = "app_settings"
    fun remote(fpath: String) = "remote/" + Uri.encode(fpath)
    fun local(uri: Uri) = "local/" + Uri.encode(uri.toString())
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val container = LocalContainer.current
    val state by container.session.state.collectAsStateWithLifecycle()

    // Leaving the camera screens while it's in playback mode: put it back to recording.
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    LaunchedEffect(route, state.mode) {
        if (route != null && route != Routes.CAMERA && route != Routes.REMOTE && state.mode == CameraMode.Playback && state.connected) {
            container.session.enterLive()
        }
    }

    // Drop back to home if the connection is lost for good while on a camera screen.
    LaunchedEffect(state.phase) {
        val route = nav.currentDestination?.route
        if ((state.phase == Phase.Disconnected) && (route == Routes.CAMERA || route == Routes.REMOTE)) {
            nav.popBackStack(Routes.HOME, inclusive = false)
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                openCamera = { nav.navigate(Routes.CAMERA) { launchSingleTop = true } },
                openLibrary = { nav.navigate(Routes.LIBRARY) },
                openDownloads = { nav.navigate(Routes.DOWNLOADS) },
                openSettings = { nav.navigate(Routes.APP_SETTINGS) },
            )
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                back = { nav.popBackStack() },
                openRemote = { nav.navigate(Routes.remote(it.fpath)) },
                openDownloads = { nav.navigate(Routes.DOWNLOADS) },
            )
        }
        composable(Routes.REMOTE, arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
            RemotePlayerScreen(fpath = Uri.decode(entry.arguments?.getString("path").orEmpty()), back = { nav.popBackStack() })
        }
        composable(Routes.LOCAL, arguments = listOf(navArgument("uri") { type = NavType.StringType })) { entry ->
            LocalPlayerScreen(uri = Uri.parse(Uri.decode(entry.arguments?.getString("uri").orEmpty())), back = { nav.popBackStack() })
        }
        composable(Routes.LIBRARY) { LibraryScreen(back = { nav.popBackStack() }, open = { nav.navigate(Routes.local(it)) }) }
        composable(Routes.DOWNLOADS) { DownloadsScreen(back = { nav.popBackStack() }, open = { nav.navigate(Routes.local(it)) }) }
        composable(Routes.APP_SETTINGS) { AppSettingsScreen(back = { nav.popBackStack() }) }
    }
}
