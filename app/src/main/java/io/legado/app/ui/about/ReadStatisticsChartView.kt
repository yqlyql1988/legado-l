package io.legado.app.ui.about

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getCompatColor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class ReadStatisticsBar(val label: String, val duration: Long)

/** Small native chart: no chart dependency or raster resources in the slim APK. */
class ReadStatisticsChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    var bars: List<ReadStatisticsBar> = emptyList()
        set(value) {
            field = value
            contentDescription = value.joinToString("，") { "${it.label}：${formatDuring(it.duration, showSeconds = false)}" }
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density get() = resources.displayMetrics.density
    private val textSize get() = 12f * resources.displayMetrics.scaledDensity

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return
        val inset = textSize * 3.3f
        val top = textSize * 2f
        val bottom = height - textSize * 2.2f
        val right = width - 4f * density
        if (right <= inset || bottom <= top) return
        val hours = (bars.maxOfOrNull { it.duration } ?: 0) / 3_600_000.0
        val step = if (hours > 100) 100.0 else if (hours > 10) 10.0 else 1.0
        val ceiling = max(step, ceil(hours / step) * step)
        paint.textSize = textSize
        paint.color = context.getCompatColor(R.color.secondaryText)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.read_statistics_hours), 0f, textSize, paint)
        for (index in 0..2) {
            val y = top + (bottom - top) * index / 2f
            paint.color = ColorUtils.blendColors(context.backgroundColor, context.getCompatColor(R.color.primaryText), .10f)
            paint.strokeWidth = density
            canvas.drawLine(inset, y, right, y, paint)
            paint.color = context.getCompatColor(R.color.secondaryText)
            paint.textAlign = Paint.Align.RIGHT
            val amount = ceiling * (2 - index) / 2
            val label = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()
            canvas.drawText(label, inset - 7f * density, y + textSize / 3, paint)
        }
        val slot = (right - inset) / bars.size
        val barWidth = min(32f * density, slot * .56f)
        val peak = bars.maxOf { it.duration }
        val stride = max(1, ceil(textSize * 2.5f / slot).toInt())
        bars.forEachIndexed { index, bar ->
            val center = inset + slot * (index + .5f)
            val barHeight = ((bar.duration / 3_600_000.0 / ceiling) * (bottom - top)).toFloat()
            paint.color = if (bar.duration == peak && peak > 0) context.accentColor
                else ColorUtils.blendColors(context.backgroundColor, context.accentColor, .38f)
            if (bar.duration > 0) {
                rect.set(center - barWidth / 2, bottom - barHeight, center + barWidth / 2, bottom)
                canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
            }
            if (index % stride == 0) {
                paint.color = context.getCompatColor(R.color.secondaryText)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(bar.label, center, bottom + textSize * 1.7f, paint)
            }
        }
    }
}
