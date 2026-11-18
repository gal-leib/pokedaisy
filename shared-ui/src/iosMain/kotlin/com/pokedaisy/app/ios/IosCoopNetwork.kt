package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.coop.CoopAddress
import com.pokedaisy.app.companion.coop.CoopEndpoint
import com.pokedaisy.app.companion.coop.CoopNetwork
import com.pokedaisy.app.companion.coop.CoopPipe
import com.pokedaisy.app.companion.coop.CoopRelayPipe
import com.pokedaisy.app.companion.coop.CoopRelaySocket
import com.pokedaisy.app.companion.coop.CoopTcpSocket
import com.pokedaisy.app.companion.coop.CoopTcpListener
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Schedules relay routing away from the iOS UI and emulator threads. */
internal class IosCoopNetwork(private val tcp: IosCoopTcpNative?) : CoopNetwork {
    private val relayWorkers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    override fun tickEvery(intervalMs: Long, tick: () -> Unit) {
        ticker = relayWorkers.launch {
            while (isActive) {
                delay(intervalMs)
                tick()
            }
        }
    }

    override fun relayPipe(): CoopRelayPipe = RelayPipe(relayWorkers)
    override fun listen(onAccept: (CoopTcpSocket) -> Unit): CoopTcpListener? {
        val native = tcp?.listen(0) { onAccept(IosCoopTcpSocket(it, relayWorkers)) } ?: return null
        return object : CoopTcpListener {
            override val port get() = native.port
            override fun start() {}
            override fun close() = native.close()
        }
    }

    override fun ticketAddresses(port: Int): List<CoopAddress> =
        tcp?.addresses()?.mapNotNull { it.toEndpoint(port)?.address }.orEmpty()

    override fun connect(address: CoopEndpoint, timeoutMs: Long, connected: (CoopTcpSocket?) -> Unit) {
        val host = tcp ?: return connected(null)
        host.connect(address.hostName(), address.address.port, timeoutMs.toInt()) { socket ->
            connected(socket?.let { IosCoopTcpSocket(it, relayWorkers) })
        }
    }

    override fun close() {
        ticker?.cancel()
        ticker = null
        // The router has sealed its relay writers. Let pending CLOSE frames drain.
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class RelayPipe(scope: CoroutineScope) : CoopRelayPipe {
    private val lock = SynchronizedObject()
    private val ready = CompletableDeferred<CoopRelaySocket?>()
    private val queue = Channel<ByteArray>(CoopPipe.CAPACITY)
    private var socket: CoopRelaySocket? = null
    private var pending = 0
    private var sealed = false

    private val worker = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            val target = withTimeoutOrNull(5_000) { ready.await() } ?: return@launch
            for (message in queue) {
                synchronized(lock) { pending-- }
                if (!target.send(message)) break
            }
        } finally {
            val target = synchronized(lock) {
                sealed = true
                socket.also { socket = null }
            }
            queue.cancel()
            target?.close()
        }
    }

    override val backlog: Int get() = synchronized(lock) { pending }

    override fun offer(bytes: ByteArray): Boolean = synchronized(lock) {
        if (sealed || !queue.trySend(bytes).isSuccess) return@synchronized false
        pending++
        true
    }

    override fun attach(socket: CoopRelaySocket) {
        val accepted = synchronized(lock) {
            if (sealed || this.socket != null) false else {
                this.socket = socket
                ready.complete(socket)
                true
            }
        }
        if (!accepted) socket.close()
    }

    override fun closeSoon() {
        synchronized(lock) {
            sealed = true
            if (socket == null) ready.complete(null)
            queue.close()
        }
    }

    override fun closeNow() {
        closeSoon()
        worker.cancel()
    }
}
