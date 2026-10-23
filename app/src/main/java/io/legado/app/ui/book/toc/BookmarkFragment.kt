package io.legado.app.ui.book.toc

import io.legado.app.help.ReadingNotes
import io.legado.app.ui.book.bookmark.ReadingNoteNavigation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightTag
import io.legado.app.help.HighlightTags
import io.legado.app.ui.highlight.HighlightTagManageActivity
import io.legado.app.databinding.FragmentBookmarkBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.bookmark.BookmarkDialog
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


class BookmarkFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_bookmark),
    BookmarkAdapter.Callback,
    TocViewModel.BookmarkCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentBookmarkBinding::bind)
    private var layoutManager: UpLinearLayoutManager? = null
    private var adapter: BookmarkAdapter? = null
    private var bookmarkJob: Job? = null
    private var durChapterIndex = 0
    private var selectedTag: String? = null
    private var attached = emptyMap<Long, List<HighlightTag>>()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        selectedTag = savedInstanceState?.getString("tagFilter")
        binding.tagFilter.setOnClickListener { showTagFilter() }
        binding.tagManage.setOnClickListener {
            startActivity(Intent(requireContext(), HighlightTagManageActivity::class.java)
                .putExtra("bookUrl", viewModel.bookData.value?.bookUrl)
                .putExtra("bookmarkMode", true))
        }
        viewModel.bookMarkCallBack = this
        initRecyclerView()
        viewModel.bookData.observe(viewLifecycleOwner) {
            durChapterIndex = it.durChapterIndex
            upBookmark(viewModel.searchKey)
        }
    }

    override fun onDestroyView() {
        bookmarkJob?.cancel()
        bookmarkJob = null
        binding.recyclerView.adapter = null
        adapter = null
        layoutManager = null
        viewModel.bookMarkCallBack = clearCallbackIfOwned(
            viewModel.bookMarkCallBack,
            this
        )
        super.onDestroyView()
    }

    private fun initRecyclerView() {
        val layoutManager = UpLinearLayoutManager(requireContext())
        val adapter = BookmarkAdapter(requireContext(), this)
        this.layoutManager = layoutManager
        this.adapter = adapter
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    override fun upBookmark(searchKey: String?) {
        bookmarkJob?.cancel()
        val book = viewModel.bookData.value ?: return
        bookmarkJob = viewLifecycleOwner.lifecycleScope.launch {
            combine(appDb.bookmarkDao.flowByBook(book.name, book.author),
                appDb.highlightTagDao.flowAll(), appDb.bookmarkTagDao.flowLinks(),
                appDb.bookmarkTagDao.flowOrders()) { bookmarks, tags, links, orders ->
                val byId = tags.associateBy { it.id }
                val linksByTime = links.groupBy { it.bookmarkTime }
                val positions = orders.filter { it.bookUrl == book.bookUrl }.associate { it.tagId to it.sortOrder }
                val labels = bookmarks.associate { bookmark ->
                    bookmark.time to linksByTime[bookmark.time].orEmpty().mapNotNull { byId[it.tagId] }
                        .sortedWith(compareBy<HighlightTag> { positions[it.id] ?: Int.MAX_VALUE }
                            .thenBy { it.bookmarkSortOrder }.thenBy { it.id })
                }
                bookmarks to labels
            }.catch {
                AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { (all, labels) ->
                attached = labels
                adapter?.tags = labels
                val bookmarks = all.filter { bookmark ->
                    HighlightTags.matchesFilter(selectedTag, labels[bookmark.time].orEmpty().map { it.id }) &&
                        (searchKey.isNullOrBlank() || listOf(bookmark.chapterName, bookmark.content,
                            bookmark.bookText).any { it.contains(searchKey, true) } ||
                            labels[bookmark.time].orEmpty().any { it.name.contains(searchKey, true) })
                }
                binding.tagFilter.text = selectedTag?.let { id ->
                    if (id.isEmpty()) getString(R.string.highlight_filter_untagged)
                    else labels.values.flatten().firstOrNull { it.id == id }?.name
                } ?: getString(R.string.book_notes_tags)
                adapter?.setItems(bookmarks)
                val scrollPosition = bookmarks
                    .indexOfLast { it.chapterIndex < durChapterIndex }
                    .coerceAtLeast(0)
                layoutManager?.scrollToPositionWithOffset(scrollPosition, 0)
            }
        }
    }

    private fun showTagFilter() {
        val tags = attached.values.flatten().distinctBy { it.id }
        val ids = listOf(null, "") + tags.map { it.id }
        val names = listOf(getString(R.string.highlight_filter_all),
            getString(R.string.highlight_filter_untagged)) + tags.map { it.name }
        AlertDialog.Builder(requireContext()).setTitle(R.string.book_notes_tag_filter)
            .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(selectedTag)) { dialog, index ->
                selectedTag = ids[index]
                upBookmark(viewModel.searchKey)
                dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    override fun onTagClick(id: String) {
        selectedTag = id
        upBookmark(viewModel.searchKey)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tagFilter", selectedTag)
        super.onSaveInstanceState(outState)
    }

    override fun onClick(bookmark: Bookmark) {
        val book = viewModel.bookData.value ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val target = withContext(IO) {
                ReadingNotes.target(ReadingNotes.legacy(bookmark, book.bookUrl),
                    appDb.bookChapterDao.getChapterList(book.bookUrl))
            }
            if (target == null) {
                activity?.let {
                    ReadingNoteNavigation.unavailable(
                        it, book, ReadingNotes.legacy(bookmark, book.bookUrl))
                }
                return@launch
            }
            activity?.run {
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("index", target.chapterIndex)
                    putExtra("chapterPos", target.chapterPos)
                    putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, target.layoutTitleLength)
                    putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, target.quote)
                    putExtra(TocActivityResult.EXTRA_READING_NOTE_KEY, ReadingNotes.key(target))
                })
                finish()
            }
        }
    }

    override fun onLongClick(bookmark: Bookmark, pos: Int) {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.reading_note_relocate))) { _, position ->
            if (position == 0) showDialogFragment(BookmarkDialog(bookmark, pos))
            else viewModel.bookData.value?.let { book ->
                ReadingNoteNavigation.relocate(
                    requireActivity(), book, ReadingNotes.legacy(bookmark, book.bookUrl))
            }
        }.show()
    }

}
