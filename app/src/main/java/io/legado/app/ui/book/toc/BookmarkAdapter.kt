package io.legado.app.ui.book.toc

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightTag
import io.legado.app.databinding.ItemBookmarkBinding
import io.legado.app.ui.highlight.highlightTagChip
import io.legado.app.utils.gone
import splitties.views.onLongClick
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<Bookmark, ItemBookmarkBinding>(context) {
    var tags: Map<Long, List<HighlightTag>> = emptyMap()

    /** 组首条目的 time -> 本章条数，由列表按章节顺序生成。 */
    var sections: Map<Long, Int> = emptyMap()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun getViewBinding(parent: ViewGroup): ItemBookmarkBinding {
        return ItemBookmarkBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookmarkBinding,
        item: Bookmark,
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
        binding.tvContent.gone(item.content.isEmpty())
        binding.tvContent.text = item.content
        binding.tvDate.text = dateFormat.format(Date(item.time))
        binding.tagLabels.removeAllViews()
        val attached = tags[item.time].orEmpty()
        binding.tagLabels.gone(attached.isEmpty())
        attached.forEach { tag ->
            binding.tagLabels.addView(binding.root.context.highlightTagChip(tag, true, compact = true) {
                callback.onTagClick(tag.id)
            })
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookmarkBinding) {
        binding.card.setOnClickListener {
            getItem(holder.layoutPosition)?.let { bookmark ->
                callback.onClick(bookmark)
            }
        }
        binding.card.onLongClick {
            getItem(holder.layoutPosition)?.let { bookmark ->
                callback.onLongClick(bookmark, holder.layoutPosition)
            }
        }

    }

    interface Callback {
        fun onClick(bookmark: Bookmark)
        fun onLongClick(bookmark: Bookmark, pos: Int)
        fun onTagClick(id: String)
    }

}
