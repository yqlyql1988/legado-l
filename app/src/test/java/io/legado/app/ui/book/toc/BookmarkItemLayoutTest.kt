package io.legado.app.ui.book.toc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookmarkItemLayoutTest {

    @Test
    fun `bookmark item keeps bindings and card presentation`() {
        val root = parse("item_bookmark.xml")

        assertEquals("LinearLayout", root.tagName)
        assertEquals("10dp", root.androidAttribute("paddingStart"))
        assertEquals("10dp", root.androidAttribute("paddingEnd"))

        val children = root.childElements()
        assertEquals(2, children.size)
        val header = children[0]
        assertEquals("include", header.tagName)
        assertEquals("@layout/item_note_chapter_header", header.getAttribute("layout"))
        assertEquals("@+id/chapter_header", header.androidAttribute("id"))

        val card = children[1]
        assertEquals("androidx.cardview.widget.CardView", card.tagName)
        assertEquals("@+id/card", card.androidAttribute("id"))
        assertEquals("3dp", card.androidAttribute("layout_marginTop"))
        assertEquals("3dp", card.androidAttribute("layout_marginBottom"))
        assertEquals("?android:attr/selectableItemBackground", card.androidAttribute("foreground"))
        assertEquals("@color/background_card", card.appAttribute("cardBackgroundColor"))
        assertEquals("8dp", card.appAttribute("cardCornerRadius"))
        assertEquals("0dp", card.appAttribute("cardElevation"))

        val content = card.firstChildElement()
        assertEquals("LinearLayout", content.tagName)
        assertEquals("12dp", content.androidAttribute("padding"))

        val fields = content.childElements().associateBy { it.androidAttribute("id") }
        assertEquals(
            setOf("@+id/tv_book_text", "@+id/tv_content", "@+id/tag_labels", "@+id/tv_date"),
            fields.keys
        )
        val quote = fields.getValue("@+id/tv_book_text")
        assertEquals("14sp", quote.androidAttribute("textSize"))
        assertEquals("@color/primaryText", quote.androidAttribute("textColor"))
        assertEquals("2", quote.androidAttribute("maxLines"))
        val note = fields.getValue("@+id/tv_content")
        assertEquals("12sp", note.androidAttribute("textSize"))
        assertEquals("@color/secondaryText", note.androidAttribute("textColor"))
        assertEquals("2", note.androidAttribute("maxLines"))
        val date = fields.getValue("@+id/tv_date")
        assertEquals("11sp", date.androidAttribute("textSize"))
        assertEquals("@color/secondaryText", date.androidAttribute("textColor"))
    }

    @Test
    fun `chapter header keeps chapter name and count`() {
        val root = parse("item_note_chapter_header.xml")

        assertEquals("LinearLayout", root.tagName)
        val fields = root.childElements().associateBy { it.androidAttribute("id") }
        assertEquals(
            setOf("@+id/tv_chapter_name", "@+id/tv_chapter_count"),
            fields.keys
        )
        val name = fields.getValue("@+id/tv_chapter_name")
        assertEquals("14sp", name.androidAttribute("textSize"))
        assertEquals("@color/primaryText", name.androidAttribute("textColor"))
        assertEquals("", name.androidAttribute("textStyle"))
        val count = fields.getValue("@+id/tv_chapter_count")
        assertEquals("11sp", count.androidAttribute("textSize"))
        assertEquals("@color/secondaryText", count.androidAttribute("textColor"))
    }

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/$name"))
            .documentElement

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(appNamespace, name)

    private fun Element.firstChildElement(): Element = childElements().single()

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val appNamespace = "http://schemas.android.com/apk/res-auto"
    }
}
