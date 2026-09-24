// >>> FILE: app/src/main/java/com/client/app/api/SocketCustomizer.kt
package com.client.app.api

import android.os.ParcelFileDescriptor
import com.client.app.audio.NativeAudioBridge
import com.client.app.logging.AppLogManager
import java.net.InetAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

@Singleton
class TunedSocketFactory(
    private val delegate: SocketFactory,
    private val nativeBridge: NativeAudioBridge,
    private val logManager: AppLogManager
) : SocketFactory() {

    @Inject
    constructor(
        nativeBridge: NativeAudioBridge,
        logManager: AppLogManager
    ) : this(
        delegate = SocketFactory.getDefault(),
        nativeBridge = nativeBridge,
        logManager = logManager
    )

    override fun createSocket(): Socket = configure(delegate.createSocket())

    override fun createSocket(host: String, port: Int): Socket =
        configure(delegate.createSocket(host, port))

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = configure(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        configure(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = configure(delegate.createSocket(address, port, localAddress, localPort))

    private fun configure(socket: Socket): Socket {
        runCatching {
            socket.tcpNoDelay = true

            // P1 Fix (Проблема №15): Безусловное дублирование дескриптора сокета через системный вызов dup()
            // на всех поддерживаемых версиях Android (API 28+).
            // Это исключает закрытие оригинального сетевого сокета OkHttp в ядре Linux: pfd.close() внутри .use
            // закрывает исключительно изолированный дубликат (декремент f_count в struct file ядра с 2 до 1),
            // предотвращая спорадические сбои TLS 1.3 со статусом "Socket closed" или EBADF.
            ParcelFileDescriptor.fromSocket(socket)?.use { origPfd ->
                origPfd.dup()?.use { dupPfd ->
                    val nativeFd = dupPfd.fd
                    if (nativeFd >= 0) {
                        nativeBridge.tuneNativeSocket(nativeFd)
                        logManager.net(
                            "SocketCustomizer",
                            "Применены TCP опции (fd=$nativeFd)"
                        )
                    }
                }
            }
        }.onFailure {
            logManager.w(
                "SocketCustomizer",
                "Сбой конфигурации TCP опций: ${it.message}"
            )
        }
        return socket
    }
}