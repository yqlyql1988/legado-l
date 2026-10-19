package io.legado.app.ui.highlight

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookmarkTagOrder
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.lib.theme.accentColor
import com.google.android.material.tabs.TabLayout
import io.legado.app.ui.book.bookmark.BookNotesRepository
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One selector for both scenes; switching lists never drops the pending selection. */
class NoteTagPickerDialog : DialogFragment(), HighlightTagEditDialog.Callback {
    private val selected = linkedSetOf<String>()
    private val bookmarkMode get() = requireArguments().getBoolean("bookmarkMode")
    private val bookUrl get() = requireArguments().getString("bookUrl").orEmpty()
    private var library = false
    private var query = ""
    private lateinit var rows: LinearLayout
    private var saving = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        selected.addAll(savedInstanceState?.getStringArrayList("ids")
            ?: requireArguments().getStringArrayList("ids").orEmpty())
        library = savedInstanceState?.getBoolean("library") ?: false
        query = savedInstanceState?.getString("query").orEmpty()
        val context = requireContext()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
        }
        panel.addView(TabLayout(context).apply {
            setSelectedTabIndicatorColor(context.accentColor)
            setTabTextColors(context.getCompatColor(R.color.secondaryText), context.accentColor)
            isTabIndicatorFullWidth = false
            addTab(newTab().setText(R.string.note_tag_book_list))
            addTab(newTab().setText(R.string.highlight_tag_library))
            getTabAt(if (library) 1 else 0)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { library = tab.position == 1; render() }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        })
        fun action(label: Int, click: () -> Unit) = AccentTextView(context, null).apply {
            setText(label)
            gravity = android.view.Gravity.CENTER
            minHeight = 48.dpToPx()
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
            setOnClickListener { if (!saving) click() }
        }
        rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(context).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(360.dpToPx(), resources.displayMetrics.heightPixels * 2 / 5)))
        val actions = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        actions.addView(ThemeEditText(context).apply {
            setHint(R.string.note_tag_search)
            isSingleLine = true
            setText(query)
            doAfterTextChanged { query = it.toString(); render() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(action(R.string.note_tag_new) {
            HighlightTagEditDialog.create(HighlightTag(bookUrl = if (bookmarkMode) "" else bookUrl, common = false),
                true, bookUrl, bookmarkMode = bookmarkMode).show(childFragmentManager, "new-tag")
        })
        panel.addView(actions)
        render()
        return AlertDialog.Builder(context).setTitle(R.string.book_notes_tags).setView(panel)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ok, null).create()
    }

    private fun render() {
        if (!::rows.isInitialized) return
        val definitions = appDb.highlightTagDao.all()
        val available = if (bookmarkMode) appDb.bookmarkTagDao.forBook(bookUrl)
            else appDb.highlightTagDao.forBook(bookUrl)
        val choices = if (library) definitions.map { if (bookmarkMode) it.forBookmark() else it }
            .sortedWith(compareByDescending<HighlightTag> { it.common }.thenBy { it.sortOrder }.thenBy { it.id })
            else (available + definitions.filter { it.id in selected }).distinctBy { it.id }
        rows.removeAllViews()
        choices.filter { query.isBlank() || it.name.contains(query.trim(), true) ||
            it.note.contains(query.trim(), true) }.forEach { tag ->
            rows.addView(CheckBox(requireContext()).apply {
                text = tag.name
                minHeight = 48.dpToPx()
                isChecked = tag.id in selected
                isEnabled = !saving
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(tag.id) else selected.remove(tag.id)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (rows.childCount == 0) rows.addView(TextView(requireContext()).apply {
            setText(R.string.note_tag_empty)
            setPadding(8.dpToPx(), 16.dpToPx(), 8.dpToPx(), 16.dpToPx())
        })
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { save() }
    }

    private fun save() {
        if (saving) return
        saving = true
        val requested = selected.toList()
        render()
        lifecycleScope.launch {
            try {
                val savedIds = withContext(IO) {
                    appDb.runInTransaction(java.util.concurrent.Callable {
                        val validIds = appDb.highlightTagDao.all().mapTo(hashSetOf()) { it.id }
                        val chosen = requested.filter { it in validIds }
                        // Choosing a library label adds it to this scene's book list.
                        val available = (if (bookmarkMode) appDb.bookmarkTagDao.forBook(bookUrl)
                            else appDb.highlightTagDao.forBook(bookUrl)).mapTo(hashSetOf()) { it.id }
                        if (bookUrl.isNotBlank()) {
                            if (bookmarkMode) {
                                val orders = appDb.bookmarkTagDao.ordersForBook(bookUrl)
                                val start = (orders.maxOfOrNull { it.sortOrder } ?: -1) + 1
                                appDb.bookmarkTagDao.upsertOrders(chosen.filter { it !in available }
                                    .mapIndexed { index, id -> BookmarkTagOrder(bookUrl, id, start + index) })
                            } else {
                                val orders = appDb.highlightTagDao.ordersForBook(bookUrl)
                                val start = (orders.maxOfOrNull { it.sortOrder } ?: -1) + 1
                                appDb.highlightTagDao.upsertOrders(chosen.filter { it !in available }
                                    .mapIndexed { index, id -> HighlightTagOrder(bookUrl, id, start + index) })
                            }
                        }
                        if (requireArguments().getBoolean("direct")) {
                            val time = requireArguments().getLong("time")
                            if (bookmarkMode) appDb.bookmarkTagDao.saveLinks(time, bookUrl, chosen)
                            else if (appDb.highlightTagDao.highlightExists(time)) {
                                appDb.highlightTagDao.deleteLinksForHighlights(listOf(time))
                                appDb.highlightTagDao.upsertLinks(chosen.filter {
                                    appDb.highlightTagDao.canAttach(time, it)
                                }.map { HighlightTagLink(time, it) })
                            }
                        }
                        chosen
                    })
                }
                if (!bookmarkMode) BookNotesRepository.refreshReader(setOf(bookUrl))
                parentFragmentManager.setFragmentResult(requireArguments().getString("resultKey").orEmpty(),
                    bundleOf("ids" to ArrayList(savedIds)))
                dismiss()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { requireContext().toastOnUi(R.string.highlight_tag_save_failed) }
            finally { saving = false; if (isAdded) render() }
        }
    }

    override fun saveTag(tag: HighlightTag): Boolean = try {
        require(appDb.highlightTagDao.all().none { it.id != tag.id && it.name.equals(tag.name, true) })
        appDb.runInTransaction {
            appDb.highlightTagDao.saveTag(tag.copy(common = false, bookmarkCommon = false))
            if (bookUrl.isNotBlank()) {
                if (bookmarkMode) appDb.bookmarkTagDao.upsertOrders(listOf(BookmarkTagOrder(bookUrl,
                    tag.id, (appDb.bookmarkTagDao.ordersForBook(bookUrl).maxOfOrNull { it.sortOrder } ?: -1) + 1)))
                else appDb.highlightTagDao.upsertOrders(listOf(HighlightTagOrder(bookUrl,
                    tag.id, (appDb.highlightTagDao.ordersForBook(bookUrl).maxOfOrNull { it.sortOrder } ?: -1) + 1)))
            }
        }
        selected.add(tag.id)
        render()
        true
    } catch (_: IllegalArgumentException) { false }

    override fun deleteTag(id: String) = Unit

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("ids", ArrayList(selected))
        outState.putBoolean("library", library)
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    companion object {
        fun create(bookUrl: String, bookmarkMode: Boolean, ids: Collection<String>,
                   time: Long = 0, direct: Boolean = true, resultKey: String = "note-tags") =
            NoteTagPickerDialog().apply {
                arguments = bundleOf("bookUrl" to bookUrl, "bookmarkMode" to bookmarkMode,
                    "ids" to ArrayList(ids), "time" to time, "direct" to direct, "resultKey" to resultKey)
            }
    }
}
