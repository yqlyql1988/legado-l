package io.legado.app.ui.book.toc

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * 目录章节行底部的自然阅读进度线。
 *
 * 直接画在行的前景上，不参与布局、不改变行高，也不受 View 复用、可见性
 * 和覆盖层位置影响。
 */
internal class ChapterProgressDrawable(
    @ColorInt color: Int,
    private val horizontalInset: Int,
    private val lineHeight: Int,
) : Drawable() {

    private val paint = Paint().apply {
        this.color = color
        alpha = (0.72f * 255).toInt()
        isAntiAlias = false
    }

    private var fraction = 0f

    fun setFraction(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (next == fraction) return
        fraction = next
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (fraction <= 0f) return
        val left = bounds.left + horizontalInset
        val right = bounds.right - horizontalInset
        if (right <= left) return
        val bottom = bounds.bottom.toFloat()
        canvas.drawRect(
            left.toFloat(),
            bottom - lineHeight,
            left + (right - left) * fraction,
            bottom,
            paint,
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
