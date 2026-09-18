// >>> FILE: app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt
package com.client.app.api

import android.util.Base64
import kotlinx.coroutines.*
import com.client.app.audio.NativeAudioBridge
import com.client.app.logging.AppLogManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

@Singleton
class GeminiProtobufLiveClient @Inject constructor(
    private val nativeBridge: NativeAudioBridge,
    private val logManager: AppLogManager
) {
    companion object {
        const val WS_HOST =
            "generativelanguage.googleapis.com"

        const val WS_PATH =
            "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Application-level watermark.
        // This is intentionally below OkHttp's own terminal queue limit.
        private const val MAX_QUEUE_BYTES =
            256L * 1024L

        // 40 ms @ 16 kHz PCM16 mono:
        // 16000 * 0.040 * 2 = 1280 bytes.
        private const val AUDIO_BATCH_THRESHOLD_BYTES =
            1280

        private const val WS_QUEUE_POLL_MS = 5L

        // Bounded command queue means network backpressure eventually
        // propagates to the capture producer instead of dropping PCM.
        private const val AUDIO_COMMAND_CHANNEL_CAPACITY = 32

        private const val MAX_INITIAL_HISTORY_TURNS = 20

        // Retained from prior P0 work:
        // protect against unbounded incoming model audio backlog.
        private const val MAX_AI_AUDIO_BACKLOG_BYTES =
            4L * 1024L * 1024L

        private const val MAX_DATA_EVENTS_IN_FLIGHT = 256
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // AUD-067:
    //
    // One ordered producer pipeline for realtime audio/activity commands.
    //
    // Crucially:
    //   - PCM uses suspend/send(), not trySend()
    //   - AudioStreamEnd uses the same queue
    //   - ActivityStart/End use the same queue
    //