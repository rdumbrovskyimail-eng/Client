// >>> FILE: app/src/main/java/com/client/app/api/SocketCustomizer.kt
package com.client.app.api

import android.os.ParcelFileDescriptor
import com.client.app.audio.NativeAudioBridge
import com.client.app.util.AppLogger
import java.io.FileDescriptor
import java.lang.reflect.Field
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * SocketFactory с рекурсивной распаковкой Conscrypt / OkHttp оберток и прямым POSIX-тюнингом.
 */
class TunedSocketFactory(
    private val delegate: SocketFactory,
    private val nativeBridge: NativeAudioBridge,
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

            val fd = extractUnderlyingFileDescriptor(socket)
            if (fd != null && fd.valid()) {
                val nativeFd = getNativeFdInt(fd)
                if (nativeFd > 0) {
                    nativeBridge.tuneNativeSocket(nativeFd)
                }
            }
        }.onFailure {
            logger.w("SocketCustomizer: Не удалось настроить TCP опции: ${it.message}")
        }
        return socket
    }

    private fun extractUnderlyingFileDescriptor(target: Any?): FileDescriptor? {
        if (target == null) return null
        var current: Any = target

        // Разворачиваем возможные обертки ConscryptEngineSocket / OpenSSLSocketImpl
        for (i in 0..5) {
            if (current is FileDescriptor) return current
            if (current is Socket) {
                val impl = getFieldValue(current, "impl")
                if (impl != null) {
                    current = impl
                    continue
                }
            }
            val fd = getFieldValue(current, "fd")
            if (fd is FileDescriptor) return fd
            val socket = getFieldValue(current, "socket") ?: break
            current = socket
        }
        return null
    }

    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            runCatching {
                val field: Field = clazz!!.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun getNativeFdInt(fd: FileDescriptor): Int {
        return runCatching {
            val descriptorField = FileDescriptor::class.java.getDeclaredField("descriptor")
            descriptorField.isAccessible = true
            descriptorField.getInt(fd)
        }.getOrElse {
            runCatching {
                ParcelFileDescriptor.dup(fd).use { it.detachFd() }
            }.getOrDefault(-1)
        }
    }
}