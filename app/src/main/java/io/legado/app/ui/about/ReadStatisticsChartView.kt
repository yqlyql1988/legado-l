package io.legado.app.ui.about

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getCompatColor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

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
    var selectedIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }
    var onBarSelected: ((Int) -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null
            isFocusable = value != null
        }
    private var downX = 0f
    private var downY = 0f
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
        val maximum = bars.maxOfOrNull { it.duration } ?: 0L
        val minutes = onBarSelected != null && maximum < 3_600_000
        val divisor = if (minutes) 60_000.0 else 3_600_000.0
        val amount = maximum / divisor
        val step = if (amount > 100) 100.0 else if (amount > 10) 10.0 else 1.0
        val ceiling = max(step, ceil(amount / step) * step)
        paint.textSize = textSize
        paint.color = context.getCompatColor(R.color.secondaryText)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(if (minutes) R.string.read_statistics_minutes else R.string.read_statistics_hours), 0f, textSize, paint)
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
            val barHeight = ((bar.duration / divisor / ceiling) * (bottom - top)).toFloat()
            paint.color = if (if (onBarSelected != null) index == selectedIndex else bar.duration == peak && peak > 0) context.accentColor
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onBarSelected == null || bars.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    val left = textSize * 3.3f
                    val right = width - 4f * density
                    if (event.x in left..right && right > left) {
                        selectedIndex = ((event.x - left) / (right - left) * bars.size).toInt().coerceIn(bars.indices)
                        performClick()
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (selectedIndex in bars.indices) onBarSelected?.invoke(selectedIndex)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onBarSelected != null && bars.isNotEmpty()) {
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return super.onKeyDown(keyCode, event)
            }
            selectedIndex = (selectedIndex.coerceAtLeast(0) + delta).coerceIn(bars.indices)
            performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
