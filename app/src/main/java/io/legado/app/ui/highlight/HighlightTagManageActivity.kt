package io.legado.app.ui.highlight

import android.annotation.SuppressLint
import android.os.Bundle
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.databinding.ActivityHighlightTagBinding
import io.legado.app.databinding.ItemHighlightTagBinding
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTags
import io.legado.app.help.BookmarkTags
import io.legado.app.data.entities.BookmarkTagOrder
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.GSON
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HighlightTagManageActivity : BaseActivity<ActivityHighlightTagBinding>(),
    HighlightTagEditDialog.Callback, HighlightTagAddDialog.Callback {
    override val binding by viewBinding(ActivityHighlightTagBinding::inflate)
    private val tags = arrayListOf<HighlightTag>()
    private var original = emptyList<HighlightTag>()
    private var originalOrder = emptyList<HighlightTagOrder>()
    private var bookOrder = emptyList<HighlightTagOrder>()
    private val replacements = linkedMapOf<String, String>()
    private val bookUrl by lazy { intent.getStringExtra("bookUrl").orEmpty() }
    private val bookmarkMode by lazy { intent.getBooleanExtra("bookmarkMode", false) }
    private var libraryMode = false
    private val adapter = TagAdapter()
    private var saving = false
    private var visibleTags = emptyList<HighlightTag>()
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(recycler: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            move(source.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    })

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        original = savedInstanceState?.getString("original")?.let { GSON.fromJsonArray<HighlightTag>(it).getOrNull() }
            ?: appDb.highlightTagDao.all().map { if (bookmarkMode) it.forBookmark() else it }
        tags.addAll(savedInstanceState?.getString("tags")?.let { GSON.fromJsonArray<HighlightTag>(it).getOrNull() } ?: original)
        originalOrder = savedInstanceState?.getString("originalOrder")?.let {
            GSON.fromJsonArray<HighlightTagOrder>(it).getOrNull()
        } ?: if (bookmarkMode) appDb.bookmarkTagDao.ordersForBook(bookUrl).map { it.asHighlightOrder() }
            else appDb.highlightTagDao.ordersForBook(bookUrl)
        bookOrder = savedInstanceState?.getString("order")?.let {
            GSON.fromJsonArray<HighlightTagOrder>(it).getOrNull()
        } ?: originalOrder
        savedInstanceState?.getString("replacements")?.let {
            GSON.fromJsonObject<Map<String, String>>(it).getOrNull()?.let(replacements::putAll)
        }
        libraryMode = (savedInstanceState?.getBoolean("library") ?: false) || bookUrl.isEmpty()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        touchHelper.attachToRecyclerView(binding.recyclerView)
        binding.recyclerView.applyNavigationBarPadding()
        binding.tvAdd.setOnClickListener { if (!saving) showDialogFragment(HighlightTagAddDialog()) }
        binding.etLibrarySearch.doAfterTextChanged { if (libraryMode) render() }
        onBackPressedDispatcher.addCallback(this) {
            if (saving) return@addCallback
            if (libraryMode && bookUrl.isNotEmpty()) {
                libraryMode = false
                render()
                return@addCallback
            }
            if (tags == original && bookOrder == originalOrder && replacements.isEmpty()) finish()
            else AlertDialog.Builder(this@HighlightTagManageActivity).setMessage(R.string.highlight_tag_unsaved)
                .setPositiveButton(R.string.action_save) { _, _ -> save() }
                .setNegativeButton(R.string.highlight_tag_discard) { _, _ -> finish() }
                .setNeutralButton(R.string.cancel, null).show()
        }
        render()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        // AppCompat finishes installing its home indicator after the menu callback.
        binding.titleBar.post {
            binding.titleBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            binding.titleBar.applyForegroundColor()
        }
        menu.add(Menu.NONE, R.id.btn_save, Menu.NONE, R.string.action_save).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { save(); true }
        }
        if (!bookmarkMode) menu.add(R.string.highlight_tag_preview).setOnMenuItemClickListener {
            showPreview()
            true
        }
        if (bookUrl.isNotEmpty()) menu.add(R.string.highlight_tag_library).setOnMenuItemClickListener {
            libraryMode = true
            render()
            true
        }
        return true
    }

    private fun render() {
        binding.titleBar.title = getString(if (bookmarkMode) R.string.bookmark_tags else R.string.highlight_tag_manage)
        val query = binding.etLibrarySearch.text.toString().trim()
        visibleTags = if (!libraryMode) bookChoices() else tags
            .filter { query.isEmpty() || it.name.contains(query, true) || it.note.contains(query, true) }
            .sortedWith(compareByDescending<HighlightTag> { it.common }.thenBy { it.sortOrder }.thenBy { it.id })
        binding.tvListTitle.setText(if (libraryMode) R.string.highlight_tag_library else R.string.highlight_tag_book_list)
        binding.etLibrarySearch.isVisible = libraryMode
        binding.tvHint.setText(if (bookmarkMode) R.string.note_tag_bookmark_hint
            else if (libraryMode) R.string.highlight_tag_library_hint else R.string.highlight_tag_list_hint)
        binding.tvEmpty.isVisible = visibleTags.isEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun move(from: Int, to: Int) {
        if (saving || libraryMode && (bookUrl.isNotEmpty() || binding.etLibrarySearch.text?.isNotBlank() == true)
            || from !in visibleTags.indices || to !in visibleTags.indices || from == to) return
        visibleTags = visibleTags.toMutableList().apply { add(to, removeAt(from)) }
        if (bookUrl.isEmpty()) {
            visibleTags.forEachIndexed { index, tag ->
                val at = tags.indexOfFirst { it.id == tag.id }
                tags[at] = tag.copy(sortOrder = index)
            }
            adapter.notifyItemMoved(from, to)
            return
        }
        val positions = visibleTags.mapIndexed { index, tag -> HighlightTagOrder(bookUrl, tag.id, index) }
        bookOrder = positions + bookOrder.filter { entry -> positions.none { it.tagId == entry.tagId } }
        adapter.notifyItemMoved(from, to)
    }

    private fun showPreview() {
        val previewTags = visibleTags.mapIndexed { index, tag -> tag.copy(sortOrder = index) }
        val selected = previewTags.takeLast(2).mapTo(hashSetOf()) { it.id }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 8.dpToPx())
        }
        val rail = LinearLayout(this)
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(rail)
        })
        val sample = HighlightTagPreview(this).apply {
            setText(R.string.highlight_tag_preview_text)
            setPadding(0, 16.dpToPx(), 0, 36.dpToPx())
            textSize = 17f
        }
        val primary = TextView(this).apply {
            textSize = 12f
            setTextColor(getCompatColor(R.color.secondaryText))
        }
        panel.addView(sample)
        panel.addView(primary)
        fun refresh() {
            rail.removeAllViews()
            previewTags.forEach { tag ->
                rail.addView(highlightTagChip(tag, tag.id in selected) {
                    if (!selected.add(tag.id)) selected.remove(tag.id)
                    refresh()
                })
            }
            val tag = HighlightTags.primary(previewTags, selected)
            sample.highlightStyle = tag?.styleObj() ?: HighlightStyle()
            primary.text = tag?.let { getString(R.string.highlight_tag_primary, it.name) }
                ?: getString(R.string.highlight_tag_preview_empty)
        }
        refresh()
        AlertDialog.Builder(this).setTitle(R.string.highlight_tag_preview).setView(panel)
            .setPositiveButton(R.string.ok, null).show()
    }

    override fun saveTag(tag: HighlightTag): Boolean {
        val old = tags.firstOrNull { it.id == tag.id }
        if (saving || tag.name.isBlank() || (old == null || !old.name.equals(tag.name, true)) &&
            tags.any { it.id != tag.id && it.name.equals(tag.name, true) }) return false
        val index = tags.indexOfFirst { it.id == tag.id }
        if (index < 0) tags.add(tag) else tags[index] = tag
        if (index < 0 && bookUrl.isNotEmpty()) putEntry(tag.id, false)
        render()
        return true
    }

    private fun bookChoices() = HighlightTags.choices(
        HighlightTags.forBook(tags, bookUrl, bookOrder, includeOwned = !bookmarkMode), bookUrl,
        bookOrder.filterNot { it.hidden }.sortedBy { it.sortOrder }.map { it.tagId })

    override fun labelLibrary(): List<HighlightTag> = tags.toList()
    override fun addedLabelIds(): Set<String> = bookChoices().mapTo(hashSetOf()) { it.id }

    override fun createLabel(name: String) {
        if (saving) return
        showDialogFragment(HighlightTagEditDialog.create(HighlightTag(
            name = name, bookUrl = if (bookmarkMode) "" else bookUrl, common = bookUrl.isEmpty(),
            style = GSON.toJson(HighlightStyle(underline = HighlightStyle.Underline(color = 0xFF43864C.toInt(), width = 2f))),
            sortOrder = (tags.maxOfOrNull { it.sortOrder } ?: -1) + 1
        ), true, bookUrl, bookmarkMode = bookmarkMode))
    }

    private fun putEntry(id: String, hidden: Boolean) {
        val previous = bookOrder.associateBy { it.tagId }
        bookOrder = HighlightTags.forBook(tags, bookUrl, bookOrder, includeHidden = true, includeOwned = !bookmarkMode)
            .mapIndexed { index, tag ->
                HighlightTagOrder(bookUrl, tag.id, index, previous[tag.id]?.hidden == true)
            }
        val position = bookOrder.firstOrNull { it.tagId == id }?.sortOrder
            ?: ((bookOrder.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        bookOrder = bookOrder.filterNot { it.tagId == id } + HighlightTagOrder(bookUrl, id, position, hidden)
    }

    override fun chooseLabel(id: String) {
        if (saving) return
        val tag = tags.firstOrNull { it.id == id } ?: return
        if (bookUrl.isEmpty()) {
            showDialogFragment(HighlightTagEditDialog.create(tag, false, bookmarkMode = bookmarkMode))
            return
        }
        val other = bookChoices().firstOrNull { it.id != id && it.name.equals(tag.name, true) }
        fun add() {
            if (id !in addedLabelIds()) putEntry(id, false)
            libraryMode = false
            render()
            binding.recyclerView.post {
                val position = visibleTags.indexOfFirst { it.id == id }
                if (position >= 0) binding.recyclerView.smoothScrollToPosition(position)
            }
            toastOnUi(getString(R.string.highlight_tag_located, tag.name))
        }
        if (other == null) add()
        else AlertDialog.Builder(this)
            .setTitle(R.string.highlight_tag_use_existing)
            .setMessage(getString(R.string.highlight_tag_use_existing_hint, tag.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.highlight_tag_use_existing) { _, _ ->
                replacements.replaceAll { _, target -> if (target == other.id) id else target }
                replacements[other.id] = id
                replacements.remove(id)
                putEntry(other.id, true)
                add()
            }.show()
    }

    override fun deleteTag(id: String) {
        if (saving || bookUrl.isEmpty()) return
        putEntry(id, true)
        render()
    }

    private fun deleteFromLibrary(tag: HighlightTag) {
        val usage = appDb.highlightTagDao.usage(tag.id)
        AlertDialog.Builder(this).setTitle(R.string.highlight_tag_delete_library)
            .setMessage(getString(R.string.note_tag_delete_hint, tag.name, usage.highlights,
                appDb.bookmarkTagDao.usage(tag.id)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                tags.removeAll { it.id == tag.id }
                bookOrder = bookOrder.filterNot { it.tagId == tag.id }
                render()
            }.show()
    }

    private fun toggleCommon(tag: HighlightTag) {
        if (saving) return
        if (tag.common && bookUrl.isNotEmpty()) {
            // Keep this book's current choices after cancelling the global default.
            val choices = bookChoices()
            choices.forEach { putEntry(it.id, false) }
        }
        val index = tags.indexOfFirst { it.id == tag.id }
        if (index >= 0) tags[index] = tags[index].copy(common = !tag.common)
        render()
    }

    private fun save() {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                withContext(IO) {
                    appDb.runInTransaction {
                        // Apply confirmed switches before deleting definitions so removing
                        // the old library entry cannot cascade away the chosen association.
                        replacements.forEach { (from, to) ->
                            if (bookmarkMode) appDb.bookmarkTagDao.useLibraryLabel(bookUrl, from, to)
                            else appDb.highlightTagDao.useLibraryLabel(bookUrl, from, to)
                        }
                        if (bookmarkMode) BookmarkTags.saveDefinitions(tags.toList(), bookUrl)
                        else {
                            val current = appDb.highlightTagDao.all().associateBy { it.id }
                            appDb.highlightTagDao.saveDefinitions(tags.map { tag ->
                                tag.copy(bookmarkCommon = current[tag.id]?.bookmarkCommon ?: false,
                                    bookmarkSortOrder = current[tag.id]?.bookmarkSortOrder ?: 0)
                            }, bookUrl)
                        }
                        if (bookUrl.isNotEmpty() && bookOrder != originalOrder) {
                            if (bookmarkMode) appDb.bookmarkTagDao.saveBookLabels(bookUrl, bookOrder.map {
                                BookmarkTagOrder(it.bookUrl, it.tagId, it.sortOrder, it.hidden)
                            }) else appDb.highlightTagDao.saveBookLabels(bookUrl, bookOrder)
                        }
                    }
                }
                ReadBook.upHighlightTags()
                setResult(RESULT_OK)
                finish()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { toastOnUi(R.string.highlight_tag_save_failed) }
            finally { saving = false }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("original", GSON.toJson(original))
        outState.putString("tags", GSON.toJson(tags))
        outState.putString("order", GSON.toJson(bookOrder))
        outState.putString("originalOrder", GSON.toJson(originalOrder))
        outState.putString("replacements", GSON.toJson(replacements))
        outState.putBoolean("library", libraryMode)
        super.onSaveInstanceState(outState)
    }

    private inner class TagHolder(val row: ItemHighlightTagBinding) : RecyclerView.ViewHolder(row.root)
    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        override fun getItemCount() = visibleTags.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TagHolder(ItemHighlightTagBinding.inflate(layoutInflater, parent, false))
        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            val tag = visibleTags[position]
            holder.row.tvName.text = tag.name
            holder.row.tvPreview.highlightStyle = tag.styleObj()
            holder.row.tvPreview.isVisible = !bookmarkMode
            holder.row.tvNote.text = tag.note
            holder.row.tvNote.isVisible = tag.note.isNotBlank()
            holder.row.btnCommon.setImageResource(if (tag.common) R.drawable.ic_star else R.drawable.ic_star_border)
            holder.row.btnCommon.imageTintList = ColorStateList.valueOf(
                if (tag.common) accentColor else getCompatColor(R.color.secondaryText))
            holder.row.btnCommon.isSelected = tag.common
            holder.row.btnCommon.contentDescription = getString(if (tag.common)
                R.string.highlight_tag_unmark_common else R.string.highlight_tag_mark_common, tag.name)
            holder.row.btnCommon.setOnClickListener { toggleCommon(tags.first { it.id == tag.id }) }
            holder.row.btnMore.isVisible = libraryMode
            holder.row.btnMore.imageTintList = ColorStateList.valueOf(getCompatColor(R.color.secondaryText))
            holder.row.btnMore.setOnClickListener { view ->
                if (!saving) PopupMenu(this@HighlightTagManageActivity, view).apply {
                    menu.add(R.string.highlight_tag_delete_library).setOnMenuItemClickListener {
                        deleteFromLibrary(tag)
                        true
                    }
                    show()
                }
            }
            holder.row.editLabel.setOnClickListener {
                if (!saving) showDialogFragment(HighlightTagEditDialog.create(
                    tags.first { it.id == tag.id }, false, bookUrl, !libraryMode && bookUrl.isNotEmpty(), bookmarkMode))
            }
            holder.row.dragHandle.isVisible = !libraryMode || bookUrl.isEmpty()
            holder.row.dragHandle.contentDescription = getString(R.string.highlight_tag_drag, tag.name)
            holder.row.dragHandle.setOnTouchListener { _, event ->
                if (!saving && event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
            holder.row.dragHandle.setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    val from = holder.bindingAdapterPosition
                    move(from, from + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    true
                } else false
            }
        }
    }
}
