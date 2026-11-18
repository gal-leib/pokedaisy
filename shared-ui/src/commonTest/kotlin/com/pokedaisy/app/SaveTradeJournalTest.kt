package com.pokedaisy.app

import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.SYSTEM

class SaveTradeJournalTest {
    private val fs = FileSystem.SYSTEM

    @Test fun aPartlyWrittenTradeRollsBackAndKeepsAnExternalChange() = fixture { file, journal, a, b ->
        journal.write(a, b)
        b.edit.path.writeBytes(b.edit.updated)
        val external = "an unrelated game save".encodeToByteArray()
        a.edit.path.writeBytes(external)

        val recovered = SaveTradeJournal(file, Path::writeBytes)
        assertTrue(recovered.recover({ true }) { error("A partial trade must not clear resume state") })
        assertContentEquals(external, a.edit.path.readBytes())
        assertContentEquals(b.edit.expected, b.edit.path.readBytes())
        assertFalse(fs.exists(file))
    }

    @Test fun bothWrittenSavesStayCommittedWhenFinalizationWasInterrupted() = fixture { file, journal, a, b ->
        journal.write(a, b)
        a.edit.path.writeBytes(a.edit.updated)
        b.edit.path.writeBytes(b.edit.updated)
        val finished = mutableListOf<String>()
        assertFalse(journal.recover({ true }) { side ->
            if (side.stateKey == b.stateKey) error("Finalization interrupted")
            finished += side.stateKey
        })
        assertTrue(fs.exists(file))

        val reopened = SaveTradeJournal(file, Path::writeBytes)
        assertTrue(reopened.recover({ true }) { finished += it.stateKey })
        assertContentEquals(a.edit.updated, a.edit.path.readBytes())
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())
        assertEquals(listOf(a.stateKey, a.stateKey, b.stateKey), finished)
        assertFalse(fs.exists(file))
    }

    @Test fun unrecognizedPathOrFailedRecoveryKeepsTheJournal() = fixture { file, journal, a, b ->
        journal.write(a, b)
        b.edit.path.writeBytes(b.edit.updated)

        assertFalse(journal.recover({ it.stateKey != b.stateKey }) { error("Rejected path") })
        assertTrue(fs.exists(file))
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())

        val unwritable = SaveTradeJournal(file, { _, _ -> error("Could not sync the rollback") })
        assertFalse(unwritable.recover({ true }) { error("Partial trade") })
        assertTrue(fs.exists(file))
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())

        assertTrue(journal.recover({ true }) { error("Partial trade") })
        assertContentEquals(b.edit.expected, b.edit.path.readBytes())
        assertFalse(fs.exists(file))
    }

    private fun fixture(action: (Path, SaveTradeJournal, SaveTradeJournal.Side, SaveTradeJournal.Side) -> Unit) {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-trade-${Random.nextLong().toULong()}"
        fs.createDirectories(dir)
        try {
            val file = dir / "pending"
            fun side(name: String): SaveTradeJournal.Side {
                val path = dir / "$name.sav"
                val expected = "$name original".encodeToByteArray()
                path.writeBytes(expected)
                return SaveTradeJournal.Side(SaveEditFiles.Edit(path, expected, "$name edited".encodeToByteArray()), "$name-key")
            }
            action(file, SaveTradeJournal(file, Path::writeBytes), side("a"), side("b"))
        } finally {
            fs.deleteRecursively(dir)
        }
    }
}
