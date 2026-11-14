package com.pokedaisy.app.companion.data

import org.junit.Assert.*
import org.junit.Test

class GameOptionPatchesTest {
    private val sites = listOf(ReusableTms.Site(0x08001000L, ByteArray(16)), ReusableTms.Site(0x08001100L, ByteArray(16)))
    private val text = InstantText.Patch(listOf(InstantText.Write(0x08002000L, byteArrayOf(1, 2), byteArrayOf(3, 4)),
        InstantText.Write(0x08002010L, byteArrayOf(5, 6), byteArrayOf(7, 8))))
    private class Host(private val fail: (Long, Int) -> Boolean = { _, _ -> false }) : RomOptionPatcher {
        var calls = 0
        val active = mutableSetOf<Long>()
        override fun unpatch() { active.clear() }
        override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray): Boolean {
            if (fail(at, ++calls)) return false
            active += at
            return true
        }
    }
    @Test fun featuresCanBeEnabledAndDisabledTogether() {
        val host = Host()
        assertEquals(GameOptionPatches.Result(true, true), GameOptionPatches.apply(host, sites, text))
        assertEquals((sites.map { it.branch } + text.writes.map { it.addr }).toSet(), host.active)
        assertEquals(GameOptionPatches.Result(false, false), GameOptionPatches.apply(host, emptyList(), null))
        assertTrue(host.active.isEmpty())
    }
    @Test fun partialTmFailureStillAllowsCompleteText() {
        val host = Host { at, _ -> at == sites.last().branch }
        assertEquals(GameOptionPatches.Result(false, true), GameOptionPatches.apply(host, sites, text))
        assertEquals(text.writes.map { it.addr }.toSet(), host.active)
    }
    @Test fun partialTextFailureRestoresTheOtherFeature() {
        val host = Host { at, _ -> at == text.writes.last().addr }
        assertEquals(GameOptionPatches.Result(true, false), GameOptionPatches.apply(host, sites, text))
        assertEquals(sites.map { it.branch }.toSet(), host.active)
    }
    @Test fun failedRestorationLeavesNoHalfAppliedFeature() {
        val host = Host { at, call -> at == text.writes.last().addr || call == 6 }
        assertEquals(GameOptionPatches.Result(false, false), GameOptionPatches.apply(host, sites, text))
        assertTrue(host.active.isEmpty())
    }
}
