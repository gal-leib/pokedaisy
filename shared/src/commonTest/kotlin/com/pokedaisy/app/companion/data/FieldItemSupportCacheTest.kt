package com.pokedaisy.app.companion.data

import kotlin.test.*

class FieldItemSupportCacheTest {
    @Test fun failedReadsRetryAndVerifiedTablesAreReusedUntilTheGameChanges() {
        val bytes = ByteArray(32)
        var reads = 0
        var booted = false
        val cache = FieldItemSupportCache(object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                reads++
                check(booted)
                return bytes
            }
        })
        val fns = FieldItemFns(0x08000100, 0, 0, 0, 0, 0, 0, listOf(crc32(bytes)))
        assertNull(cache.get(null, "TEST", 0, fns))
        booted = true
        val support = assertNotNull(cache.get(null, "TEST", 0, fns))
        assertEquals(2, reads)
        assertSame(support, cache.get(null, "TEST", 0, fns))
        assertEquals(2, reads)
        assertNotNull(cache.get(null, "TEST", 1, fns))
        assertEquals(3, reads)
    }
}
