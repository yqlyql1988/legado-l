package io.legado.app.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import kotlin.math.max

class CenterCropDrawable(private val bitmap: Bitmap) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val scale = max(
            bounds.width().toFloat() / bitmap.width,
            bounds.height().toFloat() / bitmap.height,
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        destination.set(
            bounds.left + (bounds.width() - width) / 2f,
            bounds.top + (bounds.height() - height) / 2f,
            bounds.left + (bounds.width() + width) / 2f,
            bounds.top + (bounds.height() + height) / 2f,
        )
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Drawable opacity is only used by legacy rendering")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
