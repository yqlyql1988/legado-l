package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.lib.theme.secondaryTextColor
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
    val actionIcon: Int? = null,
    val actionDescription: String? = null,
    val actionTint: Int? = null,
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
        onActionClick: ((String) -> Unit)? = null,
    ) {
        clear()
        labels.forEach {
            if (it.actionIcon != null && onActionClick != null) {
                addActionLabel(it, onClick, onLongClick, onActionClick)
            } else {
                addLabel(it.text, onClick, onLongClick, it.style)
            }
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

    private fun addActionLabel(
        label: StyledLabel,
        onClick: ((String) -> Unit)?,
        onLongClick: ((String) -> Boolean)?,
        onActionClick: (String) -> Unit,
    ) {
        val chip = createLabelView(label.text, label.style).apply {
            setOnClickListener(onClick?.let { callback -> View.OnClickListener { callback(label.text) } })
            setOnLongClickListener(
                onLongClick?.let { callback ->
                    View.OnLongClickListener { callback(label.text) }
                }
            )
            isClickable = onClick != null
            isLongClickable = onLongClick != null
        }
        val action = AppCompatImageButton(context).apply {
            val selectableBackground = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                selectableBackground,
                true,
            )
            setBackgroundResource(selectableBackground.resourceId)
            setImageResource(label.actionIcon!!)
            imageTintList = ColorStateList.valueOf(label.actionTint ?: context.secondaryTextColor)
            contentDescription = label.actionDescription
            tooltipText = label.actionDescription
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            setOnClickListener { onActionClick(label.text) }
            layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx())
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                chip,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(action)
            layoutParams = FlexboxLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 2.dpToPx()
            }
        }
        addView(container)
    }

    private fun createLabelView(label: String, style: LabelStyle): TextView {
        return when (style) {
            LabelStyle.FILLED -> AccentBgTextView(context, null)
            LabelStyle.TONAL -> AccentTonalBgTextView(context, null)
        }.apply {
            setPadding(3.dpToPx(), 0, 3.dpToPx(), 0)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            when (this) {
                is AccentBgTextView -> setRadius(2)
                is AccentTonalBgTextView -> setRadius(2)
            }
            textSize = this@LabelsBar.textSize
            text = label
        }
    }
}
