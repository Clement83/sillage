package io.github.clement83.sillage

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class SillageApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
        // Paired while the app still had a default server: the settings kept none. Show the paired one.
        container.appScope.launch {
            if (container.settings.serverUrl.first() == null) {
                container.account.current()?.let { container.settings.setServerUrl(it.server) }
            }
        }
    }
}
