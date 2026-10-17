package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTags
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class HighlightTagBackupRestoreTest {
    @Test fun annotationBackupRoundTripsTagsAndLegacyFormatWithoutLosingBaseStyleOrNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val savedLatest = preferences.all[PreferKey.onlyLatestBackup]
        val savedLocal = LocalConfig.lastBackup
        val savedIgnore = HashMap(BackupConfig.ignoreConfig)
        val savedTags = appDb.highlightTagDao.all()
        val savedLinks = appDb.highlightTagDao.links()
        val savedOrders = appDb.highlightTagDao.orders()
        val savedTimes = appDb.bookHighlightDao.all.map { it.time }
        val directory = File(context.cacheDir, "tag-backup-${UUID.randomUUID()}")
        val first = BookHighlight(bookUrl = "fixture-tag-book-a", bookName = "Tag fixture A",
            chapterUrl = "fixture-chapter-a", bookText = "标注正文", note = "保留的备注")
            .apply { applyStyle(HighlightStyle(fill = 0xFF112233.toInt(), textColor = 0xFF445566.toInt())) }
        val second = first.copy(time = first.time + 1, bookUrl = "fixture-tag-book-b",
            bookName = "Tag fixture B", note = "第二本书")
        val tags = HighlightTags.defaults().reversed().mapIndexed { index, tag ->
            tag.copy(name = "${tag.name}测试", sortOrder = index, note = "通用标签含义 $index\n跨书共用")
        } + HighlightTag(id = "local-backup", name = "本书线索", sortOrder = 4,
            bookUrl = first.bookUrl, note = "仅本书的线索说明",
            style = GSON.toJson(HighlightStyle(fill = 0x80FFE082.toInt(),
                fillShape = HighlightStyle.FillShape.PILL, bold = true, italic = true,
                underline = HighlightStyle.Underline(HighlightStyle.Kind.DOUBLE, 0xFF43864C.toInt()),
                emphasis = HighlightStyle.Deco(0xFF43864C.toInt()), shadow = HighlightStyle.Shadow(),
                fontSize = 22f, letterSpacing = 0.1f, horizontalPadding = 8f)))
        val links = listOf(HighlightTagLink(first.time, tags.first().id),
            HighlightTagLink(first.time, tags.last().id), HighlightTagLink(second.time, tags.first().id))
        val orders = tags.reversed().mapIndexed { index, tag ->
            io.legado.app.data.entities.HighlightTagOrder(first.bookUrl, tag.id, index)
        }
        val unpacked = File(directory, "unpacked")
        fun restore() = runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
        fun assertAnnotations() {
            assertEquals(first, appDb.bookHighlightDao.all.first { it.time == first.time })
            assertEquals(second, appDb.bookHighlightDao.all.first { it.time == second.time })
        }
        try {
            preferences.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
            BackupConfig.ignoreConfig[BackupConfig.annotationContentKey] = false
            appDb.bookHighlightDao.insert(first, second)
            appDb.highlightTagDao.restore(tags, links, listOf(first.time, second.time), orders)
            runBlocking(Dispatchers.IO) {
                Backup.backupLocked(context, directory.path, uploadWebDav = false)
            }
            unpacked.mkdirs()
            ZipFile(File(directory, "backup.zip")).use { zip ->
                assertNull("Independent annotation backup", zip.getEntry("bookshelf.json"))
                for (name in listOf("highlight.json", "highlightTag.json", "highlightTagLink.json", "highlightTagOrder.json")) {
                    val entry = requireNotNull(zip.getEntry(name))
                    File(unpacked, name).writeBytes(zip.getInputStream(entry).use { it.readBytes() })
                }
                val raw = GSON.fromJson(File(unpacked, "highlight.json").readText(),
                    Array<BookHighlight>::class.java).filter { it.time in listOf(first.time, second.time) }
                assertEquals(listOf(first, second).toSet(), raw.toSet())
                // The original file contains base styles; older versions ignore the two extensions.
                assertTrue(raw.all { it.styleObj().fill == 0xFF112233.toInt() })
                File(unpacked, "highlight.json").writeText(GSON.toJson(raw))
                File(unpacked, "highlightTagLink.json").writeText(GSON.toJson(links))
            }
            appDb.bookHighlightDao.delete(first, second)
            appDb.highlightTagDao.saveTags(emptyList())
            repeat(2) { restore() }
            assertAnnotations()
            assertEquals(tags, appDb.highlightTagDao.all())
            assertEquals(links.toSet(), appDb.highlightTagDao.links().toSet())
            assertEquals(orders.toSet(), appDb.highlightTagDao.orders().toSet())
            assertEquals(tags.last().id, appDb.highlightTagDao.forBook(first.bookUrl).first().id)
            assertFalse(appDb.highlightTagDao.forBook(second.bookUrl).any { it.bookUrl.isNotEmpty() })
            assertEquals(tags.first().styleObj(),
                HighlightTags.effectiveStyle(first, tags, appDb.highlightTagDao.idsForHighlight(first.time)))

            // A backup written before label notes existed must not erase notes added locally.
            val tagFile = File(unpacked, "highlightTag.json")
            val fullTagJson = tagFile.readText()
            val legacyTags = org.json.JSONArray(fullTagJson)
            for (index in 0 until legacyTags.length()) legacyTags.getJSONObject(index).remove("note")
            tagFile.writeText(legacyTags.toString())
            repeat(2) { restore() }
            assertEquals(tags, appDb.highlightTagDao.all())
            tagFile.writeText(fullTagJson)

            // A pre-label backup has only highlight.json. Upsert must preserve existing label links.
            File(unpacked, "highlightTag.json").delete()
            File(unpacked, "highlightTagLink.json").delete()
            File(unpacked, "highlightTagOrder.json").delete()
            repeat(2) { restore() }
            assertAnnotations()
            assertEquals(tags, appDb.highlightTagDao.all())
            assertEquals(links.toSet(), appDb.highlightTagDao.links().toSet())

            // Even pre-style backups with bgColor/textColor still restore through the legacy adapter.
            File(unpacked, "highlight.json").writeText(
                """[{"time":${first.time},"bookUrl":"${first.bookUrl}","chapterUrl":"${first.chapterUrl}","bookName":"${first.bookName}","bookText":"标注正文","note":"旧版备注","bgColor":19,"textColor":23}]""")
            restore()
            val legacy = appDb.bookHighlightDao.all.first { it.time == first.time }
            assertEquals("旧版备注", legacy.note)
            assertEquals(19, legacy.styleObj().fill)
            assertEquals(23, legacy.styleObj().textColor)
            assertEquals(links.toSet(), appDb.highlightTagDao.links().toSet())

            // A partially downloaded extension must not erase global settings or local associations.
            File(unpacked, "highlightTag.json").writeText("[]")
            restore()
            assertEquals(tags, appDb.highlightTagDao.all())
            assertEquals(links.toSet(), appDb.highlightTagDao.links().toSet())

            // Both explicit empty extension files mean the user really removed every label.
            File(unpacked, "highlightTagLink.json").writeText("[]")
            File(unpacked, "highlightTagOrder.json").writeText("[]")
            restore()
            assertTrue(appDb.highlightTagDao.all().isEmpty())
            assertTrue(appDb.highlightTagDao.links().isEmpty())
            assertEquals("旧版备注", appDb.bookHighlightDao.all.first { it.time == first.time }.note)
            assertEquals(19, appDb.bookHighlightDao.all.first { it.time == first.time }.styleObj().fill)
        } finally {
            appDb.bookHighlightDao.delete(first, second)
            appDb.highlightTagDao.restore(savedTags, savedLinks, savedTimes, savedOrders)
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(savedIgnore)
            preferences.edit().apply {
                if (savedLatest == null) remove(PreferKey.onlyLatestBackup)
                else putBoolean(PreferKey.onlyLatestBackup, savedLatest as Boolean)
            }.commit()
            LocalConfig.lastBackup = savedLocal
            directory.deleteRecursively()
        }
    }
}
