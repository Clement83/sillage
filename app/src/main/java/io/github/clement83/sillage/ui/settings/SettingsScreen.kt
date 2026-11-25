package io.github.clement83.sillage.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.clement83.sillage.BuildConfig
import io.github.clement83.sillage.R
import io.github.clement83.sillage.SillageApp
import io.github.clement83.sillage.ui.Format
import io.github.clement83.sillage.ui.appContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onAccount: () -> Unit, onOfflineMaps: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as SillageApp).container.settings }
    val scope = rememberCoroutineScope()

    var serverInput by rememberSaveable { mutableStateOf<String?>(null) }
    var serverError by rememberSaveable { mutableStateOf(false) }
    var serverSaved by rememberSaveable { mutableStateOf(false) }
    // Follows the stored server (pairing sets it while this screen sits in the back stack); an edit in
    // progress survives a rotation because the stored value it started from has not changed.
    var serverShown by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        settings.serverUrl.collect { stored ->
            val value = stored.orEmpty()
            if (serverInput == null || value != serverShown) {
                serverInput = value
                serverError = false
            }
            serverShown = value
        }
    }

    fun save(value: String) = scope.launch {
        val stored = settings.setServerUrl(value)
        serverError = stored == null
        serverSaved = stored != null
        if (stored != null) serverInput = stored
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        Section(R.string.settings_server_title) {
            OutlinedTextField(
                value = serverInput.orEmpty(),
                onValueChange = {
                    serverInput = it
                    serverError = false
                    serverSaved = false
                },
                label = { Text(stringResource(R.string.settings_server_label)) },
                singleLine = true,
                isError = serverError,
                supportingText = when {
                    serverError -> ({ Text(stringResource(R.string.settings_server_invalid)) })
                    serverSaved -> ({ Text(stringResource(R.string.settings_server_saved)) })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Button(onClick = { save(serverInput.orEmpty()) }) { Text(stringResource(R.string.settings_save)) }
            }
        }

        HorizontalDivider()
        AccountSection(onAccount)
        OfflineSection(onOfflineMaps)

        HorizontalDivider()
        LockScreenSection()

        HorizontalDivider()
        BatterySection()

        HorizontalDivider()
        LanguageSection()

        HorizontalDivider()
        Section(R.string.settings_about_title) {
            Text(stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The paired singletrack account (#1728): which one, or none; managed on its own screen. */
@Composable
private fun AccountSection(onAccount: () -> Unit) {
    val account by appContainer().account.account.collectAsStateWithLifecycle(initialValue = null)
    Section(R.string.account_title) {
        Text(
            account?.let { stringResource(R.string.account_paired, it.server, Format.dateTime(it.pairedAt)) }
                ?: stringResource(R.string.settings_account_unpaired),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onAccount) { Text(stringResource(R.string.settings_account_manage)) }
        }
    }
}

/** Offline maps (#1729): only with a paired account, or once something is downloaded. */
@Composable
private fun OfflineSection(onOfflineMaps: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val account by container.account.account.collectAsStateWithLifecycle(initialValue = null)
    val zones by container.offline.zones.collectAsStateWithLifecycle(initialValue = emptyList())
    if (account == null && zones.isEmpty()) return
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(zones) { used = container.offline.sizeOnDisk() }
    HorizontalDivider()
    Section(R.string.offline_title) {
        if (zones.isNotEmpty()) {
            Text(
                stringResource(R.string.offline_used, Formatter.formatShortFileSize(context, used)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onOfflineMaps) { Text(stringResource(R.string.settings_account_manage)) }
        }
    }
}

@Composable
private fun LockScreenSection() {
    val settings = appContainer().settings
    val scope = rememberCoroutineScope()
    val enabled by settings.lockScreenMap.collectAsStateWithLifecycle(initialValue = true)
    Section(R.string.settings_lockscreen_title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_lockscreen_switch),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(checked = enabled, onCheckedChange = { scope.launch { settings.setLockScreenMap(it) } })
        }
    }
}

/** Battery optimisation can stop a long recording; let the user exempt Sillage (allowed on F-Droid). */
@SuppressLint("BatteryLife")
@Composable
private fun BatterySection() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    Section(R.string.settings_battery_title) {
        Text(
            stringResource(if (exempt) R.string.settings_battery_body_off else R.string.settings_battery_body_on),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!exempt) {
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                )
            }) { Text(stringResource(R.string.settings_battery_action)) }
        }
        Text(
            stringResource(R.string.settings_battery_vendor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguageSection() {
    val current = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-') }
    val options = listOf(
        "" to stringResource(R.string.settings_language_system),
        "fr" to stringResource(R.string.settings_language_fr),
        "en" to stringResource(R.string.settings_language_en),
    )
    Section(R.string.settings_language_title) {
        Column(Modifier.selectableGroup()) {
            options.forEach { (tag, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = tag == current,
                            role = Role.RadioButton,
                            onClick = {
                                if (tag != current) {
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                                }
                            },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = tag == current, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        content()
    }
}
