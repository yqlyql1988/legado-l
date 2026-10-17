package io.legado.app.ui.book.read

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.legado.app.R
import io.legado.app.data.entities.HighlightTag
import io.legado.app.help.HighlightMenuConfig
import io.legado.app.help.HighlightPopupPosition
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.highlight.highlightTagChip
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.toastOnUi

/** Comfortable actions and an independently scrolling label row, anchored to the visible annotation. */
class HighlightActionPopup(
    private val context: Context,
    private val onAction: (Action) -> Unit,
    private val onTag: (String) -> Unit
) : PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    enum class Action(val titleRes: Int) {
        NOTE(R.string.highlight_note), STYLE(R.string.highlight_style), COPY(android.R.string.copy),
        CREATE_RULE(R.string.highlight_create_rule), DELETE(R.string.delete),
        MANAGE_TAGS(R.string.highlight_tag_manage), CONFIGURE(R.string.highlight_menu_config)
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val tagRail = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tagScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(tagRail)
    }
    private var morePopup: PopupAction? = null
    private var noteDialog: AlertDialog? = null
    private var hasTags = false

    init {
        contentView = root
        applyMd3PopupStyle()
        root.setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
        isFocusable = true
        isTouchable = true
        isOutsideTouchable = true
        setOnDismissListener {
            morePopup?.dismiss()
            morePopup = null
            noteDialog?.dismiss()
            noteDialog = null
        }
        val config = HighlightMenuConfig.load(context)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val firstRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        firstRow.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        config.bar.forEach { key ->
            val action = Action.valueOf(key)
            actions.addView(actionView(context.getString(action.titleRes)) {
                dismiss()
                onAction(action)
            })
        }
        firstRow.addView(actionView("⋮") { anchor ->
            morePopup?.dismiss()
            morePopup = popupActionMenu(context) {
                config.more.forEach { key -> item(context.getString(Action.valueOf(key).titleRes), key) }
                item(context.getString(R.string.highlight_menu_config), Action.CONFIGURE.name)
                danger(Action.DELETE.name)
            }.show(anchor) { key -> dismiss(); onAction(Action.valueOf(key)) }
        }.apply {
            contentDescription = context.getString(R.string.text_menu_zone_more)
            textSize = 20f
            setPadding(0, 0, 0, 0)
        },
            LinearLayout.LayoutParams(44.dpToPx(), -2))
        root.addView(firstRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(context).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(context.getCompatColor(R.color.primaryText), 24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, 1.dpToPx()).apply {
            marginStart = 12.dpToPx()
            marginEnd = 12.dpToPx()
        })
        val secondRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        secondRow.addView(tagScroll, LinearLayout.LayoutParams(0, -2, 1f))
        secondRow.addView(AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(context.getCompatColor(R.color.secondaryText))
            contentDescription = context.getString(R.string.highlight_tag_manage)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            setOnClickListener { dismiss(); onAction(Action.MANAGE_TAGS) }
        }, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        root.addView(secondRow, LinearLayout.LayoutParams(-1, -2))
    }

    private fun actionView(title: String, click: (View) -> Unit) = TextView(context).apply {
        text = title
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = false
        isSingleLine = true
        minHeight = 44.dpToPx()
        setTextColor(context.getCompatColor(R.color.primaryText))
        setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
        setBackgroundResource(background.resourceId)
        setOnClickListener(click)
    }

    fun refreshTags(tags: List<HighlightTag>, selectedIds: Collection<String>) {
        hasTags = tags.isNotEmpty()
        val scroll = tagScroll.scrollX
        tagRail.removeAllViews()
        if (tags.isEmpty()) tagRail.addView(actionView(context.getString(R.string.highlight_tag_add)) {
            dismiss(); onAction(Action.MANAGE_TAGS)
        })
        tags.forEach { tag ->
            val selected = tag.id in selectedIds
            tagRail.addView(
                context.highlightTagChip(tag, selected, compact = true) { onTag(tag.id) }.apply {
                    setOnLongClickListener {
                        showTagNote(tag)
                        true
                    }
                    ViewCompat.replaceAccessibilityAction(this,
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                        context.getString(R.string.highlight_tag_view_note)) { view, _ ->
                        view.performLongClick()
                    }
                    minHeight = 44.dpToPx()
                    val chipBackground = (background as GradientDrawable).apply {
                        cornerRadius = 6f.dpToPx()
                        setStroke(if (selected) 0 else 1.dpToPx(),
                            ColorUtils.setAlphaComponent(context.getCompatColor(R.color.primaryText), 36))
                    }
                    background = InsetDrawable(chipBackground, 0, 8.dpToPx(), 0, 8.dpToPx())
                    setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dpToPx() }
            )
        }
        tagScroll.post { tagScroll.scrollTo(scroll, 0) }
    }

    private fun showTagNote(tag: HighlightTag) {
        noteDialog?.dismiss()
        val scope = context.getString(if (tag.bookUrl.isEmpty())
            R.string.highlight_tag_global else R.string.highlight_tag_book)
        noteDialog = AlertDialog.Builder(context)
            .setTitle("${tag.name} · $scope")
            .setMessage(tag.note.ifBlank { context.getString(R.string.highlight_tag_note_empty) })
            .setPositiveButton(R.string.close, null)
            .create().apply {
                setOnDismissListener { noteDialog = null }
                show()
            }
    }

    fun show(anchor: View, selection: Rect? = null) {
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        if (frame.isEmpty) return
        val margin = 8.dpToPx()
        frame.inset(margin, margin)
        width = minOf(320.dpToPx(), frame.width()).coerceAtLeast(1)
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.AT_MOST)
        )
        height = contentView.measuredHeight
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val selected = selection ?: Rect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
        val position = HighlightPopupPosition.resolve(
            HighlightPopupPosition.Bounds(frame.left, frame.top, frame.right, frame.bottom),
            HighlightPopupPosition.Bounds(selected.left, selected.top, selected.right, selected.bottom),
            width, height, 8.dpToPx()
        )
        showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, position.x, position.y)
        if (hasTags && !LocalConfig.getBoolean("highlightTagNoteHelpShown", false)) {
            context.toastOnUi(R.string.highlight_tag_note_help)
            LocalConfig.edit().putBoolean("highlightTagNoteHelpShown", true).apply()
        }
    }
}
