// >>> FILE: app/src/main/java/com/client/app/api/SocketCustomizer.kt
package com.client.app.api

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.client.app.util.AppLogger
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * SocketFactory, конфигурирующий низкоуровневые параметры ядра Linux для голосового стриминга.
 */
class TunedSocketFactory(
    private val delegate: SocketFactory,
    private val logger: AppLogger
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

            val fd = extractFileDescriptor(socket)
            if (fd != null && fd.valid()) {
                applyLinuxSocketTuning(fd)
            }
        }.onFailure {
            logger.w("SocketCustomizer: Не удалось применить расширенные опции сокета: ${it.message}")
        }
        return socket
    }

    private fun extractFileDescriptor(socket: Socket): FileDescriptor? {
        return runCatching {
            val getFdMethod = socket.javaClass.getMethod("getFileDescriptor\$")
            getFdMethod.invoke(socket) as? FileDescriptor
        }.getOrNull()
    }

    private fun applyLinuxSocketTuning(fd: FileDescriptor) {
        // 1. Отключение алгоритма Нейгла на уровне ядра (IPPROTO_TCP = 6, TCP_NODELAY = 1)
        runCatching {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 1, 1)
        }

        // 2. Ликвидация Bufferbloat: TCP_NOTSENT_LOWAT (Linux constant 25)
        // Ограничивает буфер неотправленных данных ядра до 16 КБ (~500 мс звука)
        runCatching {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 25, 16384)
        }

        // 3. Мгновенные ACK: TCP_QUICKACK (Linux constant 12)
        runCatching {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 12, 1)
        }
    }
}