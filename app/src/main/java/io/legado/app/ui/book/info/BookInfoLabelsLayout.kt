package io.legado.app.ui.book.info

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.children
import io.legado.app.R
import io.legado.app.ui.widget.LabelsBar
import io.legado.app.utils.dpToPx

/** The edit action floats in the header margin without affecting label wrapping or centering. */
class BookInfoLabelsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private lateinit var labels: LabelsBar
    private lateinit var edit: AppCompatImageButton
    private val anchor = Rect()

    override fun onFinishInflate() {
        super.onFinishInflate()
        labels = findViewById(R.id.lb_kind)
        edit = findViewById(R.id.ib_edit_tags)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Flexbox does not retain its minimum height when there are no label rows.
        labels.layoutParams.height = if (labels.childCount == 0) {
            labels.minimumHeight
        } else {
            LayoutParams.WRAP_CONTENT
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val lastLabel = labels.children.lastOrNull { it.visibility != View.GONE }
        val target = lastLabel ?: labels
        target.getDrawingRect(anchor)
        offsetDescendantRectToMyCoords(target, anchor)

        val iconSize = 14.dpToPx()
        val gap = 2.dpToPx()
        val rtl = labels.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val iconLeft = when {
            lastLabel == null -> anchor.centerX() - iconSize / 2
            rtl -> anchor.left - gap - iconSize
            else -> anchor.right + gap
        }.coerceIn(0, (width - iconSize).coerceAtLeast(0))
        val availableWidth = when {
            lastLabel == null -> width
            rtl -> anchor.left
            else -> width - anchor.right
        }
        val actionWidth = edit.measuredWidth.coerceAtMost(availableWidth)
        val actionLeft = when {
            lastLabel == null -> iconLeft - (actionWidth - iconSize) / 2
            rtl -> anchor.left - actionWidth
            else -> anchor.right
        }
        val actionTop = (anchor.centerY() - edit.measuredHeight / 2)
            .coerceIn(0, (height - edit.measuredHeight).coerceAtLeast(0))
        // Use the existing margin without letting the action intercept taps on the last tag.
        val iconPaddingLeft = iconLeft - actionLeft
        edit.setPadding(
            iconPaddingLeft,
            (edit.measuredHeight - iconSize) / 2,
            actionWidth - iconSize - iconPaddingLeft,
            (edit.measuredHeight - iconSize) / 2,
        )
        edit.layout(
            actionLeft, actionTop,
            actionLeft + actionWidth, actionTop + edit.measuredHeight,
        )
    }
}
