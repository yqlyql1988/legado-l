package io.legado.app.ui.highlight

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import io.legado.app.R
import io.legado.app.data.entities.HighlightTag
import io.legado.app.databinding.PopupNoteTagFilterBinding
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.resolveDropDownYOffset

/**
 * 目录书签页、标注页和全局阅读笔记共用的标签筛选弹出面板。
 * 紧贴「标签：…」按钮下方展开，点选立即生效并关闭，视线不用在屏幕上下往返。
 */
class NoteTagFilterPopup(
    private val context: Context,
    private val anchor: TextView,
    private val onSelect: (String?) -> Unit,
) : PopupWindow(
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
) {

    private val binding = PopupNoteTagFilterBinding.inflate(LayoutInflater.from(context))
    private var tags: List<HighlightTag> = emptyList()
    private var selectedId: String? = null

    init {
        contentView = binding.root
        applyMd3PopupStyle()
        isTouchable = true
        isOutsideTouchable = true
        isFocusable = true
        anchor.setOnClickListener { showFilter() }
    }

    fun update(tags: List<HighlightTag>, selectedId: String?) {
        this.tags = tags
        this.selectedId = selectedId
        anchor.text = noteTagFilterLabel(context, selectedId, tags)
    }

    private fun showFilter() {
        render()
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.AT_MOST),
        )
        val margin = 8.dpToPx()
        val popupWidth = if (anchor.width >= frame.width() * 0.8f) {
            frame.width() - margin * 2
        } else {
            minOf(frame.width() - margin * 2, 320.dpToPx())
        }
        width = popupWidth
        height = minOf(contentView.measuredHeight, (frame.height() * 0.6f).toInt())
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val yOffset = resolveDropDownYOffset(
            anchorTop = location[1],
            anchorHeight = anchor.height,
            popupHeight = height,
            frameTop = frame.top,
            frameBottom = frame.bottom,
            gap = 0,
        )
        val xOffset = if (anchor.width >= popupWidth) (anchor.width - popupWidth) / 2 else 0
        showAsDropDown(anchor, xOffset, yOffset)
    }

    private fun render() {
        binding.flTags.removeAllViews()
        entries().forEach { (tag, selected) ->
            binding.flTags.addView(context.highlightTagChip(tag, selected, compact = true) {
                dismiss()
                onSelect(nextSelection(tag))
            })
        }
    }

    private fun entries(): List<Pair<HighlightTag, Boolean>> {
        val entries = arrayListOf<Pair<HighlightTag, Boolean>>()
        entries.add(
            HighlightTag(id = ALL_TAG_ID, name = context.getString(R.string.highlight_filter_all))
                to (selectedId == null)
        )
        entries.add(
            HighlightTag(id = "", name = context.getString(R.string.highlight_filter_untagged))
                to (selectedId == "")
        )
        tags.forEach { entries.add(it to (selectedId == it.id)) }
        return entries
    }

    private fun nextSelection(tag: HighlightTag): String? {
        val id = tag.id.takeIf { it != ALL_TAG_ID }
        return if (id == selectedId) null else id
    }

    private companion object {
        const val ALL_TAG_ID = "\u0000all"
    }
}

/** 三种入口共用的按钮文案，保证显示规则一致。 */
internal fun noteTagFilterLabel(
    context: Context,
    selectedId: String?,
    tags: List<HighlightTag>,
): String {
    val name = when {
        selectedId == null -> context.getString(R.string.highlight_filter_all)
        selectedId.isEmpty() -> context.getString(R.string.highlight_filter_untagged)
        else -> tags.firstOrNull { it.id == selectedId }?.name
            ?: context.getString(R.string.highlight_filter_all)
    }
    return context.getString(R.string.book_notes_tag_filter_value, name)
}
