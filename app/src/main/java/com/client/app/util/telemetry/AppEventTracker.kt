package com.client.app.util.telemetry

interface AppEventTracker {
    fun trackEvent(name: String, params: Map<String, String> = emptyMap())
}
