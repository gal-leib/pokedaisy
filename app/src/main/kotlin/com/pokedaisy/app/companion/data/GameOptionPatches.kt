package com.pokedaisy.app.companion.data

/** The native core owns reversible, expected-byte-checked ROM edits. File bytes never change. */
interface RomOptionPatcher {
    fun unpatch()
    fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray): Boolean
}

/** TMs and text share one native patch set: rollback must preserve the other feature atomically. */
object GameOptionPatches {
    data class Result(val tms: Boolean, val text: Boolean)
    fun apply(host: RomOptionPatcher, sites: List<ReusableTms.Site>, text: InstantText.Patch?): Result {
        host.unpatch()
        fun applyTms() = sites.isNotEmpty() && sites.all { host.patch(it.at, it.expect, it.branch, ReusableTms.ALWAYS_SKIP) }
        var tms = applyTms()
        if (sites.isNotEmpty() && !tms) host.unpatch()
        if (text != null && !text.writes.all { host.patch(it.addr, it.expect, it.addr, it.value) }) {
            host.unpatch()
            if (tms) {
                tms = applyTms()
                if (!tms) host.unpatch()
            }
            return Result(tms, false)
        }
        return Result(tms, text != null)
    }
}
