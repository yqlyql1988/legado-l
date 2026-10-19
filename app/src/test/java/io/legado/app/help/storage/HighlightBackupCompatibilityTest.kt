package io.legado.app.help.storage

import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightTag
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightBackupCompatibilityTest {
    @Test fun `old backups preserve bookmark favorites while explicit new preferences replace them`() {
        val current = listOf(HighlightTag("label", "伏笔", bookmarkCommon = true, bookmarkSortOrder = 8))
        val old = """[{"id":"label","name":"伏笔","common":true}]"""
        val restored = preserveLegacyTagNotes(old, GSON.fromJsonArray<HighlightTag>(old).getOrThrow(), current).single()
        assertTrue(restored.bookmarkCommon)
        assertEquals(8, restored.bookmarkSortOrder)
        val newer = """[{"id":"label","name":"伏笔","bookmarkCommon":false,"bookmarkSortOrder":2}]"""
        val explicit = preserveLegacyTagNotes(newer, GSON.fromJsonArray<HighlightTag>(newer).getOrThrow(), current).single()
        assertFalse(explicit.bookmarkCommon)
        assertEquals(2, explicit.bookmarkSortOrder)
    }

    @Test
    fun `old tag backups preserve local notes while explicit empty notes can clear them`() {
        val json = """[{"id":"old","name":"伏笔"},{"id":"clear","name":"金句","note":""},
            {"id":"null","name":"人物","note":null},{"id":"new","name":"地点"}]"""
        val restored = GSON.fromJsonArray<HighlightTag>(json).getOrThrow()
        val current = listOf(HighlightTag(id = "old", note = "保留本机说明"),
            HighlightTag(id = "clear", note = "可以清空"), HighlightTag(id = "null", note = "保留空值说明"))
        val notes = preserveLegacyTagNotes(json, restored, current).associate { it.id to it.note }
        assertEquals(mapOf("old" to "保留本机说明", "clear" to "", "null" to "保留空值说明", "new" to ""), notes)
    }

    @Test
    fun `legacy colors are converted without replacing current styles`() {
        val json = """[{"bgColor": 1, "textColor": 2}]"""
        val legacy = GSON.fromJsonArray<BookHighlight>(json).getOrThrow().single()
        val current = BookHighlight(time = 2).apply {
            applyStyle(HighlightStyle(fill = 7))
        }

        applyLegacyHighlightStyles(json, listOf(legacy))
        applyLegacyHighlightStyles("""[{"bgColor": 3}]""", listOf(current))

        assertEquals(HighlightStyle(fill = 1, textColor = 2), legacy.styleObj())
        assertEquals(BookHighlight.UNKNOWN_TITLE_LENGTH, legacy.layoutTitleLength)
        assertEquals(HighlightStyle(fill = 7), current.styleObj())
    }

    @Test
    fun `older rule json defaults title matching to false`() {
        val rule = GSON.fromJsonArray<HighlightRule>(
            """[{"id":1,"name":"rule","pattern":"text"}]"""
        ).getOrThrow().single().normalizeForRestore()

        assertFalse(rule.applyToTitle)
        assertTrue(rule.applyToBody)
        assertEquals(HighlightRule.DEFAULT_TIMEOUT_MILLISECONDS, rule.timeoutMillisecond)
        assertEquals(HighlightStyle(), rule.styleObj())
    }

    @Test
    fun `rule json preserves title only scope`() {
        val rule = GSON.fromJsonArray<HighlightRule>(
            """[{"pattern":"text","applyToTitle":true,"applyToBody":false}]"""
        ).getOrThrow().single().normalizeForRestore()

        assertTrue(rule.applyToTitle)
        assertFalse(rule.applyToBody)
    }

    @Test
    fun `legacy rules receive distinct uuids and new backups preserve them`() {
        val legacy = GSON.fromJsonArray<HighlightRule>(
            """[{"pattern":"one"},{"uuid":null,"pattern":"two"}]"""
        ).getOrThrow().map(HighlightRule::normalizeForRestore)

        assertTrue(legacy.all { it.uuid.isNotBlank() })
        assertNotEquals(legacy[0].uuid, legacy[1].uuid)

        val restored = GSON.fromJsonArray<HighlightRule>(GSON.toJson(legacy))
            .getOrThrow()
            .map(HighlightRule::normalizeForRestore)
        assertEquals(legacy.map { it.uuid }, restored.map { it.uuid })
    }

    @Test
    fun `backup and restore include automatic highlight rules`() {
        val backup = projectFile("src/main/java/io/legado/app/help/storage/Backup.kt").readText()
        val restore = projectFile("src/main/java/io/legado/app/help/storage/Restore.kt").readText()

        assertTrue(backup.contains("\"highlightRule.json\""))
        assertTrue(backup.contains("appDb.highlightRuleDao.all"))
        assertTrue(backup.contains("writeEmpty = true"))
        assertTrue(restore.contains("fileToListT<HighlightRule>(path, \"highlightRule.json\")"))
        assertTrue(restore.contains("appDb.highlightRuleDao.replaceAll"))
        assertTrue(restore.contains("HighlightRule::normalizeForRestore"))
        assertTrue(restore.contains("恢复高亮规则出错"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
