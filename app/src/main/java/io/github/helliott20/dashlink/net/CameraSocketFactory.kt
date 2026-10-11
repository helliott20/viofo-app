package io.github.helliott20.dashlink.net

import android.net.Network
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Creates sockets on the camera's Wi-Fi network (when connected) without binding the whole
 * process to it. Camera traffic goes to the camera; everything else (map tiles, sharing,
 * other apps) keeps using mobile data or home Wi-Fi as normal.
 */
class CameraSocketFactory(private val network: () -> Network?) : SocketFactory() {
    private fun delegate(): SocketFactory = network()?.socketFactory ?: getDefault()

    override fun createSocket(): Socket = delegate().createSocket()
    override fun createSocket(host: String?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(address, port, localAddress, localPort)
}
