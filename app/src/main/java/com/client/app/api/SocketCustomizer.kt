// >>> FILE: app/src/main/java/com/client/app/api/SocketCustomizer.kt
package com.client.app.api

import android.os.ParcelFileDescriptor
import com.client.app.audio.NativeAudioBridge
import com.client.app.logging.AppLogManager
import android.os.Build
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class TunedSocketFactory(
    private val delegate: SocketFactory,
    private val nativeBridge: NativeAudioBridge,
    private val logManager: AppLogManager
) : SocketFactory() {

    override fun createSocket(): Socket = configure(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(address, port, localAddress, localPort))

    private fun configure(socket: Socket): Socket {
        runCatching {
            socket.tcpNoDelay = true

            // Use the public Android bridge to the socket descriptor.
            // Android restricts non-SDK reflection, so do not inspect SocketImpl
            // or FileDescriptor internals directly. On API < 29 the documented
            // compatibility pattern is fromSocket(socket).dup().
            val pfd =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ParcelFileDescriptor.fromSocket(socket)
                } else {
                    ParcelFileDescriptor.fromSocket(socket)?.dup()
                }

            if (pfd != null) {
                try {
                    val nativeFd = pfd.fd
                    if (nativeFd >= 0) {
                        nativeBridge.tuneNativeSocket(nativeFd)
                        logManager.net(
                            "SocketCustomizer",
                            "Применены TCP опции (fd=$nativeFd)"
                        )
                    }
                } finally {
                    pfd.close()
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

