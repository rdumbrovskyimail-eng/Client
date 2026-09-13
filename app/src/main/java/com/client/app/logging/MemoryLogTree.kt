package com.client.app.logging

import android.util.Log
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryLogTree @Inject constructor(
    private val logManager: AppLogManager
) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = tag ?: "App"
        val level = when (priority) {
            Log.VERBOSE -> LogLevel.VERBOSE
            Log.DEBUG -> LogLevel.DEBUG
            Log.INFO -> LogLevel.INFO
            Log.WARN -> LogLevel.WARN
            Log.ERROR, Log.ASSERT -> LogLevel.ERROR
            else -> LogLevel.DEBUG
        }

        if (level == LogLevel.ERROR) {
            logManager.e(resolvedTag, message, t)
        } else {
            logManager.log(level, resolvedTag, message)
        }
    }
}