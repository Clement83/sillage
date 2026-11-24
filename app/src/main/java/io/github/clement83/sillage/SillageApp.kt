package io.github.clement83.sillage

import android.app.Application
import org.maplibre.android.MapLibre

class SillageApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
    }
}
