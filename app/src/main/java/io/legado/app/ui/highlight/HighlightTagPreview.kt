package io.legado.app.ui.highlight

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.page.HighlightDraw

/** Preview uses the same line drawing as the reader, including waves and double lines. */
class HighlightTagPreview @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    var highlightStyle: HighlightStyle = HighlightStyle()
        set(value) {
            field = value.normalized()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val textLayout = layout ?: return
        val style = highlightStyle
        for (line in 0 until textLayout.lineCount) {
            HighlightDraw.drawRun(
                canvas,
                compoundPaddingLeft + textLayout.getLineLeft(line),
                compoundPaddingLeft + textLayout.getLineRight(line),
                (extendedPaddingTop + textLayout.getLineBaseline(line)).toFloat(),
                textSize,
                paint.ascent(), paint.descent(),
                style.underline, style.strike, style.box, currentTextColor
            )
        }
    }
}
