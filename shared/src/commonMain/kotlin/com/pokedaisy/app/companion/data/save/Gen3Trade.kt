package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.speciesNames
import com.pokedaisy.app.companion.i18n.tr

/**
 * Trading between two saves, PC box to PC box, with the games' own rules (pret's trade.c
 * `CanTradeSelectedMon` / `GetUnionRoomTradeMessageId`, link.c) and what a trade does afterwards
 * (`UpdatePokedexForReceivedMon`, trade evolutions, Ruby / Sapphire's National Dex):
 *
 * - FireRed / LeafGreen link with Ruby / Sapphire / Emerald only once `FLAG_SYS_CAN_LINK_WITH_RS` is set.
 * - Before its National Dex, FireRed / LeafGreen / Emerald neither send nor take an egg or a Pokémon outside its
 *   regional dex (Kanto's 151 / Hoenn's 202). Ruby / Sapphire's own code has no such check, and trading with
 *   FireRed / LeafGreen / Emerald is what gives them their National Dex.
 * - Mew and Deoxys trade only with their fateful-encounter bit (an event's).
 * - Mail and the e-Reader's Enigma Berry keep data in their own save, so a Pokémon holding one stays.
 * - The game's own "last Pokémon" rule is about the party: the PC is all this trades from.
 *
 * Nothing changes until every check of a trade has passed; then both [Gen3SaveEditor]s hold the result, to be
 * written together (`SaveFileEdits.writeBoth`).
 */
class TradeSide(
    val label: String,
    val editor: Gen3SaveEditor,
    /** The ROM's header game code (BPRE, AXVE, ...). */
    val gameCode: String,
) {
    val format: Gen3SaveFormat get() = editor.file.format
    /** English text: a trade evolution renames by the English species name, so only English games evolve on arrival. */
    val english: Boolean get() = gameCode.endsWith("E")
}

object Gen3Trade {

    /** Why [mon] can't go from [from] to [to], or null if the games would trade it. */
    fun blocker(mon: Gen3BoxMon, from: TradeSide, to: TradeSide): String? {
        if (mon.isEmpty) return null
        if (mon.isBadEgg) return tr("A Bad Egg can't be traded")
        linkBlocker(from, to)?.let { return it }
        for (side in listOf(from, to)) {
            if (side.format == Gen3SaveFormat.RUBY_SAPPHIRE || side.editor.nationalDex) continue
            if (mon.isEgg) return tr("{0} has no National Dex yet, so no eggs", side.label)
            if (!inRegionalDex(mon.species, side.format)) {
                return tr("{0} has no National Dex yet, so no {1}", side.label, speciesLabel(mon.species))
            }
        }
        if ((mon.species == SPECIES_MEW || mon.species == SPECIES_DEOXYS) && !mon.fatefulEncounter) {
            return tr("This {0} isn't from an event, so the games won't trade it", speciesLabel(mon.species))
        }
        if (mon.heldItem in MAIL_ITEMS || mon.heldItem == ITEM_ENIGMA_BERRY) {
            return tr("Mail and Enigma Berries stay with their save: take it off in the game first")
        }
        return null
    }

    /** FireRed / LeafGreen and Ruby / Sapphire / Emerald can't link before the FireRed / LeafGreen side's Sevii Islands. */
    fun linkBlocker(a: TradeSide, b: TradeSide): String? {
        val frlg = listOf(a, b).filter { it.format == Gen3SaveFormat.FIRERED_LEAFGREEN }
        if (frlg.size != 1) return null
        val side = frlg.single()
        return if (side.editor.flag(side.format.linkRsFlag)) null
        else tr("{0} links with Ruby, Sapphire and Emerald only after the Sevii Islands", side.label)
    }

    fun inRegionalDex(species: Int, format: Gen3SaveFormat): Boolean =
        if (format.kanto) species in 1..KANTO_COUNT
        else speciesHoennDex.getOrNull(species)?.let { it in 1..HOENN_DEX_COUNT } == true

    /** The trade evolution [mon] would go through on arriving in [to] (GetEvolutionTargetSpecies, EVO_MODE_TRADE), or null. */
    fun evolution(mon: Gen3BoxMon, to: TradeSide): TradeEvolution? {
        if (mon.isEmpty || mon.isBadEgg || mon.isEgg || !to.english) return null
        if (mon.heldItem == ITEM_EVERSTONE) return null
        return tradeEvolutions.firstOrNull { it.species == mon.species && (it.item == 0 || it.item == mon.heldItem) }
    }

