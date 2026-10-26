package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.addBookTag
import io.legado.app.data.entities.aggregateBookTagStats
import io.legado.app.data.entities.bookTags
import io.legado.app.data.entities.normalizeBookTags
import io.legado.app.data.entities.removeBookTag
import io.legado.app.data.entities.sourceTags
import io.legado.app.databinding.ViewBookTagEditorBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class BookTagEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val binding = ViewBookTagEditorBinding.inflate(LayoutInflater.from(context), this, true)
    private var sourceTags = emptyList<String>()
    private var commonTags = emptyList<String>()
    private var expanded = false
    private var renderVersion = 0

    val tags: String?
        get() = normalizeBookTags(binding.tieCustomTag.text?.toString())

    init {
        binding.tieCustomTag.doAfterTextChanged { render() }
        binding.tieCommonTagSearch.doAfterTextChanged {
            expanded = false
            render()
        }
        binding.tvCommonTagAdd.setOnClickListener {
            binding.tieCommonTagSearch.text?.toString().bookTags().singleOrNull()?.let { tag ->
                setTags(addBookTag(tags, tag))
                binding.tieCommonTagSearch.text?.clear()
            }
        }
        binding.tvCommonTagExpand.setOnClickListener {
            expanded = !expanded
            render()
        }
    }

    fun setBook(book: Book, customTags: String? = book.customTag) {
        sourceTags = book.sourceTags()
        setTags(customTags)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && oldw > 0) post { render() }
    }

    fun loadCommonTags(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            commonTags = withContext(IO) {
                aggregateBookTagStats(
                    appDb.bookDao.flowShelfTagBooks().first(),
                    sourceTags = false,
                ).map { it.name }
            }
            render()
        }
    }

    private fun setTags(value: String?) {
        binding.tieCustomTag.setText(value)
        binding.tieCustomTag.setSelection(binding.tieCustomTag.text?.length ?: 0)
    }

    private fun render() {
        val selected = tags.bookTags()
        val selectedKeys = selected.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        val sourceKeys = sourceTags.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        val query = binding.tieCommonTagSearch.text?.toString().orEmpty().trim()
        val labels = (sourceTags + selected + commonTags)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            .map { name ->
                val key = name.lowercase(Locale.ROOT)
                val isSelected = key in selectedKeys
                val isSource = key in sourceKeys
                StyledLabel(
                    name,
                    when {
                        isSelected && isSource -> LabelStyle.OUTLINED
                        isSelected -> LabelStyle.FILLED
                        else -> LabelStyle.TONAL
                    },
                    context.getString(
                        when {
                            isSelected && isSource -> R.string.book_tag_origin_both
                            isSelected -> R.string.book_tag_origin_user
                            isSource -> R.string.book_tag_origin_source
                            else -> R.string.book_tag_origin_common
                        },
                        name,
                    ),
                )
            }
        val newTag = query.bookTags().singleOrNull()
        binding.tvCommonTagAdd.apply {
            isVisible = newTag != null && labels.none { it.text.equals(newTag, ignoreCase = true) }
            text = newTag?.let { context.getString(R.string.book_tag_existing_add, it) }
        }

        val version = ++renderVersion
        binding.lbCommonTag.setStyledLabels(labels, onClick = { name ->
            val updated = if (name.lowercase(Locale.ROOT) in selectedKeys) {
                removeBookTag(tags, name)
            } else {
                addBookTag(tags, name)
            }
            setTags(updated)
            binding.tieCommonTagSearch.text?.clear()
        })
        binding.lbCommonTag.children.forEach { it.isVisible = true }
        binding.lbCommonTag.doOnNextLayout {
            if (version != renderVersion) return@doOnNextLayout
            val visibleCount = binding.lbCommonTag.flexLines.take(3).sumOf { it.itemCount }
            binding.lbCommonTag.post {
                if (version != renderVersion) return@post
                val hiddenCount = (labels.size - visibleCount).coerceAtLeast(0)
                if (hiddenCount == 0) expanded = false
                binding.tvCommonTagExpand.apply {
                    isVisible = hiddenCount > 0
                    text = if (expanded) {
                        context.getString(R.string.book_tag_existing_collapse)
                    } else {
                        context.getString(R.string.book_tag_existing_expand, hiddenCount)
                    }
                }
                binding.lbCommonTag.children.forEachIndexed { index, child ->
                    child.isVisible = expanded || index < visibleCount
                }
            }
        }
    }
}
