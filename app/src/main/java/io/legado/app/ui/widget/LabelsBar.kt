package io.legado.app.ui.widget

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.TextView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.ui.widget.text.AccentTonalBgTextView
import io.legado.app.utils.dpToPx

enum class LabelStyle {
    FILLED,
    TONAL,
}

data class StyledLabel(
    val text: String,
    val style: LabelStyle,
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
                LabelStyle.FILLED -> view is AccentBgTextView
                LabelStyle.TONAL -> view is AccentTonalBgTextView
            }
        }
        val tv = if (reusableIndex == -1) {
            when (style) {
                LabelStyle.FILLED -> AccentBgTextView(context, null)
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
            is AccentBgTextView -> tv.setRadius(2)
            is AccentTonalBgTextView -> tv.setRadius(2)
        }
        usedViews.add(tv)
        tv.textSize = textSize
        tv.text = label
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
}
