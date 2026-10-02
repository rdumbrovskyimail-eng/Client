package com.client.app.api

import android.os.Build
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

            // УСТРАНЕНИЕ БУФЕРБЛОАТА (RFC 8860): Рациональные 64 КБ буферы TCP для низкой задержки
            socket.sendBufferSize = 64 * 1024
            socket.receiveBufferSize = 64 * 1024

            // Безусловное дублирование дескриптора сокета через dup() для исключения случайного закрытия сокета
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ParcelFileDescriptor.fromSocket(socket)?.use { origPfd ->
                    origPfd.dup()?.use { dupPfd ->
                        val nativeFd = dupPfd.fd
                        if (nativeFd >= 0) {
                            nativeBridge.tuneNativeSocket(nativeFd)
                            logManager.net(
                                "SocketCustomizer",
                                "Применены TCP опции (fd=$nativeFd, tcpNoDelay=true, sndBuf=64K, rcvBuf=64K)"
                            )
                        }
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