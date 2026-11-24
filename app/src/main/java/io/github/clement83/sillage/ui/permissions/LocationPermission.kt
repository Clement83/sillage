package io.github.clement83.sillage.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

enum class LocationAccess { Precise, Approximate, None }

@Stable
class LocationPermissionState(access: LocationAccess) {
    var access by mutableStateOf(access)
        internal set

    /** True once the user refused and Android will no longer show the system dialog. */
    var permanentlyDenied by mutableStateOf(false)
        internal set

    val granted: Boolean get() = access != LocationAccess.None

    internal var launch: () -> Unit = {}
    internal var openSettings: () -> Unit = {}

    /** Shows the system dialog, or the app settings if Android would not show it anymore. */
    fun request() = if (permanentlyDenied) openSettings() else launch()
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

fun Context.locationAccess(): LocationAccess = when {
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
        LocationAccess.Precise
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
        LocationAccess.Approximate
    else -> LocationAccess.None
}

/** Location permission, re-read on every resume (the user may change it in the system settings). */
@Composable
fun rememberLocationPermissionState(): LocationPermissionState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember { LocationPermissionState(context.locationAccess()) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.access = context.locationAccess()
        state.permanentlyDenied = state.access != LocationAccess.Precise && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    state.launch = { launcher.launch(LOCATION_PERMISSIONS) }
    state.openSettings = { context.openAppSettings() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.access = context.locationAccess()
        if (state.access == LocationAccess.Precise) state.permanentlyDenied = false
    }
    return state
}

fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
