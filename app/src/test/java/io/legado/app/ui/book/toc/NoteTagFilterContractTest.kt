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
    fun `both pages open the shared tag filter popup`() {
        val bookmark = sourceFile("src/main/java/io/legado/app/ui/book/toc/BookmarkFragment.kt")
        val highlight = sourceFile("src/main/java/io/legado/app/ui/book/toc/HighlightFragment.kt")

        listOf(bookmark, highlight).forEach { source ->
            assertTrue(source.contains("NoteTagFilterPopup("))
            assertTrue(source.contains("anchor = binding.tagFilterBar.tagFilter"))
            assertTrue(!source.contains("setSingleChoiceItems"))
            assertTrue(!source.contains("filterTags"))
            assertTrue(!source.contains("BottomSheetDialog"))
        }
        assertTrue(bookmark.contains("putExtra(\"bookmarkMode\", true)"))
        assertTrue(highlight.contains("HighlightTagManageActivity::class.java"))
    }

    @Test
    fun `all notes details page uses the same popup`() {
        val source = sourceFile("src/main/java/io/legado/app/ui/book/bookmark/AllBookmarkActivity.kt")

        assertTrue(source.contains("NoteTagFilterPopup("))
        assertTrue(source.contains("anchor = binding.tvTagFilter"))
        assertTrue(source.contains("tagFilterPopup?.update(noteTags, tagId)"))
        assertTrue(!source.contains("setSingleChoiceItems"))
    }

    @Test
    fun `popup opens below the anchor and lists every state`() {
        val layout = parseLayout("popup_note_tag_filter.xml")
        assertEquals(
            1,
            layout.getElementsByTagName("com.google.android.flexbox.FlexboxLayout").length
        )
        assertEquals(
            "@string/book_notes_manage",
            (layout.getElementsByTagName("io.legado.app.ui.widget.text.AccentTextView")
                .item(0) as Element).androidAttribute("text")
        )
        // 「管理」必须留在滚动区外，否则书签标签较多时会被滚出视野
        val scroller = layout.getElementsByTagName("androidx.core.widget.NestedScrollView")
            .item(0) as Element
        assertTrue(scroller.childElements().none { it.tagName.contains("AccentTextView") })
        assertTrue(layout.childElements().any { it.tagName.contains("AccentTextView") })

        val source = sourceFile("src/main/java/io/legado/app/ui/highlight/NoteTagFilterPopup.kt")
        assertTrue(source.contains("binding.svTags.layoutParams"))
        assertTrue(source.contains("binding.flTags.measure("))
        assertTrue(source.contains("highlight_filter_all"))
        assertTrue(source.contains("highlight_filter_untagged"))
        assertTrue(source.contains("dismiss()"))
        assertTrue(source.contains("nextSelection"))
        assertTrue(source.contains("resolveDropDownYOffset"))
        assertTrue(source.contains("applyMd3PopupStyle"))
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

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
    }
}
