package io.legado.app.ui.book.readingmark

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookReadingMark
import io.legado.app.data.entities.ReadingMarkChapter
import io.legado.app.data.entities.ReadingMarkResolver
import io.legado.app.data.entities.readingMarkChapter
import io.legado.app.data.entities.withSkipped
import io.legado.app.databinding.DialogReadingMarkBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class ReadingMarkDialog : BaseDialogFragment(R.layout.dialog_reading_mark) {
    private val binding by viewBinding(DialogReadingMarkBinding::bind)
    private lateinit var book: Book
    private var chapters: List<BookChapter> = emptyList()
    private var draft: BookReadingMark? = null
    private var original: BookReadingMark? = null
    private var saving = false

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.8f)
        dialog?.window?.apply {
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Suppress("DEPRECATION")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        book = requireArguments().getParcelable<Book>("book") ?: run { dismiss(); return }
        binding.markBook.text = listOf(book.name, book.author).filter { it.isNotBlank() }.joinToString(" · ")
        binding.markSave.isEnabled = false
        binding.markCancel.setOnClickListener { dismiss() }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loaded = withContext(IO) {
                    val current = appDb.bookDao.getBook(book.bookUrl)
                    // Keep a live reader's exact character offset instead of a queued DB snapshot.
                    if (ReadBook.book?.bookUrl != book.bookUrl && current != null) book = current
                    chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
                    appDb.bookReadingMarkDao.get(book.name, book.author)
                        ?: BookReadingMark(book.name, book.author)
                }
                draft = savedInstanceState?.getString("draft")?.let {
                    GSON.fromJson(it, BookReadingMark::class.java)
                } ?: loaded
                original = savedInstanceState?.getString("original")?.let {
                    GSON.fromJson(it, BookReadingMark::class.java)
                } ?: loaded
                bindControls(savedInstanceState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                requireContext().toastOnUi(e.localizedMessage ?: getString(R.string.reading_mark_failed))
            }
        }
    }

    private fun bindControls(state: Bundle?) = binding.run {
        val mark = requireNotNull(draft)
        markReason.setText(mark.reason)
        markStatus.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            BookReadingMark.STATUSES.map { requireContext().readingMarkStatus(it) })
        val requested = if (state == null) arguments?.getString("status") else null
        if (requested != null) changeStatus(requested, force = true)
        markStatus.setSelection(BookReadingMark.STATUSES.indexOf(requireNotNull(draft).status))
        markStatus.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                changeStatus(BookReadingMark.STATUSES[position])
            }
        }
        val labels = chapters.map { getString(R.string.reading_mark_chapter, it.index + 1, it.title) }
        markFrom.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        markTo.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        val initial = chapters.indexOfFirst { it.index == arguments?.getInt("rangeStart", book.durChapterIndex) }
            .coerceAtLeast(0)
        markFrom.setSelection(state?.getInt("from") ?: initial)
        markTo.setSelection(state?.getInt("to") ?: initial)
        markRangeFields.isVisible = state?.getBoolean("rangeVisible")
            ?: (arguments?.containsKey("rangeStart") == true)
        markChooseRange.setOnClickListener {
            if (chapters.isEmpty()) requireContext().toastOnUi(R.string.reading_mark_empty_catalog)
            else markRangeFields.isVisible = !markRangeFields.isVisible
        }
        markAddRange.setOnClickListener { changeRange(remove = false) }
        markRemoveRange.setOnClickListener { changeRange(remove = true) }
        markCurrent.setOnClickListener {
            draft = requireNotNull(draft).copy(position = capturePosition(BookReadingMark.ABANDONED))
            renderStatus()
        }
        markLocate.setOnClickListener { draft?.position?.let(::openPosition) }
        markDate.setOnClickListener {
            val date = draft?.date?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: LocalDate.now()
            DatePickerDialog(requireContext(), { _, year, month, day ->
                draft = requireNotNull(draft).copy(date = LocalDate.of(year, month + 1, day).toString())
                renderStatus()
            }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        markClear.setOnClickListener {
            requireContext().alert(R.string.reading_mark_clear, R.string.reading_mark_clear_confirm) {
                positiveButton(R.string.clear) {
                    draft = BookReadingMark(book.name, book.author, updatedAt = requireNotNull(draft).updatedAt)
                    markReason.setText("")
                    markStatus.setSelection(0)
                    renderStatus()
                    renderSkipped()
                }
                cancelButton()
            }
        }
        markSave.isEnabled = true
        markSave.setOnClickListener { save() }
        renderStatus()
        renderSkipped()
    }

    private fun capturePosition(status: String): ReadingMarkChapter? {
        if (status == BookReadingMark.FINISHED) return chapters.lastOrNull()?.readingMarkChapter()
        val active = ReadBook.book?.bookUrl == book.bookUrl && book.bookUrl.isNotBlank()
        val index = if (active) ReadBook.durChapterIndex else book.durChapterIndex
        val pos = if (active) ReadBook.durChapterPos else book.durChapterPos
        return chapters.firstOrNull { it.index == index }?.readingMarkChapter(pos)
            ?: book.durChapterTitle?.takeIf { it.isNotBlank() }?.let {
                ReadingMarkChapter(book.bookUrl, "", it, index.coerceAtLeast(0), pos.coerceAtLeast(0))
            }
    }

    private fun changeStatus(status: String, force: Boolean = false) {
        val mark = draft ?: return
        if (!force && mark.status == status) return
        draft = mark.copy(status = status,
            date = if (status == BookReadingMark.UNMARKED) "" else LocalDate.now().toString(),
            position = if (status == BookReadingMark.UNMARKED) null else capturePosition(status))
        renderStatus()
    }

    private fun renderStatus() = binding.run {
        val mark = draft ?: return
        val marked = mark.status != BookReadingMark.UNMARKED
        markDate.isVisible = marked
        markDate.text = getString(R.string.reading_mark_date, mark.date)
        markReason.isVisible = mark.status == BookReadingMark.ABANDONED
        markPosition.isVisible = marked
        markPosition.text = getString(R.string.reading_mark_position,
            mark.position?.let {
                if (it.pos > 0) getString(R.string.reading_mark_position_detail, it.title) else it.title
            }
                ?: getString(R.string.reading_mark_no_position))
        markLocate.isVisible = marked && mark.position != null
        markCurrent.isVisible = mark.status == BookReadingMark.ABANDONED
    }

    private fun changeRange(remove: Boolean) {
        val from = binding.markFrom.selectedItemPosition
        val to = binding.markTo.selectedItemPosition
        if (from !in chapters.indices || to !in chapters.indices) return
        if (to < from) {
            requireContext().toastOnUi(R.string.reading_mark_range_invalid)
            return
        }
        draft = requireNotNull(draft).withSkipped(chapters, chapters.subList(from, to + 1), remove)
        renderSkipped()
    }

    private fun renderSkipped(): Unit = binding.run {
        val skipped = draft?.skipped.orEmpty()
        markSkippedTitle.text = getString(R.string.reading_mark_skipped) +
            if (skipped.isEmpty()) "" else " · ${getString(R.string.reading_mark_skip_count, skipped.size)}"
        markSkippedList.removeAllViews()
        if (skipped.isEmpty()) {
            markSkippedList.addView(TextView(requireContext()).apply {
                text = getString(R.string.reading_mark_no_skipped)
                setTextColor(ThemeStore.textColorSecondary(context))
                textSize = 13f
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            })
        }
        val groups = mutableListOf<MutableList<ReadingMarkChapter>>()
        skipped.sortedWith(compareBy({ it.bookUrl }, { it.index })).forEach { chapter ->
            val last = groups.lastOrNull()?.lastOrNull()
            if (last != null && last.bookUrl == chapter.bookUrl && last.index + 1 == chapter.index)
                groups.last().add(chapter)
            else groups.add(mutableListOf(chapter))
        }
        groups.forEach { group ->
            val first = group.first()
            markSkippedList.addView(TextView(requireContext()).apply {
                text = if (group.size == 1) first.title else getString(R.string.reading_mark_range_summary,
                    first.title, group.last().title, group.size)
                textSize = 14f
                setTextColor(ThemeStore.textColorPrimary(context))
                minHeight = 48.dpToPx()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                val value = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
                setOnClickListener {
                    requireContext().selector(title = text,
                        items = listOf(getString(R.string.reading_mark_open_chapter),
                            getString(R.string.reading_mark_skip_remove))) { _, which ->
                        if (which == 0) openPosition(first)
                        else {
                            draft = requireNotNull(draft).copy(skipped = requireNotNull(draft).skipped - group.toSet())
                            renderSkipped()
                        }
                    }
                }
            })
        }
    }

    private fun openPosition(position: ReadingMarkChapter) {
        val navigate: () -> Unit = {
            viewLifecycleOwner.lifecycleScope.launch {
                val target = withContext(IO) {
                    val current = appDb.bookDao.getBook(book.bookUrl)
                        ?.takeIf { it.name == book.name && it.author == book.author }
                        ?: appDb.bookDao.findByName(book.name).filter { it.author == book.author }
                            .maxByOrNull { it.durChapterTime }
                    current?.let {
                        ReadingMarkResolver(appDb.bookChapterDao.getChapterList(it.bookUrl))
                            .resolve(position)?.let { chapter -> it to chapter }
                    }
                }
                if (target == null) {
                    requireContext().toastOnUi(R.string.reading_mark_unavailable)
                } else {
                    val (current, chapter) = target
                    requireContext().startActivityForBook(current) {
                        putExtra("index", chapter.index)
                        putExtra("chapterPos", if (chapter.bookUrl == position.bookUrl &&
                            chapter.url == position.url) position.pos else 0)
                    }
                    dismiss()
                }
            }
        }
        if (snapshotDraft() != original) {
            requireContext().alert(R.string.reading_mark, R.string.reading_mark_save_before_open) {
                positiveButton(R.string.reading_mark_save_and_open) { save(navigate) }
                cancelButton()
            }
        } else navigate()
    }

    private fun snapshotDraft(): BookReadingMark? = draft?.copy(reason =
        if (draft?.status == BookReadingMark.UNMARKED) "" else binding.markReason.text.toString())

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        snapshotDraft()?.let { outState.putString("draft", GSON.toJson(it)) }
        original?.let { outState.putString("original", GSON.toJson(it)) }
        outState.putInt("from", binding.markFrom.selectedItemPosition)
        outState.putInt("to", binding.markTo.selectedItemPosition)
        outState.putBoolean("rangeVisible", binding.markRangeFields.isVisible)
    }

    private fun save(onSaved: (() -> Unit)? = null) {
        val mark = snapshotDraft() ?: return
        if (saving) return
        saving = true
        binding.markSave.isEnabled = false
        isCancelable = false
        binding.markCancel.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = withContext(IO) {
                    val current = appDb.bookDao.getBook(book.bookUrl)
                    check(current == null || (current.name == mark.bookName && current.author == mark.bookAuthor)) {
                        "书籍信息已变更，请重新打开阅读标记"
                    }
                    appDb.bookReadingMarkDao.save(mark)
                }
                draft = saved
                original = saved
                requireContext().toastOnUi(R.string.reading_mark_saved)
                if (onSaved == null) dismiss() else onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                requireContext().toastOnUi(e.localizedMessage ?: getString(R.string.reading_mark_failed))
            } finally {
                saving = false
                isCancelable = true
                if (view != null) {
                    binding.markSave.isEnabled = true
                    binding.markCancel.isEnabled = true
                }
            }
        }
    }

    companion object {
        fun newInstance(book: Book, status: String? = null, rangeStart: Int? = null) =
            ReadingMarkDialog().apply {
                arguments = Bundle().apply {
                    putParcelable("book", book.copy())
                    status?.let { putString("status", it) }
                    rangeStart?.let { putInt("rangeStart", it) }
                }
            }
    }
}
