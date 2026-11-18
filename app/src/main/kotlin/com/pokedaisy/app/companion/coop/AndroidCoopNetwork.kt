package com.pokedaisy.app.companion.coop

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal class AndroidCoopNetwork(
    private val bindLoopback: Boolean,
    private val addresses: () -> List<InetAddress> = {
        if (bindLoopback) listOf(InetAddress.getLoopbackAddress()) else siteLocalAddresses()
    },
) : CoopNetwork {
    private val io = Executors.newCachedThreadPool(daemon("pokedaisy-coop-io"))
    private val worker = Executors.newSingleThreadScheduledExecutor(daemon("pokedaisy-coop-net"))

    override fun tickEvery(intervalMs: Long, tick: () -> Unit) {
        worker.scheduleWithFixedDelay(tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    override fun relayPipe(): CoopRelayPipe = RelayPipe()

    override fun listen(onAccept: (CoopTcpSocket) -> Unit): CoopTcpListener? = try {
        DirectListener(bindLoopback, io) { onAccept(DirectSocket(it, io)) }
    } catch (_: IOException) {
        null
    }

    override fun ticketAddresses(port: Int): List<CoopAddress> =
        addresses().distinct().map { CoopAddress(it.address, port) }

    override fun connect(address: CoopEndpoint, timeoutMs: Long, connected: (CoopTcpSocket?) -> Unit) {
        val ip = address.address.ip
        val host = if (ip.size == 16 && address.interfaceIndex != 0) {
            Inet6Address.getByAddress(null, ip, address.interfaceIndex)
        } else {
            InetAddress.getByAddress(ip)
        }
        io.execute {
            connected(DirectSocket.connect(InetSocketAddress(host, address.address.port), timeoutMs)?.let { DirectSocket(it, io) })
        }
    }

    override fun close() {
        worker.shutdownNow()
        io.shutdown()
        worker.awaitTermination(500, TimeUnit.MILLISECONDS)
        io.awaitTermination(1_500, TimeUnit.MILLISECONDS)
    }

    private inner class RelayPipe : CoopRelayPipe {
        private val ready = CountDownLatch(1)
        private val guard = Any()
        private var socket: CoopRelaySocket? = null
        private var ended = false
        private val writer = PipeWriter(
            io, ready,
            write = { bytes -> synchronized(guard) { socket }?.send(bytes) ?: false },
            onEnd = {
                val closed = synchronized(guard) {
                    ended = true
                    socket.also { socket = null }
                }
                closed?.close()
            },
        ).also { it.start() }

        override val backlog: Int get() = writer.backlog
        override fun offer(bytes: ByteArray) = writer.offer(bytes)
        override fun closeSoon() {
            writer.closeSoon()
            ready.countDown()
        }

        override fun closeNow() {
            writer.closeNow()
            ready.countDown()
        }

        override fun attach(socket: CoopRelaySocket) {
            val close = synchronized(guard) {
                if (ended) true else {
                    this.socket = socket
                    false
                }
            }
            ready.countDown()
            if (close) socket.close()
        }
    }

    private companion object {
        fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

        /** Site-local IPv4 and unique-local IPv6 addresses of interfaces that are up. */
        fun siteLocalAddresses(): List<InetAddress> = try {
            (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { address ->
                    !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress &&
                        !address.isAnyLocalAddress && (address is Inet4Address && address.isSiteLocalAddress ||
                        address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)
                }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
        } catch (_: IOException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }
}

internal fun InetSocketAddress.toCoopEndpoint(): CoopEndpoint =
    CoopEndpoint(CoopAddress(address.address, port), (address as? Inet6Address)?.scopeId ?: 0)

internal fun androidCoopTransport(
    connector: CoopRelayConnector = OkHttpRelayConnector(),
    lan: CoopLan = NoCoopLan,
    timings: CoopTimings = CoopTimings(),
    clock: () -> Long = System::currentTimeMillis,
    bindLoopback: Boolean = false,
): RelayCoopTransport = RelayCoopTransport(connector, JvmCoopCrypto, { AndroidCoopNetwork(bindLoopback) }, lan, timings, clock)
