package io.legado.app.ui.config

import io.legado.app.ui.widget.image.coverBitmapCacheKey
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverTitleColorConfigTest {

    private val sourceRoot = listOf(File("src/main"), File("app/src/main"))
        .first { it.isDirectory }

    @Test
    fun `text and shadow colors separate cover bitmap caches`() {
        assertNotEquals(
            coverBitmapCacheKey(
                "书名", "作者", 300, 400, false, true,
                0xFF000000.toInt(), 0xFFFFFFFF.toInt()
            ),
            coverBitmapCacheKey(
                "书名", "作者", 300, 400, false, true,
                0xFF123456.toInt(), 0xFFFFFFFF.toInt()
            )
        )
        assertNotEquals(
            coverBitmapCacheKey(
                "书名", "作者", 300, 400, false, true,
                0xFF000000.toInt(), 0xFFFFFFFF.toInt()
            ),
            coverBitmapCacheKey(
                "书名", "作者", 300, 400, false, true,
                0xFF000000.toInt(), 0xFFABCDEF.toInt()
            )
        )
    }

    @Test
    fun `cover model resolves every style from day and night keys`() {
        val bookCover = sourceRoot.resolve("java/io/legado/app/model/BookCover.kt").readText()
        listOf(
            "PreferKey.coverTitleCustomColor",
            "PreferKey.coverTitleCustomColorN",
            "PreferKey.coverTitleTextColorN",
            "PreferKey.coverTitleShadowColorN",
            "PreferKey.coverHorizontalN",
            "PreferKey.coverTitleAdaptiveN",
            "PreferKey.coverKeepPunctuationN",
            "PreferKey.coverFontN",
            "PreferKey.coverCustomFontSizeN",
            "PreferKey.coverTitleLargeSizeN",
            "PreferKey.coverTitleSmallSizeN",
            "PreferKey.coverAuthorLargeSizeN",
            "PreferKey.coverAuthorSmallSizeN",
        ).forEach { key -> assertTrue("BookCover reads $key", bookCover.contains(key)) }
        assertTrue(bookCover.contains("private fun dayOrNightBoolean"))
        assertTrue(bookCover.contains("private fun dayOrNightInt"))
        assertTrue(bookCover.contains("private fun dayOrNightString"))
    }

    @Test
    fun `font settings seed night values and edit the selected theme`() {
        val fragment = sourceRoot
            .resolve("java/io/legado/app/ui/config/CoverFontConfigFragment.kt")
            .readText()
        assertTrue(fragment.contains("seedNightStyleFromDay()"))
        assertTrue(fragment.contains("PreferKey.coverFontN"))
        assertTrue(fragment.contains("PreferKey.coverTitleLargeSizeN"))
        assertTrue(fragment.contains("selectingNightFont"))
        assertTrue(fragment.contains("EXTRA_NIGHT"))
        assertTrue(fragment.contains("preferenceScreen.removePreference"))
        assertTrue(fragment.contains("BookCover.upDefaultCover()"))
        assertTrue(fragment.contains("EventBus.BOOKSHELF_REFRESH"))

        val layout = sourceRoot.resolve("res/xml/pref_config_cover_font.xml").readText()
        listOf(
            "@string/day",
            "@string/night",
            "coverHorizontalN",
            "coverFontN",
            "coverCustomFontSizeN",
            "coverTitleTextColorN",
            "coverTitleShadowColorN",
            "coverTitleCustomColorN",
        ).forEach { value -> assertTrue("font layout contains $value", layout.contains(value)) }
    }

    @Test
    fun `cover settings expose separate day and night style entries`() {
        val layout = sourceRoot.resolve("res/xml/pref_config_cover.xml").readText()
        assertTrue(layout.contains("android:key=\"coverFontConfig\""))
        assertTrue(layout.contains("android:key=\"coverFontConfigN\""))
        assertTrue(!layout.contains("coverTitleCustomColor"))
        assertTrue(!layout.contains("ColorPreference"))
    }

    @Test
    fun `backup restores night styles and both cover fonts`() {
        val backupConfig = sourceRoot
            .resolve("java/io/legado/app/help/storage/BackupConfig.kt")
            .readText()
        listOf(
            "PreferKey.coverTitleCustomColorN",
            "PreferKey.coverTitleTextColorN",
            "PreferKey.coverTitleShadowColorN",
            "PreferKey.coverHorizontalN",
            "PreferKey.coverTitleAdaptiveN",
            "PreferKey.coverKeepPunctuationN",
            "PreferKey.coverFontN",
            "PreferKey.coverCustomFontSizeN",
            "PreferKey.coverTitleLargeSizeN",
            "PreferKey.coverTitleSmallSizeN",
            "PreferKey.coverAuthorLargeSizeN",
            "PreferKey.coverAuthorSmallSizeN",
        ).forEach { key -> assertTrue("backup covers $key", backupConfig.contains(key)) }

        val backup = sourceRoot.resolve("java/io/legado/app/help/storage/Backup.kt").readText()
        assertTrue(backup.contains("BookCover.fontNightBackupFileName"))
        assertTrue(backup.contains("PreferKey.coverFontN"))

        val restore = sourceRoot.resolve("java/io/legado/app/help/storage/Restore.kt").readText()
        assertTrue(restore.contains("BookCover.fontNightBackupFileName"))
        assertTrue(restore.contains("PreferKey.coverFontN"))
        assertTrue(restore.contains("PreferKey.coverHorizontalN"))
    }
}
