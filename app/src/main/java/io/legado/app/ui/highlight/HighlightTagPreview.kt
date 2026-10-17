package io.legado.app.ui.highlight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.utils.dpToPx
import kotlin.math.ceil

/** Fits the complete sample, including decorations, using the reader's style renderer. */
class HighlightTagPreview @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private val basePaint = TextPaint(paint)

    var highlightStyle: HighlightStyle = HighlightStyle()
        set(value) {
            field = value.normalized()
            requestLayout()
            invalidate()
        }

    private data class Geometry(
        val width: Float, val height: Float,
        val textStart: Float, val baseline: Float,
        val fillLeft: Float, val fillRight: Float,
        val fillTop: Float, val fillBottom: Float, val radius: Float
    )

    private fun geometry(paint: Paint, sample: String): Geometry {
        val style = highlightStyle
        val fm = paint.fontMetrics
        val ink = Rect().also { paint.getTextBounds(sample, 0, sample.length, it) }
        val textWidth = paint.measureText(sample)
        val lineHeight = fm.descent - fm.ascent
        val baseline = -fm.ascent
        val band = HighlightGeometry.fillBand(baseline, paint.textSize, lineHeight,
            style.resolvedFillShape, 1f.dpToPx())
        val radius = (band.bottom - band.top) / 2f * style.resolvedPillPaddingScale
        val cap = maxOf(style.resolvedHorizontalPadding?.dpToPx() ?: 4f.dpToPx(),
            if (style.fill != 0 && style.resolvedFillShape == HighlightStyle.FillShape.PILL) {
                HighlightGeometry.pillClearance(radius, baseline + ink.top, baseline + ink.bottom,
                    band.top, band.bottom, 1f.dpToPx())
            } else 0f)
        val shadow = style.shadow
        val margin = 2f.dpToPx()
        val top = margin + maxOf(0f, (shadow?.radius ?: 0f) - (shadow?.dy ?: 0f))
        val left = margin + maxOf(0f, (shadow?.radius ?: 0f) - (shadow?.dx ?: 0f))
        val right = margin + maxOf(0f, (shadow?.radius ?: 0f) + (shadow?.dx ?: 0f))
        val textStart = left + cap - minOf(0, ink.left)
        val bottom = maxOf(lineHeight,
            style.underline?.let {
                HighlightGeometry.underlineRenderBottom(baseline, it.distance.dpToPx(),
                    it.width.dpToPx(), it.kind)
            } ?: 0f,
            lineHeight + if (style.emphasis != null) 6f.dpToPx() else 0f)
        return Geometry(
            textStart + maxOf(textWidth, ink.right.toFloat()) + cap + right,
            top + bottom + margin + maxOf(0f, (shadow?.radius ?: 0f) + (shadow?.dy ?: 0f)),
            textStart, top + baseline,
            textStart + minOf(0, ink.left) - cap,
            textStart + maxOf(textWidth, ink.right.toFloat()) + cap,
            top + band.top, top + band.bottom, radius
        )
    }

    private inline fun withPreview(block: (Paint, String, Geometry) -> Unit) {
        val sample = text?.toString().orEmpty()
        val style = highlightStyle
        val paint = HighlightDraw.obtainTextPaint(basePaint, style,
            style.textColor.takeIf { it != 0 } ?: currentTextColor, sample)
        try {
            block(paint, sample, geometry(paint, sample))
        } finally {
            HighlightDraw.recycleTextPaint(paint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        withPreview { _, _, bounds ->
            val measuredWidth = resolveSize(maxOf(suggestedMinimumWidth,
                ceil(bounds.width).toInt() + paddingLeft + paddingRight), widthMeasureSpec)
            val scale = minOf(1f, (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0) /
                bounds.width.coerceAtLeast(1f))
            val measuredHeight = resolveSize(maxOf(suggestedMinimumHeight,
                ceil(bounds.height * scale).toInt() + paddingTop + paddingBottom), heightMeasureSpec)
            setMeasuredDimension(measuredWidth, measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) = withPreview { paint, sample, bounds ->
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
        val scale = minOf(1f, contentWidth / bounds.width.coerceAtLeast(1f),
            contentHeight / bounds.height.coerceAtLeast(1f))
        val x = if (gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL)
            (contentWidth - bounds.width * scale) / 2f else 0f
        val y = if (gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL)
            (contentHeight - bounds.height * scale) / 2f else 0f
        val save = canvas.save()
        canvas.translate(paddingLeft + x, paddingTop + y)
        canvas.scale(scale, scale)
        val style = highlightStyle
        if (style.fill != 0) {
            HighlightDraw.drawFillRun(canvas, bounds.fillLeft, bounds.fillRight,
                bounds.fillTop, bounds.fillBottom, style.fill, style.resolvedFillShape, bounds.radius)
        }
        canvas.drawText(sample, bounds.textStart, bounds.baseline, paint)
        val end = bounds.textStart + paint.measureText(sample)
        HighlightDraw.drawRun(canvas, bounds.textStart, end, bounds.baseline, bounds.height,
            paint.ascent(), paint.descent(), style.underline, style.strike, style.box, paint.color)
        style.emphasis?.let { emphasis ->
            var offset = 0
            while (offset < sample.length) {
                val next = sample.offsetByCodePoints(offset, 1)
                HighlightDraw.drawEmphasis(canvas,
                    bounds.textStart + paint.measureText(sample, 0, offset),
                    bounds.textStart + paint.measureText(sample, 0, next),
                    bounds.baseline + paint.descent() + 6f.dpToPx(),
                    emphasis.color.takeIf { it != 0 } ?: paint.color)
                offset = next
            }
        }
        canvas.restoreToCount(save)
    }
}
