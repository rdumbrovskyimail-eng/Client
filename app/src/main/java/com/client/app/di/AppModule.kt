package com.client.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.client.app.logging.AppLogManager
import com.client.app.util.AppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "client_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun provideLogger(logManager: AppLogManager): AppLogger = object : AppLogger {
        override fun d(m: String) = logManager.d("App", m)
        override fun w(m: String) = logManager.w("App", m)
        override fun e(m: String, t: Throwable?) = logManager.e("App", m, t)
    }
}