    /** Sends the Pokémon at [from]'s [at] to the empty slot [to] of [dest]. Throws [TradeRefused] and changes nothing if the games wouldn't. */
    fun send(src: TradeSide, at: BoxSlot, dest: TradeSide, to: BoxSlot, evolve: Boolean = true) {
        val mon = src.editor.data.mon(at)
        if (mon.isEmpty) throw TradeRefused(tr("There's no Pokémon there"))
        if (!dest.editor.data.mon(to).isEmpty) throw TradeRefused(tr("That slot isn't empty"))
        blocker(mon, src, dest)?.let { throw TradeRefused(it) }
        val arriving = arrive(mon, dest, evolve)
        src.editor.putRecord(at, ByteArray(Gen3BoxMon.SIZE))
        dest.editor.putRecord(to, arriving)
        afterTrade(src, dest)
    }

    /** Swaps the Pokémon at [a]'s [atA] and [b]'s [atB]; each side's trade evolution unless told otherwise. */
    fun swap(a: TradeSide, atA: BoxSlot, b: TradeSide, atB: BoxSlot, evolveA: Boolean = true, evolveB: Boolean = true) {
        val monA = a.editor.data.mon(atA)
        val monB = b.editor.data.mon(atB)
        if (monA.isEmpty || monB.isEmpty) throw TradeRefused(tr("There's no Pokémon there"))
        blocker(monA, a, b)?.let { throw TradeRefused(it) }
        blocker(monB, b, a)?.let { throw TradeRefused(it) }
        val toB = arrive(monA, b, evolveA)
        val toA = arrive(monB, a, evolveB)
        b.editor.putRecord(atB, toB)
        a.editor.putRecord(atA, toA)
        afterTrade(a, b)
    }

    /**
     * [mon]'s record as it lands in [to]: evolved if it trade-evolves (the item it needed used up, its name changed
     * when it was the species' own - EvolutionRenameMon), and [to]'s Pokédex told about it. Mutates [to]'s Pokédex.
     */
    private fun arrive(mon: Gen3BoxMon, to: TradeSide, evolve: Boolean): ByteArray {
        if (!mon.isEgg) to.editor.markCaught(mon.species, mon.personality)
        val evo = evolution(mon, to)?.takeIf { evolve } ?: return mon.encode()
        val oldName = englishName(mon.species)
        val renamed = oldName != null && nicknameText(mon.nicknameBytes) == nicknameText(oldName)
        val record = mon.withChanges(
            species = evo.into,
            heldItem = if (evo.item != 0) 0 else null,
            nickname = if (renamed) englishName(evo.into) else null,
        )
        to.editor.markCaught(evo.into, mon.personality)
        return record
    }

    /** Ruby / Sapphire get their National Dex from any trade with FireRed / LeafGreen / Emerald (pokeruby trade.c). */
    private fun afterTrade(a: TradeSide, b: TradeSide) {
        for ((side, partner) in listOf(a to b, b to a)) {
            if (side.format == Gen3SaveFormat.RUBY_SAPPHIRE && partner.format != Gen3SaveFormat.RUBY_SAPPHIRE && !side.editor.nationalDex) {
                side.editor.enableNationalDex()
            }
        }
    }

    /** The species' English name in the games' charset as gSpeciesNames stores it (caps, EOS, zero padding), or null. */
    internal fun englishName(species: Int): ByteArray? {
        val name = speciesNames[species]?.uppercase() ?: return null
        val out = ByteArray(Gen3BoxMon.NICKNAME_BYTES)
        if (name.length > out.size) return null
        name.forEachIndexed { i, c -> out[i] = (WESTERN[c] ?: return null).toByte() }
        if (name.length < out.size) out[name.length] = EOS.toByte()
        return out
    }

    /** A name as the game compares it: up to its EOS. */
    private fun nicknameText(b: ByteArray): List<Byte> = b.takeWhile { it != EOS.toByte() }

    private fun speciesLabel(species: Int) = speciesNames[species]?.uppercase() ?: "#$species"

    private const val EOS = 0xFF
    private const val KANTO_COUNT = 151

    // The species names' characters in the Western charset (charmap.txt).
    private val WESTERN: Map<Char, Int> = buildMap {
        put(' ', 0x00); put('É', 0x1B); put('.', 0xAD); put('-', 0xAE); put('♂', 0xB5); put('♀', 0xB6); put('\'', 0xB4)
        for (i in 0..9) put('0' + i, 0xA1 + i)
        for (i in 0 until 26) put('A' + i, 0xBB + i)
    }
}

/** A trade the games wouldn't allow, with the reason to show. */
class TradeRefused(message: String) : Exception(message)
