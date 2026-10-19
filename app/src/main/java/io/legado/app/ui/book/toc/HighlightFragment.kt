package io.legado.app.ui.book.toc

import io.legado.app.help.ReadingNoteChapter
import io.legado.app.help.ReadingNotes
import io.legado.app.ui.book.bookmark.ReadingNoteNavigation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.databinding.FragmentHighlightBinding
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.help.HighlightTags
import io.legado.app.ui.highlight.highlightTagChip
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.HighlightNoteDialog
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun resolveHighlightChapterIndex(
    highlight: BookHighlight,
    chapterIndexes: Map<String, Int>
): Int? {
    return if (highlight.chapterUrl.isBlank()) {
        highlight.chapterIndex
    } else {
        ReadingNoteChapter.exactIndex(highlight.chapterUrl, chapterIndexes)
    }
}

internal fun highlightBodyPosition(highlight: BookHighlight): Int {
    return (highlight.chapterPos - highlight.layoutTitleLength.coerceAtLeast(0))
        .coerceAtLeast(0)
}

class HighlightFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_highlight),
    HighlightAdapter.Callback,
    TocViewModel.HighlightCallBack {

    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentHighlightBinding::bind)
    private var layoutManager: UpLinearLayoutManager? = null
    private var adapter: HighlightAdapter? = null
    private var highlightJob: Job? = null
    private var chapterIndexes = emptyMap<String, Int>()
    private var durChapterIndex = 0
    private var selectedTag: String? = null
    private var currentHighlights = emptyList<BookHighlight>()
    private var currentTags = emptyList<HighlightTag>()
    private var currentLinks = emptyMap<Long, List<HighlightTagLink>>()
    private var usedTags = emptySet<String>()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        selectedTag = savedInstanceState?.getString("tagFilter")
        binding.filterAll.setOnClickListener { selectFilter(null) }
        viewModel.highlightCallBack = this
        initRecyclerView()
        viewModel.bookData.observe(viewLifecycleOwner) {
            durChapterIndex = it.durChapterIndex
            upHighlight(viewModel.searchKey)
        }
    }

    override fun onDestroyView() {
        highlightJob?.cancel()
        highlightJob = null
        binding.recyclerView.adapter = null
        adapter = null
        layoutManager = null
        chapterIndexes = emptyMap()
        currentHighlights = emptyList()
        currentTags = emptyList()
        currentLinks = emptyMap()
        viewModel.highlightCallBack = clearCallbackIfOwned(
            viewModel.highlightCallBack,
            this
        )
        super.onDestroyView()
    }

    private fun initRecyclerView() {
        val layoutManager = UpLinearLayoutManager(requireContext())
        val adapter = HighlightAdapter(requireContext(), this)
        this.layoutManager = layoutManager
        this.adapter = adapter
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    override fun upHighlight(searchKey: String?) {
        highlightJob?.cancel()
        val book = viewModel.bookData.value ?: return
        if (!supportsHighlightPosition(book)) {
            chapterIndexes = emptyMap()
            adapter?.setItems(emptyList())
            return
        }
        highlightJob = viewLifecycleOwner.lifecycleScope.launch {
            val searched = when {
                searchKey.isNullOrBlank() ->
                    appDb.bookHighlightDao.flowByBook(book.bookUrl)

                else -> appDb.bookHighlightDao.flowSearch(book.bookUrl, searchKey)
            }
            var initial = true
            combine(appDb.bookHighlightDao.flowByBook(book.bookUrl), searched,
                appDb.highlightTagDao.flowAll(), appDb.highlightTagDao.flowLinksForBook(book.bookUrl),
                appDb.highlightTagDao.flowOrdersForBook(book.bookUrl)) { all, highlights, tags, links, orders ->
                FilterData(all, highlights, HighlightTags.forBook(tags, book.bookUrl, orders), links)
            }.flowOn(IO).catch {
                AppLog.put("目录界面获取标注数据失败\n${it.localizedMessage}", it)
            }.collect { (all, highlights, tags, links) ->
                adapter?.tags = tags
                val tagIds = tags.mapTo(hashSetOf()) { it.id }
                currentLinks = links.filter { it.tagId in tagIds }.groupBy { it.highlightTime }
                adapter?.tagLinks = currentLinks
                currentTags = tags
                val allTimes = all.mapTo(hashSetOf()) { it.time }
                usedTags = currentLinks.filterKeys { it in allTimes }.values.flatten().mapTo(hashSetOf()) { it.tagId }
                if (!selectedTag.isNullOrEmpty() && selectedTag !in usedTags) selectedTag = null
                chapterIndexes = withContext(IO) {
                    appDb.bookChapterDao.getChapterList(book.bookUrl)
                        .associate { it.url to it.index }
                }
                val orderedHighlights = highlights.sortedWith(
                    compareBy(
                        { resolveHighlightChapterIndex(it, chapterIndexes) ?: Int.MAX_VALUE },
                        ::highlightBodyPosition,
                        BookHighlight::time
                    )
                )
                currentHighlights = orderedHighlights
                renderFilters()
                val displayed = renderHighlights()
                val scrollPosition = displayed
                    .indexOfLast {
                        resolveHighlightChapterIndex(it, chapterIndexes)
                            ?.let { chapterIndex -> chapterIndex < durChapterIndex } == true
                    }
                    .coerceAtLeast(0)
                if (initial) layoutManager?.scrollToPositionWithOffset(scrollPosition, 0)
                initial = false
            }
        }
    }

    private data class FilterData(
        val all: List<BookHighlight>, val highlights: List<BookHighlight>,
        val tags: List<HighlightTag>, val links: List<HighlightTagLink>
    )

    private fun selectFilter(id: String?) {
        selectedTag = id
        renderFilters()
        renderHighlights()
        layoutManager?.scrollToPositionWithOffset(0, 0)
    }

    private fun renderHighlights(): List<BookHighlight> {
        val result = currentHighlights.filter { highlight ->
            HighlightTags.matchesFilter(selectedTag, currentLinks[highlight.time].orEmpty().map { it.tagId })
        }
        adapter?.setItems(result)
        binding.tvEmpty.isVisible = result.isEmpty()
        return result
    }

    private fun renderFilters() {
        val oldScroll = binding.filterScroll.scrollX
        binding.filterTags.removeAllViews()
        binding.filterAll.isSelected = selectedTag == null
        binding.filterAll.background = requireContext().highlightTagChip(
            HighlightTag(name = getString(R.string.highlight_filter_all)), selectedTag == null, compact = true
        ) {}.background
        currentTags.filter { it.id in usedTags }.forEach { tag ->
            binding.filterTags.addView(requireContext().highlightTagChip(tag, selectedTag == tag.id, compact = true) {
                selectFilter(tag.id)
            })
        }
        binding.filterTags.addView(requireContext().highlightTagChip(
            HighlightTag(id = "", name = getString(R.string.highlight_filter_untagged)),
            selectedTag == "", compact = true
        ) { selectFilter("") })
        binding.filterScroll.post { binding.filterScroll.scrollTo(oldScroll, 0) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tagFilter", selectedTag)
        super.onSaveInstanceState(outState)
    }

    override fun onClick(highlight: BookHighlight) {
        val book = viewModel.bookData.value ?: return
        if (!supportsHighlightPosition(book)) return
        viewLifecycleOwner.lifecycleScope.launch {
            val target = withContext(IO) {
                ReadingNotes.target(ReadingNotes.legacy(highlight),
                    appDb.bookChapterDao.getChapterList(book.bookUrl))
            }
            val chapterIndex = target?.chapterIndex
            if (chapterIndex == null || chapterIndex < 0) {
                activity?.let {
                    ReadingNoteNavigation.unavailable(it, book, "highlight", highlight.time)
                }
                return@launch
            }
            activity?.run {
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("index", chapterIndex)
                    putExtra("chapterPos", target?.chapterPos ?: highlight.chapterPos)
                    putExtra(TocActivityResult.EXTRA_READING_NOTE_KEY, ReadingNotes.key(target!!))
                    putExtra(
                        TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                        target?.layoutTitleLength ?: highlight.layoutTitleLength
                    )
                    putExtra(
                        TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT,
                        target?.quote ?: highlight.bookText.takeIf {
                            highlight.chapterPosEnd - highlight.chapterPos == it.length
                        }.orEmpty()
                    )
                })
                finish()
            }
        }
    }

    override fun onLongClick(highlight: BookHighlight) {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.reading_note_relocate))) { _, position ->
            if (position == 0) showDialogFragment(HighlightNoteDialog(highlight))
            else viewModel.bookData.value?.let { book ->
                ReadingNoteNavigation.relocate(
                    requireActivity(), book, "highlight", highlight.time)
            }
        }.show()
    }

    private fun supportsHighlightPosition(book: Book): Boolean {
        return !book.isAudio && !book.isVideo &&
            (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
    }
}
