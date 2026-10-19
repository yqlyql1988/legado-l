package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.BookmarkTags
import io.legado.app.ui.highlight.NoteTagPickerDialog
import io.legado.app.ui.highlight.HighlightTagManageActivity
import io.legado.app.ui.highlight.highlightTagChip
import io.legado.app.databinding.DialogBookmarkBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarkDialog() : BaseDialogFragment(R.layout.dialog_bookmark, true) {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    private val binding by viewBinding(DialogBookmarkBinding::bind)
    private val selectedTags = linkedSetOf<String>()
    private var bookUrl = ""

    override fun onResume() {
        super.onResume()
        if (view != null) renderTags()
    }

    private fun renderTags() {
        val all = appDb.highlightTagDao.all()
        val choices = (appDb.bookmarkTagDao.forBook(bookUrl) +
            all.filter { it.id in selectedTags }.map { it.forBookmark() }).distinctBy { it.id }
        binding.tagChoices.removeAllViews()
        choices.forEach { tag ->
            binding.tagChoices.addView(requireContext().highlightTagChip(tag,
                tag.id in selectedTags, compact = true) {
                if (!selectedTags.add(tag.id)) selectedTags.remove(tag.id)
                renderTags()
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selectedTags", ArrayList(selectedTags))
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        val arguments = arguments ?: let {
            dismiss()
            return
        }

        @Suppress("DEPRECATION")
        val bookmark = arguments.getParcelable<Bookmark>("bookmark")
        bookmark ?: let {
            dismiss()
            return
        }
        val editPos = arguments.getInt("editPos", -1)
        bookUrl = BookmarkTags.bookUrl(bookmark)
        selectedTags.addAll(savedInstanceState?.getStringArrayList("selectedTags")
            ?: appDb.bookmarkTagDao.idsForBookmark(bookmark.time))
        childFragmentManager.setFragmentResultListener("bookmark-tags", viewLifecycleOwner) { _, result ->
            selectedTags.clear()
            selectedTags.addAll(result.getStringArrayList("ids").orEmpty())
            renderTags()
        }
        binding.tagMore.setOnClickListener {
            NoteTagPickerDialog.create(bookUrl, true, selectedTags, bookmark.time,
                direct = false, resultKey = "bookmark-tags").show(childFragmentManager, "bookmark-tag-picker")
        }
        binding.toolBar.menu.add(R.string.bookmark_tags).setOnMenuItemClickListener {
            startActivity(Intent(requireContext(), HighlightTagManageActivity::class.java)
                .putExtra("bookUrl", bookUrl).putExtra("bookmarkMode", true))
            true
        }
        renderTags()
        binding.tvFooterLeft.visible(editPos >= 0)
        binding.run {
            tvChapterName.text = bookmark.chapterName
            editBookText.setText(bookmark.bookText)
            editContent.setText(bookmark.content)
            tvCancel.setOnClickListener {
                dismiss()
            }
            tvOk.setOnClickListener {
                bookmark.bookText = editBookText.text?.toString() ?: ""
                bookmark.content = editContent.text?.toString() ?: ""
                val ids = selectedTags.toList()
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.runInTransaction {
                            appDb.bookmarkDao.insert(bookmark)
                            appDb.bookmarkTagDao.saveLinks(bookmark.time, bookUrl, ids)
                        }
                        io.legado.app.model.ReadBook.captureBookmarkLocation(bookmark)
                    }
                    dismiss()
                }
            }
            tvFooterLeft.setOnClickListener {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookmarkDao.delete(bookmark)
                        appDb.readingNoteDao.deleteLocations("bookmark", listOf(bookmark.time))
                    }
                    dismiss()
                }
            }
        }
    }

}
