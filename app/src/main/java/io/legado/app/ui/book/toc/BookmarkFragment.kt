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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
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
            when {
                searchKey.isNullOrBlank() -> appDb.bookmarkDao.flowByBook(book.name, book.author)
                else -> appDb.bookmarkDao.flowSearch(book.name, book.author, searchKey)
            }.catch {
                AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { bookmarks ->
                adapter?.setItems(bookmarks)
                val scrollPosition = bookmarks
                    .indexOfLast { it.chapterIndex < durChapterIndex }
                    .coerceAtLeast(0)
                layoutManager?.scrollToPositionWithOffset(scrollPosition, 0)
            }
        }
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
                    ReadingNoteNavigation.unavailable(it, book, "bookmark", bookmark.time)
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
                    requireActivity(), book, "bookmark", bookmark.time)
            }
        }.show()
    }

}
