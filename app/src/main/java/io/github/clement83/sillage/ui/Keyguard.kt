package io.github.clement83.sillage.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** True while the activity is shown over the lock screen (the phone is still locked). */
@Composable
fun rememberKeyguardLocked(): State<Boolean> {
    val context = LocalContext.current
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    val locked = remember { mutableStateOf(keyguard.isKeyguardLocked) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { locked.value = keyguard.isKeyguardLocked }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                locked.value = keyguard.isKeyguardLocked
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return locked
}

/** Asks Android to unlock (PIN, fingerprint…); the app then shows its full UI again. */
@Composable
fun rememberUnlockRequest(): () -> Unit {
    val activity = LocalActivity.current
    val keyguard = remember { activity?.getSystemService(KeyguardManager::class.java) }
    return { if (activity != null) keyguard?.requestDismissKeyguard(activity, null) }
}
