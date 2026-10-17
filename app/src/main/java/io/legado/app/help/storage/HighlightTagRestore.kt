package io.legado.app.help.storage

import com.google.gson.JsonParser
import io.legado.app.data.entities.HighlightTag

/** Older backups cannot express a note edit; an explicit empty note still clears it. */
internal fun preserveLegacyTagNotes(
    json: String, tags: List<HighlightTag>, current: List<HighlightTag>
): List<HighlightTag> {
    val notesPresent = JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
        val row = element.asJsonObject
        row.get("id")?.asString?.takeIf { row.has("note") && !row.get("note").isJsonNull }
    }.toHashSet()
    val existing = current.associateBy { it.id }
    return tags.map { tag ->
        tag.copy(note = if (tag.id in notesPresent) tag.note.orEmpty()
            else existing[tag.id]?.note.orEmpty())
    }
}
