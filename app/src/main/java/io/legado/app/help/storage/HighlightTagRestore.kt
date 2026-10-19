package io.legado.app.help.storage

import com.google.gson.JsonParser
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagOrder

/** Older backups cannot express a note edit; an explicit empty note still clears it. */
internal fun preserveLegacyTagNotes(
    json: String, tags: List<HighlightTag>, current: List<HighlightTag>
): List<HighlightTag> {
    val rows = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject }
        .mapNotNull { row -> row.get("id")?.asString?.let { it to row } }.toMap()
    val notesPresent = rows.values.mapNotNull { row ->
        row.get("id")?.asString?.takeIf { row.has("note") && !row.get("note").isJsonNull }
    }.toHashSet()
    val existing = current.associateBy { it.id }
    val commonPresent = rows.values.mapNotNull { row ->
        row.get("id")?.asString?.takeIf { row.has("common") && !row.get("common").isJsonNull }
    }.toHashSet()
    return tags.map { tag ->
        val row = rows[tag.id]
        tag.copy(note = if (tag.id in notesPresent) tag.note.orEmpty()
            else existing[tag.id]?.note.orEmpty(),
            common = if (tag.id in commonPresent) tag.common
                else existing[tag.id]?.common ?: tag.bookUrl.isEmpty(),
            bookmarkCommon = if (row?.has("bookmarkCommon") == true) tag.bookmarkCommon
                else existing[tag.id]?.bookmarkCommon ?: false,
            bookmarkSortOrder = if (row?.has("bookmarkSortOrder") == true) tag.bookmarkSortOrder
                else existing[tag.id]?.bookmarkSortOrder ?: 0)
    }
}

/** Pre-library backups cannot express an intentional visibility edit. */
internal fun preserveLegacyTagOrders(
    tagJson: String, orderJson: String, orders: List<HighlightTagOrder>, current: List<HighlightTagOrder>
): List<HighlightTagOrder> {
    val legacyIds = JsonParser.parseString(tagJson).asJsonArray.mapNotNull {
        val row = it.asJsonObject
        row.get("id")?.asString?.takeIf { !row.has("common") }
    }.toHashSet()
    val visibilityPresent = JsonParser.parseString(orderJson).asJsonArray.mapNotNull {
        val row = it.asJsonObject
        if (row.has("hidden")) row.get("bookUrl").asString to row.get("tagId").asString else null
    }.toHashSet()
    val existing = current.associateBy { it.bookUrl to it.tagId }
    val incoming = orders.mapTo(hashSetOf()) { it.bookUrl to it.tagId }
    return orders.map { order ->
        val key = order.bookUrl to order.tagId
        if (order.tagId in legacyIds && key !in visibilityPresent)
            order.copy(hidden = existing[key]?.hidden ?: order.hidden) else order
    } + current.filter { it.hidden && it.tagId in legacyIds && it.bookUrl to it.tagId !in incoming }
}
