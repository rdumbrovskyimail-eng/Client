package com.client.app.api

import android.os.ParcelFileDescriptor
import com.client.app.audio.NativeAudioBridge
import com.client.app.logging.AppLogManager
import java.io.FileDescriptor
import java.lang.reflect.Field
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
            val fd = extractUnderlyingFileDescriptor(socket)
            if (fd != null && fd.valid()) {
                val nativeFd = getNativeFdInt(fd)
                if (nativeFd > 0) {
                    nativeBridge.tuneNativeSocket(nativeFd)
                    logManager.net("SocketCustomizer", "Успешно применены опции TCP_NOTSENT_LOWAT & TCP_NODELAY (fd=$nativeFd)")
                } else {
                    runCatching {
                        ParcelFileDescriptor.dup(fd).use { pfd ->
                            val dupFd = pfd.fd
                            if (dupFd > 0) {
                                nativeBridge.tuneNativeSocket(dupFd)
                                logManager.net("SocketCustomizer", "Применены TCP опции через dupFd=$dupFd")
                            }
                        }
                    }
                }
            }
        }.onFailure {
            logManager.w("SocketCustomizer", "Сбой конфигурации TCP опций: ${it.message}")
        }
        return socket
    }

    private fun extractUnderlyingFileDescriptor(target: Any?): FileDescriptor? {
        if (target == null) return null
        var current: Any = target
        for (i in 0..5) {
            if (current is FileDescriptor) return current
            if (current is Socket) {
                val impl = getFieldValue(current, "impl")
                if (impl != null) { current = impl; continue }
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
        }.getOrElse { -1 }
    }
}