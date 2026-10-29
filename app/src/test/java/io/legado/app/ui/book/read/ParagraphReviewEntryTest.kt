package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ParagraphReviewEntryTest {

    @Test
    fun retargetClickKeepsBookAndChapterArguments() {
        val click = """showCmt("123456789","654321","3","123456")"""

        assertEquals(
            """showCmt("123456789","654321","7","123456")""",
            ParagraphReviewEntry.retargetClick(click, sourceParagraphNum = 3, targetParagraphNum = 7)
        )
    }

    @Test
    fun retargetClickKeepsParagraphBaseOffset() {
        val click = """showCmt("123456789","654321","2","123456")"""

        assertEquals(
            """showCmt("123456789","654321","6","123456")""",
            ParagraphReviewEntry.retargetClick(click, sourceParagraphNum = 3, targetParagraphNum = 7)
        )
    }

    @Test
    fun retargetClickAcceptsSingleQuotes() {
        val click = "showQDCmt('10','20','5','999999')"

        assertEquals(
            "showQDCmt('10','20',\"9\",'999999')",
            ParagraphReviewEntry.retargetClick(click, sourceParagraphNum = 5, targetParagraphNum = 9)
        )
    }

    @Test
    fun retargetClickRejectsUnrelatedScript() {
        assertNull(
            ParagraphReviewEntry.retargetClick(
                """openUrl("123")""",
                sourceParagraphNum = 3,
                targetParagraphNum = 7
            )
        )
    }

    @Test
    fun retargetClickRejectsUnknownParagraph() {
        assertNull(
            ParagraphReviewEntry.retargetClick(
                """showCmt("123456789","654321","8","123456")""",
                sourceParagraphNum = 3,
                targetParagraphNum = 7
            )
        )
    }

    @Test
    fun findClickTemplateSkipsTitleAndUsesAvailableParagraph() {
        val template = ParagraphReviewEntry.findClickTemplate(
            sequenceOf(1 to """showCmt("1","2","0","123456")"""),
            targetParagraphNum = 6
        )

        assertNull(template)
    }

    @Test
    fun buildCommentUrlAppendsParagraphForFanqie() {
        val url = chapterUrl(
            """{"source":"番茄小说","book_id":"11","item_id":"22","commentUrl":"https://example.com/idea_comment?book_id=11&item_id=22&sessionid=abc"}"""
        )

        assertEquals(
            "https://example.com/idea_comment?book_id=11&item_id=22&sessionid=abc&para=5",
            ParagraphReviewEntry.buildCommentUrl(url, 5)
        )
    }

    @Test
    fun buildCommentUrlReplacesExistingPara() {
        val url = chapterUrl(
            """{"commentUrl":"https://example.com/qd_comment?bid=11&cid=22&para=benzhang"}"""
        )

        assertEquals(
            "https://example.com/qd_comment?bid=11&cid=22&para=5",
            ParagraphReviewEntry.buildCommentUrl(url, 5)
        )
    }

    @Test
    fun buildCommentUrlReplacesParagraphId() {
        val url = chapterUrl(
            """{"commentUrl":"https://example.com/qm_comment?item_id=22&book_id=11&paragraph_id=3"}"""
        )

        assertEquals(
            "https://example.com/qm_comment?item_id=22&book_id=11&paragraph_id=5",
            ParagraphReviewEntry.buildCommentUrl(url, 5)
        )
    }

    @Test
    fun buildCommentUrlConvertsChapterCommentType() {
        val url = chapterUrl(
            """{"commentUrl":"https://example.com/qm_comment?book_id=11&item_id=22&type=chapter"}"""
        )

        assertEquals(
            "https://example.com/qm_comment?book_id=11&item_id=22&paragraph_id=5",
            ParagraphReviewEntry.buildCommentUrl(url, 5)
        )
    }

    @Test
    fun buildCommentUrlKeepsQueryWhenChapterTypeComesFirst() {
        val url = chapterUrl(
            """{"commentUrl":"https://example.com/qm_comment?type=chapter&book_id=11"}"""
        )

        assertEquals(
            "https://example.com/qm_comment?book_id=11&paragraph_id=5",
            ParagraphReviewEntry.buildCommentUrl(url, 5)
        )
    }

    @Test
    fun buildCommentUrlHandlesOnlyChapterType() {
        val url = chapterUrl(
            """{"commentUrl":"https://example.com/qm_comment?type=chapter"}"""
        )

        assertEquals(
            "https://example.com/qm_comment?paragraph_id=5",
            ParagraphReviewEntry.buildCommentUrl(url, 5)
        )
    }

    @Test
    fun buildCommentUrlRejectsNonChapterUrl() {
        assertNull(ParagraphReviewEntry.buildCommentUrl("https://example.com/chapter", 5))
    }

    @Test
    fun buildCommentUrlRejectsMissingCommentUrl() {
        assertNull(ParagraphReviewEntry.buildCommentUrl(chapterUrl("""{"url":"x"}"""), 5))
    }

    @Test
    fun reviewClickPatternRecognizesKnownScripts() {
        assertTrue(ParagraphReviewEntry.isReviewClick("""showCmt("1","2","3","4")"""))
        assertTrue(ParagraphReviewEntry.isReviewClick("""showReadingBrowser("https://a","b")"""))
        assertTrue(ParagraphReviewEntry.isReviewClick("""getDP(1,2)"""))
        assertFalse(ParagraphReviewEntry.isReviewClick("""java.showBrowser("https://a")"""))
    }

    private fun chapterUrl(json: String): String {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "data:chapterUrl;base64,$encoded,{\"type\":\"qingci\"}"
    }
}
