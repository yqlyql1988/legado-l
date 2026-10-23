package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityReadingNoteTaskBinding
import io.legado.app.databinding.DialogReadingNoteDiffBinding
import io.legado.app.databinding.ItemReadingNoteTaskBinding
import io.legado.app.help.ReadingNoteDiff
import io.legado.app.help.ReadingNoteChapter
import io.legado.app.help.ReadingNotes
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingNoteTaskActivity : BaseActivity<ActivityReadingNoteTaskBinding>() {
    override val binding by viewBinding(ActivityReadingNoteTaskBinding::inflate)

    private val adapter = TaskAdapter()
    private var archives = emptyList<NoteBook>()
    private var tasks = emptyList<ReadingNoteTask>()
    private var visibleTasks = emptyList<ReadingNoteTask>()
    private val selected = linkedSetOf<String>()
    private var query = ""
    private var managing = false
    private var scanStarted = false
    private var scanJob: Job? = null
    private lateinit var search: SearchView

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        search = findViewById(R.id.search_view)
        search.applyTint(getCompatColor(R.color.primaryText))
        search.queryHint = getString(R.string.book_notes_search)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                search.clearFocus()
                return true
            }

            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty()
                render()
                return true
            }
        })
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.batchActions.applyNavigationBarPadding()
        binding.titleBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSelectAll.setOnClickListener {
            if (visibleTasks.all { it.id in selected }) selected.clear()
            else selected.addAll(visibleTasks.map { it.id })
            render()
        }
        binding.btnRetry.setOnClickListener { retrySelected() }
        binding.btnManual.setOnClickListener { startManualSelected() }
        binding.btnDelete.setOnClickListener { deleteSelected() }
        onBackPressedDispatcher.addCallback(this) {
            if (managing) {
                managing = false
                selected.clear()
                render()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    BookNotesRepository.archives.collect {
                        val changed = archives != it
                        if (changed) archives = it
                        if ((!scanStarted || changed) && scanJob?.isActive != true) {
                            loadPending()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put(getString(R.string.book_notes_load_failed), e)
                    binding.tvSummary.setText(R.string.book_notes_load_failed)
                }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (scanStarted && scanJob?.isActive != true) loadPending()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }

    private fun scan(onlyIds: Set<String>? = null) {
        if (archives.isEmpty() || onlyIds == null) return
        val scanArchives = archives
        scanStarted = true
        scanJob?.cancel()
        binding.tvEmpty.isVisible = false
        binding.tvSummary.setText(R.string.reading_note_task_scanning)
        scanJob = lifecycleScope.launch {
            try {
                val result = withContext(IO) {
                    ReadingNoteTaskScanner.scan(scanArchives, onlyIds) { done, total ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                binding.tvSummary.text = getString(
                                    R.string.reading_note_task_scanning_progress, done, total
                                )
                            }
                        }
                    }
                }
                if (archives != scanArchives) {
                    scanJob = null
                    loadPending()
                    return@launch
                }
                val previous = tasks.filter { it.id in onlyIds }
                tasks = orderedTasks(tasks.filterNot { it.id in onlyIds } + result)
                val resultById = result.associateBy { it.id }
                val recovered = previous.count { it.id !in resultById }
                val changed = result.count { it.reason == ReadingNoteTaskReason.CHANGED }
                val manual = result.size - changed
                toastOnUi(getString(R.string.reading_note_task_retry_result,
                    recovered, changed, manual))
                selected.retainAll(tasks.map { it.id }.toSet())
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("标注定位检查失败\n${e.localizedMessage}", e)
                binding.tvSummary.setText(R.string.book_notes_load_failed)
            }
        }
    }

    private fun loadPending() {
        if (archives.isEmpty()) return
        scanStarted = true
        scanJob?.cancel()
        binding.tvEmpty.isVisible = false
        binding.tvSummary.setText(R.string.reading_note_task_scanning)
        scanJob = lifecycleScope.launch {
            try {
                val result = withContext(IO) {
                    ReadingNoteTaskScanner.scan(archives, verify = false)
                }
                tasks = orderedTasks(result)
                selected.retainAll(tasks.map { it.id }.toSet())
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("标注定位任务加载失败\n${e.localizedMessage}", e)
                binding.tvSummary.setText(R.string.book_notes_load_failed)
            }
        }
    }

    private fun retrySelected() {
        val target = if (selected.isEmpty()) visibleTasks else tasks.filter { it.id in selected }
        if (target.isEmpty()) {
            toastOnUi(R.string.book_notes_select_first)
            return
        }
        val ids = target.mapTo(hashSetOf()) { it.id }
        scan(ids)
    }

    private fun startManualSelected() {
        val target = if (selected.isEmpty()) visibleTasks else tasks.filter { it.id in selected }
        val queue = target.mapNotNull(::queueItem)
        if (queue.isEmpty()) {
            toastOnUi(R.string.book_notes_unavailable)
            return
        }
        ReadingNoteNavigation.startQueue(this, queue)
    }

    private fun deleteSelected() {
        val target = if (selected.isEmpty()) visibleTasks else tasks.filter { it.id in selected }
        if (target.isEmpty()) {
            toastOnUi(R.string.book_notes_select_first)
            return
        }
        confirmDelete(target)
    }

    private fun confirmDelete(target: List<ReadingNoteTask>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.reading_note_task_delete_confirm, target.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    BookNotesRepository.delete(target.map { it.note })
                    val ids = target.mapTo(hashSetOf()) { it.id }
                    tasks = tasks.filterNot { it.id in ids }
                    selected.removeAll(ids)
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun acceptCurrentQuote(task: ReadingNoteTask) {
        val resolved = task.resolved ?: return
        lifecycleScope.launch {
            val accepted = withContext(IO) {
                ReadingNotes.adoptCurrentQuote(
                    resolved,
                    task.archive.book?.name ?: task.note.bookName,
                    task.archive.book?.author ?: task.note.author,
                )
            }
            if (accepted) {
                tasks = tasks.filterNot { it.id == task.id }
                selected.remove(task.id)
                render()
                toastOnUi(R.string.reading_note_task_accepted)
            } else {
                toastOnUi(R.string.reading_note_task_accept_failed)
            }
        }
    }

    private fun showDiff(task: ReadingNoteTask) {
        val dialogBinding = DialogReadingNoteDiffBinding.inflate(layoutInflater)
        val before = task.beforeQuote.ifBlank { task.note.chapterName }
        val after = task.afterQuote
        val beforeContext = contextText(task.source.prefix, before, task.source.suffix)
        val afterContext = task.resolved?.let {
            contextText(it.prefix, after, it.suffix)
        }.orEmpty()
        val parts = if (afterContext.isNotBlank()) {
            ReadingNoteDiff.parts(beforeContext, afterContext)
        } else emptyList()
        dialogBinding.tvChange.text = buildString {
            append(task.archive.name)
            if (task.archive.author.isNotBlank()) append(" · ").append(task.archive.author)
            append("\n").append(getString(R.string.reading_note_diff_chapter,
                task.note.chapterName,
                task.chapter?.title ?: task.note.chapterName))
            append("\n").append(reasonText(task))
        }
        if (parts.isEmpty()) {
            dialogBinding.tvBefore.text = beforeContext
            dialogBinding.tvAfter.text = getString(R.string.reading_note_task_not_found)
        } else {
            dialogBinding.tvBefore.text = coloredDiff(parts, ReadingNoteDiff.Type.DELETE)
            dialogBinding.tvAfter.text = coloredDiff(parts, ReadingNoteDiff.Type.INSERT)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reading_note_diff_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun coloredDiff(
        parts: List<ReadingNoteDiff.Part>,
        marked: ReadingNoteDiff.Type
    ): CharSequence {
        val builder = SpannableStringBuilder()
        val color = getCompatColor(
            if (marked == ReadingNoteDiff.Type.DELETE) {
                R.color.reading_note_diff_delete
            } else {
                R.color.reading_note_diff_insert
            }
        )
        parts.forEach { part ->
            if (part.type == ReadingNoteDiff.Type.INSERT && marked == ReadingNoteDiff.Type.DELETE) return@forEach
            if (part.type == ReadingNoteDiff.Type.DELETE && marked == ReadingNoteDiff.Type.INSERT) return@forEach
            val start = builder.length
            builder.append(part.text)
            if (part.type == marked) {
                builder.setSpan(BackgroundColorSpan(color), start, builder.length, 0)
            }
        }
        return builder
    }

    private fun contextText(prefix: String, quote: String, suffix: String): String =
        prefix + quote + suffix

    private fun reasonText(task: ReadingNoteTask): String = getString(
        when (task.reason) {
            ReadingNoteTaskReason.PENDING -> R.string.reading_note_task_pending
            ReadingNoteTaskReason.BOOK_UNAVAILABLE -> R.string.reading_note_task_book_unavailable
            ReadingNoteTaskReason.LOCAL_FILE_UNAVAILABLE -> R.string.reading_note_task_local_file_unavailable
            ReadingNoteTaskReason.TOC_UNAVAILABLE -> R.string.reading_note_task_toc_unavailable
            ReadingNoteTaskReason.CHAPTER_NOT_FOUND -> R.string.reading_note_task_chapter_not_found
            ReadingNoteTaskReason.CHAPTER_AMBIGUOUS -> R.string.reading_note_task_chapter_ambiguous
            ReadingNoteTaskReason.SOURCE_UNAVAILABLE -> R.string.reading_note_task_source_unavailable
            ReadingNoteTaskReason.CONTENT_UNAVAILABLE -> R.string.reading_note_task_content_unavailable
            ReadingNoteTaskReason.MULTIPLE_MATCHES -> R.string.reading_note_task_multiple
            ReadingNoteTaskReason.NOT_FOUND -> R.string.reading_note_task_not_found
            ReadingNoteTaskReason.CHANGED -> R.string.reading_note_task_changed
        }
    )

    private fun render() {
        visibleTasks = tasks.filter { it.matches(query) }
        binding.titleBar.title = if (managing) getString(R.string.book_notes_selected, selected.size)
        else getString(R.string.reading_note_task_center)
        binding.tvSummary.text = if (scanJob?.isActive == true) {
            getString(R.string.reading_note_task_scanning)
        } else {
            val bookmarks = visibleTasks.count { it.note.bookmark != null }
            val highlights = visibleTasks.size - bookmarks
            getString(
                R.string.reading_note_task_summary,
                visibleTasks.size,
                bookmarks,
                highlights,
                visibleTasks.count { it.reason != ReadingNoteTaskReason.CHANGED },
                visibleTasks.count { it.reason == ReadingNoteTaskReason.CHANGED }
            )
        }
        binding.tvEmpty.isVisible = scanJob?.isActive != true && visibleTasks.isEmpty()
        binding.batchActions.isVisible = managing && visibleTasks.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelected(task: ReadingNoteTask) {
        if (task.id in selected) selected.remove(task.id) else selected.add(task.id)
        render()
    }

    private fun orderedTasks(items: List<ReadingNoteTask>): List<ReadingNoteTask> =
        items.sortedWith(compareBy<ReadingNoteTask> { it.archive.id }
            .thenBy { reasonOrder(it.reason) }
            .thenBy { it.note.chapterIndex }
            .thenBy { it.note.time })

    private fun reasonOrder(reason: ReadingNoteTaskReason): Int = when (reason) {
        ReadingNoteTaskReason.PENDING -> 0
        ReadingNoteTaskReason.BOOK_UNAVAILABLE -> 1
        ReadingNoteTaskReason.LOCAL_FILE_UNAVAILABLE -> 2
        ReadingNoteTaskReason.TOC_UNAVAILABLE -> 3
        ReadingNoteTaskReason.CHAPTER_NOT_FOUND -> 4
        ReadingNoteTaskReason.CHAPTER_AMBIGUOUS -> 5
        ReadingNoteTaskReason.SOURCE_UNAVAILABLE -> 6
        ReadingNoteTaskReason.CONTENT_UNAVAILABLE -> 7
        ReadingNoteTaskReason.MULTIPLE_MATCHES -> 8
        ReadingNoteTaskReason.NOT_FOUND -> 9
        ReadingNoteTaskReason.CHANGED -> 10
    }

    private fun showActions(task: ReadingNoteTask, anchor: View) {
        popupActionMenu(this) {
            item(getString(R.string.reading_note_diff_title), "diff")
            if (task.reason == ReadingNoteTaskReason.CHANGED && task.resolved != null) {
                item(getString(R.string.reading_note_task_accept), "accept")
            }
            item(getString(R.string.reading_note_relocate), "manual")
            item(getString(R.string.reading_note_task_skip), "skip")
            item(getString(R.string.delete), "delete")
            danger("delete")
        }.show(anchor) { action ->
            when (action) {
                "diff" -> showDiff(task)
                "accept" -> acceptCurrentQuote(task)
                "manual" -> {
                    selected.clear()
                    startManual(listOf(task))
                }
                "skip" -> {
                    tasks = tasks.filterNot { it.id == task.id }
                    render()
                }
                "delete" -> confirmDelete(listOf(task))
            }
        }
    }

    private fun startManual(items: List<ReadingNoteTask>) {
        val queue = items.mapNotNull(::queueItem)
        if (queue.isNotEmpty()) ReadingNoteNavigation.startQueue(this, queue)
        else toastOnUi(R.string.book_notes_unavailable)
    }

    private fun queueItem(task: ReadingNoteTask): ReadingNoteNavigation.QueueItem? {
        val book = task.archive.book ?: return null
        val chapter = task.chapter
        val resolved = task.resolved
        val sameChapter = chapter != null && task.source.chapterUrl.isNotBlank() &&
            ReadingNoteChapter.sameUrl(task.source.chapterUrl, chapter.url)
        val location = resolved ?: task.source
        return ReadingNoteNavigation.QueueItem(
            bookUrl = book.bookUrl,
            kind = if (task.note.highlight != null) "highlight" else "bookmark",
            time = task.note.time,
            chapterIndex = chapter?.index ?: location.chapterIndex,
            chapterUrl = resolved?.chapterUrl ?: chapter?.url.orEmpty(),
            chapterName = chapter?.title ?: location.chapterName,
            chapterPos = resolved?.chapterPos ?: location.chapterPos.takeIf { sameChapter } ?: 0,
            layoutTitleLength = location.layoutTitleLength,
            quote = ReadingNotes.anchorQuote(location),
        )
    }

    private inner class TaskAdapter : RecyclerView.Adapter<TaskHolder>() {
        override fun getItemCount() = visibleTasks.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TaskHolder(ItemReadingNoteTaskBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            ))

        override fun onBindViewHolder(holder: TaskHolder, position: Int) {
            val task = visibleTasks[position]
            holder.row.run {
                tvBook.text = buildString {
                    if (task.archive.name.isNotBlank()) append("《").append(task.archive.name).append("》")
                    task.archive.author.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                }
                tvChapter.text = task.chapter?.title ?: task.note.chapterName
                tvStatus.text = reasonText(task)
                tvBefore.text = getString(R.string.reading_note_task_before,
                    task.beforeQuote.ifBlank { task.note.chapterName })
                tvAfter.isVisible = task.afterQuote.isNotBlank()
                tvAfter.text = if (tvAfter.isVisible) {
                    getString(R.string.reading_note_task_after, task.afterQuote)
                } else ""
                tvMeta.text = getString(
                    R.string.reading_note_task_meta,
                    getString(if (task.note.highlight != null) R.string.book_notes_highlight
                    else R.string.bookmark),
                    task.candidateCount.takeIf { it > 1 }?.toString()
                        ?: getString(R.string.reading_note_task_manual)
                )
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.topMargin = if (position > 0 &&
                        visibleTasks[position - 1].archive.id != task.archive.id
                    ) 8.dpToPx() else 0
                    root.layoutParams = params
                }
                check.isVisible = managing
                check.setOnCheckedChangeListener(null)
                check.isChecked = task.id in selected
                check.setOnCheckedChangeListener { _, _ -> toggleSelected(task) }
                taskBody.setOnClickListener {
                    if (managing) toggleSelected(task) else showDiff(task)
                }
                taskBody.setOnLongClickListener {
                    managing = true
                    selected.add(task.id)
                    render()
                    true
                }
                btnMore.setOnClickListener { showActions(task, it) }
            }
        }
    }

    private class TaskHolder(val row: ItemReadingNoteTaskBinding) :
        RecyclerView.ViewHolder(row.root)
}

private fun ReadingNoteTask.matches(query: String): Boolean {
    val key = query.trim()
    if (key.isBlank()) return true
    return listOf(
        archive.name, archive.author, note.chapterName, beforeQuote, afterQuote
    ).any { it.contains(key, true) }
}
