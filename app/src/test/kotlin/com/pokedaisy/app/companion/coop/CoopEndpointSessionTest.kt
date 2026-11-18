package com.pokedaisy.app.companion.coop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopEndpointSessionTest {
    @Test fun pairingHoldKeepsOneEndpointAndRestartUsesNewSettings() {
        val underlying = FakeNet().node(1, "ticket")
        val starts = ArrayList<CoopEndpointOptions>()
        var closes = 0
        var options = CoopEndpointOptions(null, false)
        val localNetwork = ArrayList<Boolean>()
        val transport = object : CoopTransport by underlying {
            override fun start(secretKey: ByteArray, relayUrl: String?, relayOnly: Boolean): Int {
                starts.add(CoopEndpointOptions(relayUrl, relayOnly))
                return 0
            }

            override fun close() { closes++ }
        }
        val session = CoopEndpointSession(
            createTransport = { transport },
            directory = { Files.createTempDirectory("pokedaisy-coop-session").toFile().toOkioPath() },
            version = { "test" },
            secretKey = { ByteArray(32) },
            relayConnector = { error("this test has no lobby") },
            options = { options },
            localNetworkActive = { localNetwork.add(it) },
        )
        fun checkpoint() {
            val completed = CountDownLatch(1)
            session.afterStart { completed.countDown() }
            assertTrue("session command timed out", completed.await(5, TimeUnit.SECONDS))
        }

        val initial = options
        session.pairing()
        session.pairing()
        checkpoint()
        assertEquals(listOf(initial), starts)

        options = options.copy(relayOnly = true)
        session.restart()
        checkpoint()
        assertEquals(listOf(initial, options), starts)
        assertEquals(1, closes)

        session.releasePairing()
        checkpoint()
        assertEquals(2, closes)
        assertEquals(listOf(true, false), localNetwork)
    }
}
