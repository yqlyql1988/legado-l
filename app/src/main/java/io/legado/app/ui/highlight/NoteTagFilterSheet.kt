package io.legado.app.ui.highlight

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.legado.app.R
import io.legado.app.data.entities.HighlightTag
import io.legado.app.databinding.DialogNoteTagFilterBinding
import io.legado.app.utils.applyNavigationBarPadding

/**
 * 目录书签页、标注页和全局阅读笔记共用的标签筛选面板。
 * 三处只维护这一套筛选界面：点选立即回调，再点已选中的标签回到「全部」。
 */
class NoteTagFilterSheet(
    private val context: Context,
    private val tags: List<HighlightTag>,
    private val selectedId: String?,
    private val onSelect: (String?) -> Unit,
    private val onManage: () -> Unit,
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val binding = DialogNoteTagFilterBinding.inflate(LayoutInflater.from(context))
        binding.root.applyNavigationBarPadding()
        binding.tvManage.setOnClickListener {
            dialog.dismiss()
            onManage()
        }
        entries().forEach { (tag, selected) ->
            binding.flTags.addView(context.highlightTagChip(tag, selected, compact = true) {
                dialog.dismiss()
                onSelect(nextSelection(tag))
            })
        }
        dialog.setContentView(binding.root)
        dialog.show()
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
