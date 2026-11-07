package io.legado.app.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * 给图标 Drawable 叠一层阴影，用于顶部图片背景上的返回箭头、菜单图标等。
 * 图标本身和图片背景都不做修改，只在绘制时先画一层偏移的阴影副本再画原图标。
 */
class ShadowDrawable(
    val source: Drawable,
    private val shadowColor: Int,
    private val shadowRadius: Float,
    private val shadowDy: Float,
) : Drawable() {

    private var shadowDrawable: Drawable? = null

    private fun shadow(): Drawable? {
        if (shadowColor == 0) return null
        shadowDrawable?.let { return it }
        val drawable = source.constantState?.newDrawable()?.mutate() ?: return null
        drawable.setColorFilter(PorterDuffColorFilter(shadowColor, PorterDuff.Mode.SRC_IN))
        shadowDrawable = drawable
        return drawable
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        source.setBounds(bounds)
        shadow()?.let { shadow ->
            shadow.setBounds(bounds)
            val save = canvas.save()
            canvas.translate(0f, shadowDy)
            shadow.draw(canvas)
            canvas.restoreToCount(save)
        }
        source.draw(canvas)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        source.setBounds(bounds)
    }

    override fun setAlpha(alpha: Int) {
        source.alpha = alpha
        shadowDrawable?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        source.colorFilter = colorFilter
    }

    @Deprecated("Drawable opacity is only used by legacy rendering")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = source.intrinsicWidth

    override fun getIntrinsicHeight(): Int = source.intrinsicHeight
}
