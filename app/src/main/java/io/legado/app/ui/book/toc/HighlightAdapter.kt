package io.legado.app.ui.book.toc

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.databinding.ItemHighlightBinding
import io.legado.app.help.HighlightTags
import io.legado.app.utils.gone
import splitties.views.onLongClick
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HighlightAdapter(context: Context, private val callback: Callback) :
    RecyclerAdapter<BookHighlight, ItemHighlightBinding>(context) {

    var tags: List<HighlightTag> = emptyList()
    var tagLinks: Map<Long, List<HighlightTagLink>> = emptyMap()

    /** 组首条目的 time -> 本章条数，由列表按章节顺序生成。 */
    var sections: Map<Long, Int> = emptyMap()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun getViewBinding(parent: ViewGroup): ItemHighlightBinding {
        return ItemHighlightBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemHighlightBinding,
        item: BookHighlight,
        payloads: MutableList<Any>
    ) {
        val sectionCount = sections[item.time]
        binding.chapterHeader.root.isVisible = sectionCount != null
        if (sectionCount != null) {
            binding.chapterHeader.tvChapterName.text = item.chapterName
            binding.chapterHeader.tvChapterCount.text =
                context.getString(R.string.book_note_chapter_count, sectionCount)
        }
        binding.tvBookText.gone(item.bookText.isEmpty())
        binding.tvBookText.text = item.bookText
        binding.tvNote.gone(item.note.isEmpty())
        binding.tvNote.text = item.note
        binding.tvDate.text = dateFormat.format(Date(item.time))
        val ids = tagLinks[item.time].orEmpty().map { it.tagId }
        val names = tags.filter { it.id in ids }.joinToString("、") { it.name }
        binding.tvTags.gone(names.isEmpty())
        binding.tvTags.text = context.getString(R.string.highlight_tag_details, names)
        val style = HighlightTags.effectiveStyle(item, tags, ids)
        val color = style.fill.takeIf { it != 0 }
            ?: style.textColor.takeIf { it != 0 }
            ?: style.underline?.color?.takeIf { it != 0 }
            ?: style.strike?.color?.takeIf { it != 0 }
            ?: style.box?.color?.takeIf { it != 0 }
            ?: style.emphasis?.color?.takeIf { it != 0 }
            ?: 0
        binding.viewColor.setBackgroundColor(color)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemHighlightBinding) {
        binding.card.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::onClick)
        }
        binding.card.onLongClick {
            getItem(holder.layoutPosition)?.let(callback::onLongClick)
        }
    }

    interface Callback {
        fun onClick(highlight: BookHighlight)
        fun onLongClick(highlight: BookHighlight)
    }
}
