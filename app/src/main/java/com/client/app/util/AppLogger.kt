package com.client.app.util

interface AppLogger {
    fun d(m: String)
    fun w(m: String)
    fun e(m: String, t: Throwable? = null)
}