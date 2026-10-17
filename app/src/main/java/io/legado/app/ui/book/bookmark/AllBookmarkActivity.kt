package io.legado.app.ui.book.bookmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.databinding.ActivityAllBookmarkBinding
import io.legado.app.databinding.ItemBookNoteBinding
import io.legado.app.databinding.ItemNoteBookBinding
import io.legado.app.help.HighlightTags
import io.legado.app.help.book.readProgress
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.read.HighlightNoteDialog
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.resolveHighlightChapterIndex
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.highlight.highlightTagChip
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Replaces the original all-bookmarks screen, keeping its activity and export entry points. */
class AllBookmarkActivity : VMBaseActivity<ActivityAllBookmarkBinding, AllBookmarkViewModel>() {
    override val viewModel by viewModels<AllBookmarkViewModel>()
    override val binding by viewBinding(ActivityAllBookmarkBinding::inflate)
    private val bookAdapter = BooksAdapter()
    private val notesAdapter = NotesAdapter()
    private val archiveDivider by lazy {
        DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
    }
    private var archives = emptyList<NoteBook>()
    private var visibleBooks = emptyList<NoteBook>()
    private var visibleNotes = emptyList<BookNote>()
    private var selectedBook: String? = null
    private var type = NoteType.ALL
    private var tagId: String? = null
    private var query = ""
    private var recent = false
    private var managing = false
    private val selected = linkedSetOf<String>()
    private var exportIds = emptySet<String>()
    private var exportRequest = 0
    private var chapterIndexes = emptyMap<String, Int>()
    private lateinit var search: SearchView
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val archive get() = archives.firstOrNull { it.id == selectedBook }
    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            when (result.requestCode.takeIf { it != 0 } ?: exportRequest) {
                // Original exports remain available, with their original all-bookmark scope.
                1 -> viewModel.exportBookmark(uri)
                2 -> viewModel.exportBookmarkMd(uri)
                3 -> runTask {
                    val current = BookNotesRepository.archives.first().flatMap { it.notes }.associateBy { it.id }
                    val notes = exportIds.mapNotNull(current::get)
                    if (notes.isEmpty()) toastOnUi(R.string.book_notes_select_first)
                    else {
                        BookNotesRepository.exportMarkdown(uri, notes)
                        toastOnUi(R.string.export_success)
                    }
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        selectedBook = savedInstanceState?.getString("selectedBook")
            ?: intent.getStringExtra("bookUrl")?.let { "url:$it" }
        type = NoteType.entries.firstOrNull { it.name == savedInstanceState?.getString("type") }
            ?: NoteType.ALL
        tagId = savedInstanceState?.getString("tagId")
        recent = getPrefBoolean("bookNotesRecent", false)
        managing = savedInstanceState?.getBoolean("managing") == true
        selected.addAll(savedInstanceState?.getStringArrayList("selected").orEmpty())
        exportIds = savedInstanceState?.getStringArrayList("exportIds").orEmpty().toSet()
        exportRequest = savedInstanceState?.getInt("exportRequest") ?: 0
        search = binding.root.findViewById(R.id.search_view)
        search.applyTint(getCompatColor(R.color.primaryText))
        search.queryHint = getString(R.string.book_notes_search)
        search.isVisible = savedInstanceState?.getBoolean("searchVisible") == true
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean { search.clearFocus(); return true }
            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty()
                render()
                return true
            }
        })
        search.setQuery(savedInstanceState?.getString("query").orEmpty(), false)
        binding.noteTabs.setSelectedTabIndicatorColor(accentColor)
        binding.noteTabs.isTabIndicatorFullWidth = false
        NoteType.entries.forEach { binding.noteTabs.addTab(binding.noteTabs.newTab()) }
        binding.noteTabs.getTabAt(type.ordinal)?.select()
        binding.noteTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                type = NoteType.entries[tab.position]
                if (type == NoteType.BOOKMARK) tagId = null
                selected.clear()
                render()
                binding.recyclerView.scrollToPosition(0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.recyclerView.applyNavigationBarPadding()
        binding.batchActions.applyNavigationBarPadding()
        binding.tvContinue.setOnClickListener { openReading(null) }
        binding.tvSort.setOnClickListener {
            recent = !recent
            putPrefBoolean("bookNotesRecent", recent)
            render()
            binding.recyclerView.scrollToPosition(0)
        }
        binding.tvTagFilter.setOnClickListener { showTagFilter() }
        binding.btnSelectAll.setOnClickListener {
            if (visibleNotes.all { it.id in selected }) selected.clear()
            else selected.addAll(visibleNotes.map { it.id })
            render()
        }
        binding.btnExport.setOnClickListener { requestNotesExport(selectedOnly = true) }
        binding.btnDelete.setOnClickListener {
            val notes = visibleNotes.filter { it.id in selected }
            if (notes.isEmpty()) toastOnUi(R.string.book_notes_select_first)
            else confirmDelete(notes)
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                managing -> { managing = false; selected.clear(); render() }
                search.isVisible -> { search.setQuery("", false); search.isVisible = false }
                selectedBook != null -> {
                    selectedBook = null
                    type = NoteType.ALL
                    tagId = null
                    chapterIndexes = emptyMap()
                    render()
                }
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    BookNotesRepository.archives.collect {
                        archives = it
                        if (selectedBook != null && archive == null) {
                            selectedBook = null
                            type = NoteType.ALL
                            tagId = null
                            managing = false
                            binding.noteTabs.getTabAt(0)?.select()
                        }
                        chapterIndexes = withContext(IO) {
                            archive?.book?.let { book ->
                                appDb.bookChapterDao.getChapterList(book.bookUrl)
                                    .associate { chapter -> chapter.url to chapter.index }
                            }.orEmpty()
                        }
                        selected.retainAll(archive?.notes.orEmpty().map { note -> note.id }.toSet())
                        render()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put(getString(R.string.book_notes_load_failed), e)
                    binding.tvEmpty.setText(R.string.book_notes_load_failed)
                    binding.tvEmpty.isVisible = true
                }
            }
        }
        render()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bookmark, menu)
        menu.findItem(R.id.menu_manage).isVisible = selectedBook != null
        menu.findItem(R.id.menu_manage).setTitle(if (managing) R.string.book_notes_done else R.string.book_notes_manage)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressedDispatcher.onBackPressed()
            R.id.menu_search -> {
                search.isVisible = !search.isVisible
                if (search.isVisible) { search.isIconified = false; search.requestFocus() }
                else { search.setQuery("", false); search.clearFocus() }
            }
            R.id.menu_manage -> { managing = !managing; selected.clear(); render() }
            R.id.menu_export -> { exportRequest = 1; exportDir.launch { requestCode = 1 } }
            R.id.menu_export_md -> { exportRequest = 2; exportDir.launch { requestCode = 2 } }
            R.id.menu_export_notes -> requestNotesExport()
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun render() {
        if (!::search.isInitialized) return
        val book = archive
        val details = selectedBook != null
        binding.bookHeading.isVisible = details
        binding.noteTabs.isVisible = details
        binding.tvTagFilter.isVisible = details && type != NoteType.BOOKMARK
        binding.batchActions.isVisible = details && managing
        binding.titleBar.title = if (managing) getString(R.string.book_notes_selected, selected.size)
            else getString(R.string.book_notes)
        if (details) {
            binding.tvBookName.text = book?.name.orEmpty()
            val shelfBook = book?.book
            binding.tvBookAuthor.text = if (shelfBook == null) getString(
                if (book?.ambiguous == true) R.string.book_notes_ambiguous
                else R.string.book_notes_unavailable)
            else listOfNotNull(book.author.takeIf { it.isNotBlank() },
                shelfBook.readProgress()?.let {
                    getString(R.string.book_notes_progress, String.format(Locale.getDefault(), "%.1f%%", it * 100))
                }).joinToString(" · ")
            binding.tvContinue.isVisible = shelfBook != null
            book?.asBook()?.let { binding.ivCover.load(it, false) }
            binding.noteTabs.getTabAt(0)?.text = getString(R.string.book_notes_tab_all, book?.notes?.size ?: 0)
            binding.noteTabs.getTabAt(1)?.text = getString(R.string.book_notes_tab_bookmark, book?.bookmarkCount ?: 0)
            binding.noteTabs.getTabAt(2)?.text = getString(R.string.book_notes_tab_highlight, book?.highlightCount ?: 0)
            visibleNotes = filterBookNotes(book?.notes.orEmpty(), type, tagId, query, recent, chapterIndexes)
            binding.tvSummary.text = getString(R.string.book_notes_count, visibleNotes.size)
            binding.tvTagFilter.text = tagId?.let { id ->
                if (id.isEmpty()) getString(R.string.highlight_filter_untagged)
                else book?.notes?.flatMap { it.tags }?.firstOrNull { it.id == id }?.name
            } ?: getString(R.string.book_notes_tags)
            binding.tvSort.setText(if (recent) R.string.book_notes_recent else R.string.book_notes_chapter_order)
            if (binding.recyclerView.adapter !== notesAdapter) {
                binding.recyclerView.removeItemDecoration(archiveDivider)
                binding.recyclerView.adapter = notesAdapter
            }
            notesAdapter.notifyDataSetChanged()
        } else {
            visibleBooks = archives.filter { entry -> entry.notes.any { it.matches(query.trim()) } }
            binding.tvSummary.text = getString(R.string.book_notes_summary, visibleBooks.size,
                visibleBooks.sumOf { it.bookmarkCount }, visibleBooks.sumOf { it.highlightCount })
            binding.tvSort.setText(R.string.book_notes_recent)
            binding.tvSort.isClickable = false
            if (binding.recyclerView.adapter !== bookAdapter) {
                binding.recyclerView.adapter = bookAdapter
                binding.recyclerView.addItemDecoration(archiveDivider)
            }
            bookAdapter.notifyDataSetChanged()
        }
        binding.tvSort.isClickable = details
        binding.tvEmpty.isVisible = if (details) visibleNotes.isEmpty() else visibleBooks.isEmpty()
        binding.tvEmpty.setText(if (query.isNotBlank() || tagId != null) R.string.book_notes_search_empty
            else R.string.book_notes_empty)
        invalidateOptionsMenu()
    }

    private fun showTagFilter() {
        val tags = archive?.notes.orEmpty().flatMap { it.tags }.distinctBy { it.id }
        val ids = listOf(null, "") + tags.map { it.id }
        val names = listOf(getString(R.string.highlight_filter_all),
            getString(R.string.highlight_filter_untagged)) + tags.map { it.name }
        AlertDialog.Builder(this).setTitle(R.string.book_notes_tag_filter)
            .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(tagId)) { dialog, index ->
                tagId = ids[index]
                selected.clear()
                render()
                binding.recyclerView.scrollToPosition(0)
                dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun openReading(note: BookNote?) = runTask {
        val url = archive?.bookUrl.orEmpty()
        val book = withContext(IO) { appDb.bookDao.getBook(url) }
        if (book == null) {
            if (note == null) toastOnUi(R.string.book_notes_unavailable) else showFullNote(note)
            return@runTask
        }
        if (note == null) {
            // Let the reader restore its saved progress. Never pass a note's index/position here.
            startActivityForBook(book)
        } else {
            val index = note.highlight?.let { highlight ->
                withContext(IO) {
                    resolveHighlightChapterIndex(highlight, appDb.bookChapterDao.getChapterList(url)
                        .associate { it.url to it.index })
                }
            } ?: note.bookmark?.chapterIndex
            if (index == null || index < 0 || index >= book.totalChapterNum) {
                toastOnUi(R.string.book_notes_location_unavailable)
                return@runTask
            }
            startActivityForBook(book) {
                putExtra("index", index)
                putExtra("chapterPos", note.position)
                note.highlight?.let {
                    putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, it.layoutTitleLength)
                    putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT,
                        it.bookText.takeIf { text -> it.chapterPosEnd - it.chapterPos == text.length }.orEmpty())
                }
            }
        }
    }

    private fun showFullNote(note: BookNote) {
        AlertDialog.Builder(this).setTitle(note.chapterName)
            .setMessage(notesMarkdown(listOf(note))).setPositiveButton(R.string.ok, null).show()
    }

    private fun showActions(note: BookNote, anchor: View) {
        popupActionMenu(this) {
            item(getString(R.string.book_notes_view_full), "view")
            item(getString(R.string.edit), "edit")
            item(getString(R.string.copy_text), "copy")
            item(getString(R.string.share), "share")
            if (note.highlight != null) item(getString(R.string.book_notes_tags), "tags")
            item(getString(R.string.delete), "delete")
            danger("delete")
        }.show(anchor) { action ->
            when (action) {
                "view" -> showFullNote(note)
                "edit" -> if (note.bookmark != null) showDialogFragment(BookmarkDialog(note.bookmark, 0))
                    else showDialogFragment(HighlightNoteDialog(note.highlight!!))
                "copy" -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(note.chapterName, notesMarkdown(listOf(note))))
                "share" -> share(notesMarkdown(listOf(note)))
                "tags" -> editTags(note)
                "delete" -> confirmDelete(listOf(note))
            }
        }
    }

    private fun editTags(note: BookNote) = runTask {
        val highlight = note.highlight ?: return@runTask
        val tags = withContext(IO) { appDb.highlightTagDao.forBook(highlight.bookUrl) }
        if (tags.isEmpty()) { toastOnUi(R.string.book_notes_no_tags); return@runTask }
        val ids = note.tags.mapTo(linkedSetOf()) { it.id }
        AlertDialog.Builder(this).setTitle(R.string.book_notes_tags)
            .setMultiChoiceItems(tags.map { it.name }.toTypedArray(),
                tags.map { it.id in ids }.toBooleanArray()) { _, index, checked ->
                if (checked) ids.add(tags[index].id) else ids.remove(tags[index].id)
            }.setPositiveButton(R.string.ok) { _, _ ->
                runTask {
                    withContext(IO) {
                        appDb.runInTransaction {
                            appDb.highlightTagDao.deleteLinksForHighlights(listOf(highlight.time))
                            appDb.highlightTagDao.upsertLinks(ids.filter {
                                appDb.highlightTagDao.canAttach(highlight.time, it)
                            }.map { HighlightTagLink(highlight.time, it) })
                        }
                    }
                    BookNotesRepository.refreshReader(setOf(highlight.bookUrl))
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmDelete(notes: List<BookNote>) {
        AlertDialog.Builder(this).setTitle(R.string.delete)
            .setMessage(getString(R.string.book_notes_delete_confirm, notes.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                runTask { BookNotesRepository.delete(notes); selected.removeAll(notes.map { it.id }.toSet()); render() }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun requestNotesExport(selectedOnly: Boolean = false) {
        val notes = if (selectedBook == null) visibleBooks.flatMap { it.notes }
            else visibleNotes.filter { !selectedOnly || it.id in selected }
        if (notes.isEmpty()) { toastOnUi(R.string.book_notes_select_first); return }
        exportIds = notes.mapTo(linkedSetOf()) { it.id }
        exportRequest = 3
        exportDir.launch { requestCode = 3 }
    }

    private fun runTask(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.put(e.localizedMessage.orEmpty(), e); toastOnUi(e.localizedMessage.orEmpty()) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedBook", selectedBook)
        outState.putString("type", type.name)
        outState.putString("tagId", tagId)
        outState.putString("query", query)
        outState.putBoolean("searchVisible", search.isVisible)
        outState.putBoolean("managing", managing)
        outState.putStringArrayList("selected", ArrayList(selected))
        outState.putStringArrayList("exportIds", ArrayList(exportIds))
        outState.putInt("exportRequest", exportRequest)
        super.onSaveInstanceState(outState)
    }

    private inner class BooksAdapter : RecyclerView.Adapter<BookHolder>() {
        override fun getItemCount() = visibleBooks.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            BookHolder(ItemNoteBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: BookHolder, position: Int) {
            val book = visibleBooks[position]
            holder.row.run {
                ivCover.load(book.asBook(), false)
                tvName.text = book.name
                tvAuthor.text = book.author
                tvAuthor.isVisible = book.author.isNotBlank()
                tvCounts.text = getString(R.string.book_notes_book_count, book.bookmarkCount, book.highlightCount)
                tvDate.text = getString(R.string.book_notes_latest, dateFormat.format(Date(book.latestTime)))
                root.setOnClickListener {
                    selectedBook = book.id
                    type = NoteType.ALL
                    tagId = null
                    binding.noteTabs.getTabAt(0)?.select()
                    search.setQuery("", false)
                    search.isVisible = false
                    runTask {
                        chapterIndexes = withContext(IO) {
                            appDb.bookChapterDao.getChapterList(book.bookUrl)
                                .associate { it.url to it.index }
                        }
                        render()
                        binding.recyclerView.scrollToPosition(0)
                    }
                }
            }
        }
    }
    private class BookHolder(val row: ItemNoteBookBinding) : RecyclerView.ViewHolder(row.root)

    private inner class NotesAdapter : RecyclerView.Adapter<NoteHolder>() {
        override fun getItemCount() = visibleNotes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            NoteHolder(ItemBookNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: NoteHolder, position: Int) {
            val note = visibleNotes[position]
            holder.row.run {
                tvChapter.text = note.chapterName
                tvChapter.isVisible = recent || position == 0 ||
                    visibleNotes[position - 1].let {
                        it.chapterName != note.chapterName || it.chapterIndex != note.chapterIndex
                    }
                tvQuote.text = note.quote.ifBlank { note.chapterName }
                tvNote.text = getString(if (note.bookmark != null) R.string.book_notes_remark
                    else R.string.book_notes_note, note.note)
                tvNote.isVisible = note.note.isNotBlank()
                tvMeta.text = "${getString(if (note.bookmark != null) R.string.bookmark else R.string.book_notes_highlight)} · ${dateFormat.format(Date(note.time))}"
                check.isVisible = managing
                check.setOnCheckedChangeListener(null)
                check.isChecked = note.id in selected
                check.contentDescription = note.quote.ifBlank { note.chapterName }
                check.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(note.id) else selected.remove(note.id)
                    binding.titleBar.title = getString(R.string.book_notes_selected, selected.size)
                }
                val style = note.highlight?.let {
                    HighlightTags.effectiveStyle(it, note.tags, note.tags.map { tag -> tag.id })
                }
                val mark = style?.fill?.takeIf { it != 0 } ?: style?.textColor?.takeIf { it != 0 }
                    ?: style?.underline?.color?.takeIf { it != 0 }
                    ?: style?.box?.color?.takeIf { it != 0 }
                    ?: accentColor
                styleMark.backgroundTintList = ColorStateList.valueOf(mark)
                styleMark.setBackgroundColor(mark)
                tagLabels.removeAllViews()
                tagLabels.isVisible = note.tags.isNotEmpty()
                note.tags.forEach { tag ->
                    tagLabels.addView(highlightTagChip(tag, true, compact = true) {
                        tagId = tag.id
                        if (type == NoteType.BOOKMARK) {
                            type = NoteType.HIGHLIGHT
                            binding.noteTabs.getTabAt(type.ordinal)?.select()
                        }
                        selected.clear()
                        render()
                    })
                }
                noteBody.setOnClickListener {
                    if (managing) { check.isChecked = !check.isChecked }
                    else openReading(note)
                }
                noteBody.setOnLongClickListener {
                    managing = true
                    selected.add(note.id)
                    render()
                    true
                }
                btnMore.setOnClickListener { showActions(note, it) }
            }
        }
    }
    private class NoteHolder(val row: ItemBookNoteBinding) : RecyclerView.ViewHolder(row.root)
}
