package io.legado.app.ui.book.toc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NoteTagFilterContractTest {

    @Test
    fun `bookmark and highlight pages share the same filter bar`() {
        val bookmarkLayout = parseLayout("fragment_bookmark.xml")
        val highlightLayout = parseLayout("fragment_highlight.xml")

        listOf(bookmarkLayout, highlightLayout).forEach { root ->
            val includes = root.getElementsByTagName("include")
            assertEquals(1, includes.length)
            assertEquals(
                "@layout/view_note_filter_bar",
                (includes.item(0) as Element).getAttribute("layout")
            )
            assertEquals("@+id/tag_filter_bar", (includes.item(0) as Element).androidAttribute("id"))
            assertEquals(0, root.getElementsByTagName("HorizontalScrollView").length)
        }
    }

    @Test
    fun `both pages open the shared tag filter sheet`() {
        val bookmark = sourceFile("src/main/java/io/legado/app/ui/book/toc/BookmarkFragment.kt")
        val highlight = sourceFile("src/main/java/io/legado/app/ui/book/toc/HighlightFragment.kt")

        listOf(bookmark, highlight).forEach { source ->
            assertTrue(source.contains("binding.tagFilterBar.tagFilter.setOnClickListener"))
            assertTrue(source.contains("NoteTagFilterSheet("))
            assertTrue(source.contains("noteTagFilterLabel("))
            assertTrue(!source.contains("setSingleChoiceItems"))
            assertTrue(!source.contains("filterTags"))
        }
        assertTrue(bookmark.contains("putExtra(\"bookmarkMode\", true)"))
        assertTrue(highlight.contains("HighlightTagManageActivity::class.java"))
    }

    @Test
    fun `all notes details page uses the same sheet`() {
        val source = sourceFile("src/main/java/io/legado/app/ui/book/bookmark/AllBookmarkActivity.kt")

        assertTrue(source.contains("NoteTagFilterSheet("))
        assertTrue(source.contains("noteTagFilterLabel("))
        assertTrue(!source.contains("setSingleChoiceItems"))
    }

    @Test
    fun `sheet lists every state and closes on selection`() {
        val layout = parseLayout("dialog_note_tag_filter.xml")
        assertEquals(1, layout.getElementsByTagName("com.google.android.flexbox.FlexboxLayout").length)
        val manage = layout.getElementsByTagName("io.legado.app.ui.widget.text.AccentTextView")
        assertEquals(1, manage.length)
        assertEquals(
            "@string/book_notes_manage",
            (manage.item(0) as Element).androidAttribute("text")
        )

        val source = sourceFile("src/main/java/io/legado/app/ui/highlight/NoteTagFilterSheet.kt")
        assertTrue(source.contains("highlight_filter_all"))
        assertTrue(source.contains("highlight_filter_untagged"))
        assertTrue(source.contains("dialog.dismiss()"))
        assertTrue(source.contains("nextSelection"))
        assertTrue(source.contains("fun noteTagFilterLabel("))
    }

    @Test
    fun `highlight items render the same tag chips as bookmarks`() {
        val layout = parseLayout("item_highlight.xml")
        assertEquals(
            1,
            layout.getElementsByTagName("com.google.android.flexbox.FlexboxLayout").length
        )
        val adapter = sourceFile("src/main/java/io/legado/app/ui/book/toc/HighlightAdapter.kt")
        assertTrue(adapter.contains("highlightTagChip(tag, true, compact = true)"))
        assertTrue(adapter.contains("callback.onTagClick(tag.id)"))
        assertTrue(adapter.contains("fun onTagClick(id: String)"))
    }

    private fun parseLayout(name: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/$name"))
            .documentElement

    private fun sourceFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
    }
}
