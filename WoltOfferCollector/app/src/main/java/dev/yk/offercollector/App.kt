package dev.yk.offercollector

import android.app.Application
import dev.yk.offercollector.data.AppDatabase
import dev.yk.offercollector.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        settings // start loading settings eagerly so services see them quickly
    }
}

val android.content.Context.app: App get() = applicationContext as App
