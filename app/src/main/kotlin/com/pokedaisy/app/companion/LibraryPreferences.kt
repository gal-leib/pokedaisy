package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.LibraryView
import kotlin.time.Clock
import kotlinx.serialization.json.*

/** Stable sort IDs used by the library hosts. */
enum class LibrarySort(val id: Int, val label: String) {
    NAME(0, "NAME"), LAST_PLAYED(1, "LAST PLAYED"), DATE_ADDED(2, "DATE ADDED");
    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } ?: NAME }
}

/** Portable library preferences for hosts without Android's existing Prefs. IDs should survive container changes. */
class LibraryPreferences(
    read: () -> String?, private val write: (String) -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Record(
        val grid: Boolean = true, val sort: LibrarySort = LibrarySort.NAME, val view: LibraryView = LibraryView.ALL,
        val favorites: Set<String> = emptySet(), val played: Map<String, Long> = emptyMap(),
        val added: Map<String, Long> = emptyMap(), val last: String? = null,
    )
    private var record by mutableStateOf(decode(read()))
    val grid get() = record.grid
    val sort get() = record.sort
    val view get() = record.view
    val favorites get() = record.favorites
    val played get() = record.played
    val added get() = record.added
    val last get() = record.last

    fun setGrid(on: Boolean) = update(record.copy(grid = on))
    fun setSort(sort: LibrarySort) = update(record.copy(sort = sort))
    fun setView(view: LibraryView) = update(record.copy(view = view))
    fun toggleFavorite(id: String) = update(record.copy(favorites =
        if (id in record.favorites) record.favorites - id else record.favorites + id))
    fun recordPlay(id: String) = update(record.copy(played = record.played + (id to now()), last = id))
    fun recordAdded(ids: List<String>) {
        val fresh = ids.filter { it !in record.added }
        if (fresh.isNotEmpty()) update(record.copy(added = record.added + fresh.associateWith { now() }))
    }

    private fun update(value: Record) {
        val encoded = buildJsonObject {
            put("grid", value.grid); put("sort", value.sort.id); put("view", value.view.id)
            put("favorites", JsonArray(value.favorites.map(::JsonPrimitive)))
            putJsonObject("played") { value.played.forEach { (id, time) -> put(id, time) } }
            putJsonObject("added") { value.added.forEach { (id, time) -> put(id, time) } }
            value.last?.let { put("last", it) }
        }.toString()
        write(encoded)
        record = value
    }

    private fun decode(raw: String?): Record = runCatching {
        val obj = raw?.let { Json.parseToJsonElement(it) as? JsonObject } ?: return@runCatching Record()
        fun primitive(key: String) = obj[key] as? JsonPrimitive
        fun dates(key: String) = (obj[key] as? JsonObject)?.mapNotNull { (id, time) ->
            (time as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { id to it }
        }?.toMap().orEmpty()
        Record(grid = primitive("grid")?.booleanOrNull ?: true,
            sort = LibrarySort.of(primitive("sort")?.intOrNull ?: 0),
            view = LibraryView.of(primitive("view")?.intOrNull ?: 0),
            favorites = (obj["favorites"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),
            played = dates("played"), added = dates("added"), last = primitive("last")?.contentOrNull)
    }.getOrDefault(Record())
}
