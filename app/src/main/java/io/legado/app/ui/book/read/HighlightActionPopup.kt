package io.legado.app.ui.book.read

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.data.entities.HighlightTag
import io.legado.app.ui.highlight.highlightTagChip
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

/** An annotation's two rows: existing actions, then independently scrollable labels. */
class HighlightActionPopup(
    private val context: Context,
    private val onAction: (Action) -> Unit,
    private val onTag: (String) -> Unit
) : PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    enum class Action { STYLE, NOTE, CREATE_RULE, COPY, DELETE, MANAGE_TAGS }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
    }
    private val tagRail = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tagScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(tagRail)
    }

    init {
        contentView = root
        applyMd3PopupStyle()
        isFocusable = true
        isTouchable = true
        isOutsideTouchable = true
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val actionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }
        listOf(
            Action.STYLE to R.string.highlight_style, Action.NOTE to R.string.highlight_note,
            Action.CREATE_RULE to R.string.highlight_create_rule,
            Action.COPY to android.R.string.copy, Action.DELETE to R.string.delete
        ).forEach { (action, title) ->
            actions.addView(TextView(context).apply {
                setText(title)
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = 44.dpToPx()
                isSingleLine = true
                setTextColor(context.getCompatColor(R.color.primaryText))
                setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
                val background = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
                setBackgroundResource(background.resourceId)
                setOnClickListener { dismiss(); onAction(action) }
            })
        }
        root.addView(actionsScroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(context).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(context.getCompatColor(R.color.primaryText), 28))
        }, LinearLayout.LayoutParams(-1, 1.dpToPx()))
        val secondRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        secondRow.addView(tagScroll, LinearLayout.LayoutParams(0, -2, 1f))
        secondRow.addView(AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(context.getCompatColor(R.color.primaryText))
            contentDescription = context.getString(R.string.highlight_tag_manage)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(11.dpToPx(), 11.dpToPx(), 11.dpToPx(), 11.dpToPx())
            setOnClickListener { dismiss(); onAction(Action.MANAGE_TAGS) }
        }, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        root.addView(secondRow, LinearLayout.LayoutParams(-1, -2))
    }

    fun refreshTags(tags: List<HighlightTag>, selectedIds: Collection<String>) {
        val scroll = tagScroll.scrollX
        tagRail.removeAllViews()
        tags.forEach { tag ->
            tagRail.addView(context.highlightTagChip(tag, tag.id in selectedIds) { onTag(tag.id) })
        }
        tagScroll.post { tagScroll.scrollTo(scroll, 0) }
    }

    fun show(anchor: View) {
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        if (frame.isEmpty) return
        width = minOf(380.dpToPx(), frame.width() - 16.dpToPx()).coerceAtLeast(1)
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.AT_MOST)
        )
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val x = location[0].coerceIn(frame.left, (frame.right - width).coerceAtLeast(frame.left))
        val popupHeight = contentView.measuredHeight
        val above = location[1] - popupHeight - 8.dpToPx()
        val y = (if (above >= frame.top) above else location[1] + anchor.height + 8.dpToPx())
            .coerceIn(frame.top, (frame.bottom - popupHeight).coerceAtLeast(frame.top))
        showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
    }
}
