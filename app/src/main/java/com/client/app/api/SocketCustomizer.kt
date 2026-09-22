package com.client.app.api

import com.client.app.logging.AppLogManager
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class TunedSocketFactory(
    private val delegate: SocketFactory,
    private val logManager: AppLogManager
) : SocketFactory() {

    override fun createSocket(): Socket =
        configure(delegate.createSocket())

    override fun createSocket(
        host: String,
        port: Int
    ): Socket =
        configure(
            delegate.createSocket(
                host,
                port
            )
        )

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket =
        configure(
            delegate.createSocket(
                host,
                port,
                localHost,
                localPort
            )
        )

    override fun createSocket(
        host: InetAddress,
        port: Int
    ): Socket =
        configure(
            delegate.createSocket(
                host,
                port
            )
        )

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket =
        configure(
            delegate.createSocket(
                address,
                port,
                localAddress,
                localPort
            )
        )

    private fun configure(socket: Socket): Socket {
        runCatching {
            socket.tcpNoDelay = true
        }.onFailure { error ->
            logManager.w(
                "SocketCustomizer",
                "Не удалось установить TCP_NODELAY: ${error.message}"
            )
        }

        return socket
    }
}