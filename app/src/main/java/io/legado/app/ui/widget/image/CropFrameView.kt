package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.dpToPx

class CropFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var aspectRatio: Float = 5f
        set(value) {
            field = value.coerceAtLeast(1f)
            requestLayout()
            invalidate()
        }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dpToPx().toFloat()
    }
    private val frame = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        framePaint.color = context.accentColor
    }

    fun frameRect(): RectF = RectF(frame)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val frameWidth = width.toFloat()
        var frameHeight = frameWidth / aspectRatio
        if (frameHeight > height * 0.65f) {
            frameHeight = height * 0.65f
        }
        val actualWidth = frameHeight * aspectRatio
        val left = (width - actualWidth) / 2f
        val top = (height - frameHeight) / 2f
        frame.set(left, top, left + actualWidth, top + frameHeight)

        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dimPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(frame, framePaint)
    }
}
