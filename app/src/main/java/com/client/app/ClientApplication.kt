package com.client.app

import android.app.Application
import com.client.app.logging.MemoryLogTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ClientApplication : Application() {

    @Inject lateinit var memoryLogTree: MemoryLogTree

    override fun onCreate() {
        super.onCreate()
        Timber.plant(memoryLogTree)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}