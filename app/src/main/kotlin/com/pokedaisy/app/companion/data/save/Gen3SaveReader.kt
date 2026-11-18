package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.companion.data.TelemetrySampler
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.i18n.tr
import okio.Path

/** The same cartridge eligibility and save validation on both hosts. Read on a worker with the game closed. */
object Gen3SaveReader {
    private const val MAX_ROM_SIZE = 0x1000000L

    sealed interface Result {
        class Ready(val bytes: ByteArray, val save: Gen3SaveFile) : Result
        class Failed(val message: String) : Result
    }

    fun formatFor(rom: RomIdentityReader, size: () -> Long): Gen3SaveFormat? {
        if (rom.isGameBoy()) return null
        val length = size()
        if (length > MAX_ROM_SIZE) return null
        val code = rom.gameCode()
        // Emerald Legacy moved the bag and flags by increasing the ITEMS pocket to 120 slots.
        if (code == "BPEE" && length == MAX_ROM_SIZE && rom.sha1() == TelemetrySampler.EMERALD_LEGACY_V1_1_4_SHA1) return null
        return Gen3SaveFormat.forGameCode(code)
    }

    fun open(path: Path, format: Gen3SaveFormat): Result {
        if (!path.isFile) return Result.Failed(tr("No save yet - save once in the game first."))
        val bytes = runCatching { path.readBytes() }.getOrNull()
            ?: return Result.Failed(tr("Can't read the save {0}", path.toString()))
        return when (val parsed = Gen3SaveFile.parse(bytes, format)) {
            is Gen3SaveFile.Parsed.Failed -> Result.Failed(parsed.reason)
            is Gen3SaveFile.Parsed.Ok -> {
                val mismatch = Gen3SaveData(parsed.save).mismatch()
                if (mismatch == null) Result.Ready(bytes, parsed.save)
                else Result.Failed(tr("This save doesn't look like a {0} save ({1}), so it's left alone.", format.label, mismatch))
            }
        }
    }
}
