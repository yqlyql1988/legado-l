package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.ui.widget.text.AccentTonalBgTextView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

enum class LabelStyle {
    FILLED,
    TONAL,
    OUTLINED,
}

data class StyledLabel(
    val text: String,
    val style: LabelStyle,
    val description: String? = null,
)

@Suppress("unused", "MemberVisibilityCanBePrivate")
class LabelsBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FlexboxLayout(context, attrs) {

    private val unUsedViews = arrayListOf<TextView>()
    private val usedViews = arrayListOf<TextView>()
    var textSize = 12f

    fun setLabels(
        labels: List<String>,
        onClick: ((String) -> Unit)? = null,
        onLongClick: ((String) -> Boolean)? = null,
        style: LabelStyle = LabelStyle.FILLED,
    ) {
        clear()
        labels.forEach {
            addLabel(it, onClick, onLongClick, style)
        }
    }

    fun setStyledLabels(
        labels: List<StyledLabel>,
        onClick: ((String) -> Unit)? = null,
        onLongClick: ((String) -> Boolean)? = null,
    ) {
        clear()
        labels.forEach {
            addLabel(it.text, onClick, onLongClick, it.style)
            usedViews.last().contentDescription = it.description
        }
    }

    fun clear() {
        unUsedViews.addAll(usedViews)
        usedViews.clear()
        removeAllViews()
    }

    fun addLabel(
        label: String,
        onClick: ((String) -> Unit)?,
        onLongClick: ((String) -> Boolean)?,
        style: LabelStyle = LabelStyle.FILLED,
    ) {
        val reusableIndex = unUsedViews.indexOfLast { view ->
            when (style) {
                LabelStyle.FILLED, LabelStyle.OUTLINED -> view is AccentBgTextView
                LabelStyle.TONAL -> view is AccentTonalBgTextView
            }
        }
        val tv = if (reusableIndex == -1) {
            when (style) {
                LabelStyle.FILLED, LabelStyle.OUTLINED -> AccentBgTextView(context, null)
                LabelStyle.TONAL -> AccentTonalBgTextView(context, null)
            }.apply {
                setPadding(3.dpToPx(), 0, 3.dpToPx(), 0)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                val lp = FlexboxLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 2.dpToPx()
                    if (this@LabelsBar.flexWrap == FlexWrap.NOWRAP) {
                        flexShrink = 0f
                    } else {
                        bottomMargin = 2.dpToPx()
                    }
                }
                layoutParams = lp
            }
        } else {
            unUsedViews.removeAt(reusableIndex)
        }
        when (tv) {
            is AccentBgTextView -> {
                tv.setRadius(2)
                tv.setOutlined(style == LabelStyle.OUTLINED)
            }
            is AccentTonalBgTextView -> tv.setRadius(2)
        }
        usedViews.add(tv)
        tv.textSize = textSize
        tv.text = label
        tv.contentDescription = null
        tv.setOnClickListener(null)
        tv.setOnLongClickListener(null)
        tv.isClickable = false
        tv.isLongClickable = false
        if (onClick != null) {
            tv.setOnClickListener { onClick.invoke(label) }
        }
        if (onLongClick != null) {
            tv.setOnLongClickListener { onLongClick.invoke(label) }
        }
        addView(tv)
    }

    fun addAction(
        icon: Int,
        description: String,
        onClick: (View) -> Unit,
    ) {
        val action = AppCompatImageButton(context).apply {
            val selectableBackground = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                selectableBackground,
                true,
            )
            setBackgroundResource(selectableBackground.resourceId)
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(context.getCompatColor(R.color.secondaryText))
            contentDescription = description
            tooltipText = description
            setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
            setOnClickListener { onClick(it) }
            layoutParams = FlexboxLayout.LayoutParams(
                28.dpToPx(),
                28.dpToPx(),
            ).apply {
                marginStart = 2.dpToPx()
                bottomMargin = 2.dpToPx()
                flexShrink = 0f
            }
        }
        addView(action)
    }
}
