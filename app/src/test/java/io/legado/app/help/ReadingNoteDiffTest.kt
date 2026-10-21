package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingNoteDiffTest {
    @Test fun `parts retain before and after text`() {
        val before = "他抬头看见远处的灯火。"
        val after = "他抬起头，看见远处的灯光。"
        val parts = ReadingNoteDiff.parts(before, after)

        assertEquals(before, ReadingNoteDiff.before(parts))
        assertEquals(after, ReadingNoteDiff.after(parts))
        assertTrue(parts.any { it.type == ReadingNoteDiff.Type.DELETE })
        assertTrue(parts.any { it.type == ReadingNoteDiff.Type.INSERT })
    }

    @Test fun `whitespace-only changes are not meaningful`() {
        assertFalse(ReadingNoteDiff.meaningfulChange("一段  原文", "一段\n原文"))
        assertTrue(ReadingNoteDiff.meaningfulChange("原文。", "原文？"))
    }
}
