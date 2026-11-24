package io.github.clement83.sillage

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.clement83.sillage.singletrack.SingletrackLink
import io.github.clement83.sillage.ui.SillageNavHost
import io.github.clement83.sillage.ui.theme.SillageTheme

/** AppCompatActivity (not ComponentActivity) so the per-app language works below Android 13. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SillageTheme {
                SillageNavHost()
            }
        }
        // Over the lock screen only while a recording is open: otherwise the lock screen is normal.
        val container = (application as SillageApp).container
        lifecycleScope.launch {
            combine(
                container.recorder.state.map { it.isActive }.distinctUntilChanged(),
                container.settings.lockScreenMap,
            ) { recording, enabled -> recording && enabled }
                .distinctUntilChanged()
                .collect(::showOverLockScreen)
        }
        if (savedInstanceState == null) importFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importFrom(intent)
    }

    /** A GPX / KML / KMZ opened with Sillage or shared to it: import it; the UI then opens it. */
    private fun importFrom(intent: Intent?) {
        val container = (application as SillageApp).container
        // A singletrack link (ride, invitation, pairing) rather than a file.
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme in setOf("http", "https", "singletrack")) {
            SingletrackLink.parse(data.toString())?.let { container.incomingLink.value = it }
            return
        }
        // A link shared as text (browser, messaging app): "Share › Sillage" on a singletrack page.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(SingletrackLink::find)?.let { container.incomingLink.value = it }
            return
        }
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        container.appScope.launch { container.tracks.import(uri) }
    }

    private fun showOverLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
        } else {
            @Suppress("DEPRECATION")
            if (show) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
    }
}
