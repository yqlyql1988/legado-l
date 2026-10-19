package io.legado.app.ui.highlight

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.legado.app.R
import io.legado.app.data.entities.HighlightTag
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

internal fun Context.highlightTagChip(tag: HighlightTag, selected: Boolean, compact: Boolean = false, click: () -> Unit) =
    TextView(this).apply {
        text = tag.name
        textSize = if (compact) 12f else 14f
        isSingleLine = true
        gravity = Gravity.CENTER
        minHeight = (if (compact) 26 else 44).dpToPx()
        includeFontPadding = !compact
        val horizontal = (if (compact) 8 else 12).dpToPx()
        setPadding(horizontal, if (compact) 0 else 6.dpToPx(), horizontal, if (compact) 0 else 6.dpToPx())
        if (compact) layoutParams = com.google.android.flexbox.FlexboxLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 6.dpToPx()
            bottomMargin = 6.dpToPx()
        }
        setTextColor(context.getCompatColor(R.color.primaryText))
        background = GradientDrawable().apply {
            cornerRadius = 4f.dpToPx()
            setColor(if (selected) ColorUtils.setAlphaComponent(context.accentColor, 55) else Color.TRANSPARENT)
        }
        isSelected = selected
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isSelected = selected
                info.className = "android.widget.Button"
            }
        })
        setOnClickListener { click() }
    }
