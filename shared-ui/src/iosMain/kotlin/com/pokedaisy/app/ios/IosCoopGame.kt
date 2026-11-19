package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.CoopApp
import com.pokedaisy.app.CoopBattleSession
import com.pokedaisy.app.CoopEmeraldRam
import com.pokedaisy.app.CoopEmu
import com.pokedaisy.app.CoopFrameHook
import com.pokedaisy.app.CoopGlueEnv
import com.pokedaisy.app.CoopGuards
import com.pokedaisy.app.CoopLock
import com.pokedaisy.app.CoopPresence
import com.pokedaisy.app.CoopPresenceChannel
import com.pokedaisy.app.LockNative
import com.pokedaisy.app.PartnerCore
import com.pokedaisy.app.companion.PlayerLookSession
import com.pokedaisy.app.companion.coop.CoopEndpointSession
import com.pokedaisy.app.companion.coop.CoopGameStartup
import com.pokedaisy.app.companion.coop.CoopGameShutdown
import com.pokedaisy.app.companion.coop.CoopGameSettings
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopSaveId
import com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1
import com.pokedaisy.app.companion.coop.coopLog
import com.pokedaisy.app.companion.coop.coopRetailSha1
import com.pokedaisy.app.companion.coop.lock.CoopLockStore
import com.pokedaisy.app.companion.coop.lock.LockWait
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.coop.CoopIdentity
import com.pokedaisy.app.companion.data.coop.CoopJoinRules
import com.pokedaisy.app.companion.data.coop.CoopStory
import com.pokedaisy.app.companion.data.coop.CoopStorySpots
import com.pokedaisy.app.companion.data.coop.FileCatchStore
import com.pokedaisy.app.companion.data.coop.PartnerArt
import com.pokedaisy.app.companion.data.coop.PartnerPlacement
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CoopOverlayState
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.core.PkCoopCore
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/** Binds the shared co-op game to the iOS controller, whose frame and UI callbacks run on main. */
internal class IosCoopGame(
    private val rom: Path,
    private val save: Path,
    private val files: Path,
    private val look: PlayerLookSession,
    private val setFrameGate: (((Int) -> Int?)?) -> Unit,
) {
    private val endpoint = IosCoopHost.session
    private val link = endpoint.link
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val startedAt = TimeSource.Monotonic.markNow()
    private fun now() = startedAt.elapsedNow().inWholeMilliseconds
    private val battle = CoopBattleSession(::now) { PkCoopCore }
    val overlay = CoopOverlayState()
    private var hook: CoopFrameHook? = null
    private var startupPending = false
    private val startup = CoopGameStartup { pending ->
        startupPending = pending
        setFrameGate(if (pending) ({ _: Int -> null }) else if (engaged) ::beforeFrame else null)
    }
    private val shutdown = CoopGameShutdown(
        restoreBattle = battle::stopNow,
        resumeBattles = battle::resume,
        stopEndpoint = { if (started) endpoint.stopGame() },
        detachGame = ::detach,
    )
    private val settings = CoopGameSettings(
        startup = startup,
        ownSave = { pair?.ownSave },
        refuseBattle = ::refuseBattle,
        pauseBattles = shutdown::stopBattle,
        resumeBattles = shutdown::resume,
        detach = shutdown::detachAfterEndpointStop,
    )
    private var started = false
    private var pair by mutableStateOf<CoopPair?>(null)
    var storyLock by mutableStateOf<CoopLock?>(null)
        private set
    val partnerName get() = pair?.partner?.name ?: tr("Partner")
    val engaged get() = pair != null
    val battleActive get() = battle.busy
    val blocksLoad get() = startupPending || CoopGuards.statesBlocked
    private var presence: CoopPresence? = null
    private val art = mutableMapOf<Pair<Boolean, LookChar?>, ByteArray?>()
    private var artJob: Job? = null

    private val emulation = object : CoopEmu {
        override fun post(job: () -> Unit) { scope.launch { job() } }
        override fun runBetweenFrames(waitMs: Long, job: () -> Unit): Boolean {
            job() // The caller and frame callbacks share the main thread.
            return true
        }
    }

    fun start(code: String?, revision: Int, cheatsLoaded: () -> Boolean) {
        if (started || shutdown.detached) return
        started = true
        endpoint.bindGameSettings(settings)
        val fs = FileSystem.SYSTEM
        val size = fs.metadataOrNull(rom)?.size ?: 0L
        if (!CoopEndpointSession.mayBeRetailEmerald(code, size)) {
            endpoint.startUnsupportedGame()
            return
        }
        startup.start(scope, resolve = {
            withContext(Dispatchers.Default) {
                val hash = coopRetailSha1(code, revision, size, { RomIdentityReader.forPath(rom).sha1() }) { fs.source(rom) }
                val selection = endpoint.lookupGame(hash, save)
                selection to selection.decide(false).pair?.let { makeStoryLock(it.ownSave, it) }
            }
        }) { (selection, lock) ->
            val decision = selection.decide(cheatsLoaded())
            val paired = decision.pair
            if (paired != null && lock != null) attach(paired.ownSave, paired, lock)
            endpoint.startForGame(decision)
        }
    }

    private fun attach(id: CoopSaveId, paired: CoopPair, lock: CoopLock) {
        pair = paired
        val channel = CoopPresenceChannel(link)
        presence = CoopPresence(IosPartnerCore, ::now, ::partnerArt).also { player ->
            player.link = channel
            scope.launch { channel.received.collect(player::onReceived) }
        }
        battle.attach(object : CoopApp {
            override val coopOn = true
            override val emu = emulation
            override val overlay get() = this@IosCoopGame.overlay
            override val env = gameFacts(id, paired)
            override fun core() = PkCoopCore
            override fun setHook(hook: CoopFrameHook?) { this@IosCoopGame.hook = hook }
        }, link, EMERALD_COOP_SHA1)

        storyLock = lock
        lock.start()
        scope.launch { link.messages.collect(lock::onMessage) }
    }

    private fun makeStoryLock(id: CoopSaveId, paired: CoopPair) = CoopLock(
        link, CoopLockStore(IosCoopHost.directory() / "locks.json"), id.key,
        paired.partner.save.key, IosStoryCore, ::partySize, ::now, battle::storyPending,
    )

    private fun gameFacts(id: CoopSaveId, paired: CoopPair) = object : CoopGlueEnv {
        override val range get() = iosPreferences.coopNearbyRange
        override fun mySpot(): CoopJoinRules.Spot? {
            val spot = PkCoopCore.partnerPosition() ?: return null
            return if (spot[0] == 0) null else CoopJoinRules.Spot(spot[1], spot[2], spot[3] / 16, spot[4] / 16)
        }

        override fun partnerSpot(): CoopJoinRules.Spot? {
            val spot = presence?.lastReceived?.takeIf { it.valid } ?: return null
            return CoopJoinRules.Spot(spot.mapGroup, spot.mapNum, spot.fineX / 16, spot.fineY / 16)
        }

        override fun hostParty(): List<ByteArray> {
            val size = CoopEmeraldRam.MON_SIZE
            val count = partySize().coerceIn(0, 6)
            val data = PkApiCore.read(CoopEmeraldRam.PLAYER_PARTY, size * count)
            if (data.size < size * count) return emptyList()
            return List(count) { data.copyOfRange(it * size, (it + 1) * size) }
        }

        override fun foeSpecies(): Int = runCatching {
            Gen3BoxMon.read(PkApiCore.read(CoopEmeraldRam.ENEMY_PARTY, CoopEmeraldRam.MON_SIZE), 0).species
        }.getOrDefault(0)

        override fun partnerName() = paired.partner.name
        override fun identity() = CoopIdentity(id.name, if (look.isFemale) 1 else 0, (id.secretId shl 16) or id.trainerId)
        override fun beforeSave() = runCatching { StateFiles.backupBattery(save) != null }.getOrDefault(false)
        override fun busyChanged(busy: Boolean) { IosAchievements.session.coopBattle = busy }

        override fun storyAt(): CoopJoinRules.StoryAt {
            val held = storyLock?.ui?.value?.waiting as? LockWait.AtStory
            val event = if (held != null) CoopStory.byId(held.eventId) else CoopStorySpots.at(mySpot())
            return CoopStorySpots.storyAt(event) { trainer -> CoopStorySpots.trainerBeaten(trainer, PkApiCore::read) }
        }

        override fun playTogetherOnly() = storyLock?.ui?.value?.locked == true
        override fun pairKey() = id.key + ":" + paired.partner.peer.hex
        override fun catchStore() = FileCatchStore(IosCoopHost.directory() / "catches.txt")
        override fun log(text: String) = coopLog(text)
    }

    private fun partySize() = (PkApiCore.read(CoopEmeraldRam.PLAYER_PARTY_COUNT, 1).firstOrNull()?.toInt() ?: 0) and 0xFF

    private fun partnerArt(female: Boolean, look: LookChar?): ByteArray? {
        val key = female to look
        if (key in art || artJob?.isActive == true) return art[key]
        artJob = scope.launch {
            art[key] = withContext(Dispatchers.Default) {
                runCatching { PartnerArt.build(files, rom.readBytes(), female, look) }.getOrNull()
            }
        }
        return null
    }

    fun beforeFrame(keys: Int): Int? {
        val running = hook ?: return keys
        val allowed = running.beforeFrame(keys)
        storyLock?.onFrame()
        return allowed.takeUnless { CoopGuards.held }
    }

    fun afterFrame(field: Boolean) {
        hook?.afterFrame()
        presence?.let {
            it.myLook = look.wornHero
            it.myFemale = look.isFemale
            it.onFrame(field)
        }
    }

    fun refuseBattle(): Boolean {
        if (!battleActive) return false
        overlay.showNotice(tr("Not during a co-op battle."))
        return true
    }

    fun refuseLoad(): Boolean {
        if (refuseBattle()) return true
        if (!blocksLoad) return false
        overlay.showNotice(tr("Not while PLAY TOGETHER ONLY is on."))
        return true
    }

    fun refuseCheats(on: Boolean): Boolean {
        if (refuseBattle()) return true
        if (!CoopEndpointSession.refusesCheats(on, engaged)) return false
        overlay.showNotice(tr("Not while co-op is on."))
        return true
    }

    fun key(key: MenuKey): Boolean = battle.takeKey(key)
    fun saveForSuspend(save: () -> Boolean): Boolean = shutdown.saveIfRestored(save)
    fun resumeGame() = shutdown.resume()

    fun close() {
        shutdown.terminate()
    }

    private fun detach() {
        battle.detach()
        hook = null
        setFrameGate(null)
        storyLock?.close()
        storyLock = null
        presence?.stop()
        presence = null
        if (engaged) PkCoopCore.disable()
        pair = null
        endpoint.unbindGameSettings(settings)
        startup.cancel()
        scope.cancel()
    }
}

private object IosPartnerCore : PartnerCore {
    override fun self() = PkCoopCore.partnerPosition()
    override fun setArt(art: ByteArray?) = PkCoopCore.partnerArt(art)
    override fun place(p: PartnerPlacement?) = PkCoopCore.placePartner(p)
}

private object IosStoryCore : LockNative {
    override fun install() = PkCoopCore.installStoryLock()
    override fun uninstall() = PkCoopCore.uninstallStoryLock()
    override fun setActive(on: Boolean) = PkCoopCore.activateStoryLock(on)
    override fun held() = PkCoopCore.heldStory()
    override fun go(id: Int) = PkCoopCore.continueStory(id)
    override fun cancel() = PkCoopCore.cancelStory()
}
