package com.client.app.util.telemetry

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAppEventTracker @Inject constructor() : AppEventTracker {

    override fun trackEvent(name: String, params: Map<String, String>) {
        if (params.isEmpty()) {
            Timber.tag(TAG).d("Event: $name")
        } else {
            Timber.tag(TAG).d("Event: $name, params: $params")
        }
    }

    companion object {
        private const val TAG = "AppEventTracker"
    }
}
