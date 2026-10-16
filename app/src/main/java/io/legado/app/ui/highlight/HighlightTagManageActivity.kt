package io.legado.app.ui.highlight

import android.annotation.SuppressLint
import android.os.Bundle
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
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.GSON
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HighlightTagManageActivity : BaseActivity<ActivityHighlightTagBinding>(), HighlightTagEditDialog.Callback {
    override val binding by viewBinding(ActivityHighlightTagBinding::inflate)
    private val tags = arrayListOf<HighlightTag>()
    private var original = emptyList<HighlightTag>()
    private var originalOrder = emptyList<String>()
    private var bookOrder = emptyList<String>()
    private val bookUrl by lazy { intent.getStringExtra("bookUrl").orEmpty() }
    private var bookTab = true
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
        binding.titleBar.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.titleBar.menu.add(Menu.NONE, R.id.btn_save, Menu.NONE, R.string.action_save).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { save(); true }
        }
        binding.titleBar.menu.add(R.string.highlight_tag_preview).setOnMenuItemClickListener {
            showPreview()
            true
        }
        original = savedInstanceState?.getString("original")?.let { GSON.fromJsonArray<HighlightTag>(it).getOrNull() }
            ?: appDb.highlightTagDao.all()
        tags.addAll(savedInstanceState?.getString("tags")?.let { GSON.fromJsonArray<HighlightTag>(it).getOrNull() } ?: original)
        originalOrder = savedInstanceState?.getStringArrayList("originalOrder")
            ?: appDb.highlightTagDao.ordersForBook(bookUrl).map { it.tagId }
        bookOrder = savedInstanceState?.getStringArrayList("order") ?: originalOrder
        bookTab = (savedInstanceState?.getBoolean("bookTab") ?: true) && bookUrl.isNotEmpty()
        binding.tabBook.isVisible = bookUrl.isNotEmpty()
        binding.tabBook.setOnClickListener { bookTab = true; render() }
        binding.tabGlobal.setOnClickListener { bookTab = false; render() }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        touchHelper.attachToRecyclerView(binding.recyclerView)
        binding.recyclerView.applyNavigationBarPadding()
        binding.tvAdd.setOnClickListener {
            if (!saving) showDialogFragment(HighlightTagEditDialog.create(HighlightTag(
                style = GSON.toJson(HighlightStyle(underline = HighlightStyle.Underline(color = 0xFF43864C.toInt(), width = 2f))),
                sortOrder = (tags.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                bookUrl = if (bookTab) bookUrl else ""
            ), true, bookUrl))
        }
        onBackPressedDispatcher.addCallback(this) {
            if (saving) return@addCallback
            if (tags == original && bookOrder == originalOrder) finish()
            else AlertDialog.Builder(this@HighlightTagManageActivity).setMessage(R.string.highlight_tag_unsaved)
                .setPositiveButton(R.string.action_save) { _, _ -> save() }
                .setNegativeButton(R.string.highlight_tag_discard) { _, _ -> finish() }
                .setNeutralButton(R.string.cancel, null).show()
        }
        render()
    }

    private fun render() {
        visibleTags = if (bookTab) HighlightTags.forBook(tags, bookUrl,
            bookOrder.mapIndexed { index, id -> HighlightTagOrder(bookUrl, id, index) })
            else tags.filter { it.bookUrl.isEmpty() }.sortedWith(compareBy(HighlightTag::sortOrder, HighlightTag::id))
        binding.tabBook.setTextColor(if (bookTab) accentColor else getCompatColor(R.color.secondaryText))
        binding.tabGlobal.setTextColor(if (!bookTab) accentColor else getCompatColor(R.color.secondaryText))
        binding.tabBook.isSelected = bookTab
        binding.tabGlobal.isSelected = !bookTab
        binding.tvHint.setText(if (bookTab) R.string.highlight_tag_book_hint else R.string.highlight_tag_global_hint)
        binding.tvEmpty.isVisible = visibleTags.isEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun move(from: Int, to: Int) {
        if (saving || from !in visibleTags.indices || to !in visibleTags.indices || from == to) return
        visibleTags = visibleTags.toMutableList().apply { add(to, removeAt(from)) }
        if (bookTab) bookOrder = visibleTags.map { it.id }
        else {
            val positions = visibleTags.mapIndexed { index, tag -> tag.id to index }.toMap()
            tags.replaceAll { tag -> positions[tag.id]?.let { tag.copy(sortOrder = it) } ?: tag }
        }
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
        if (saving || tag.name.isBlank() || tags.any { it.id != tag.id && it.name == tag.name &&
            (it.bookUrl == tag.bookUrl || it.bookUrl.isEmpty() || tag.bookUrl.isEmpty()) }) return false
        val index = tags.indexOfFirst { it.id == tag.id }
        if (index < 0) tags.add(tag) else tags[index] = tag
        render()
        return true
    }

    override fun deleteTag(id: String) {
        if (saving) return
        tags.removeAll { it.id == id }
        bookOrder = bookOrder.filter { it != id }
        render()
    }

    private fun save() {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                withContext(IO) {
                    appDb.runInTransaction {
                        appDb.highlightTagDao.replaceDefinitions(tags.toList())
                        if (bookUrl.isNotEmpty() && bookOrder != originalOrder)
                            appDb.highlightTagDao.saveOrder(bookUrl, bookOrder)
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
        outState.putStringArrayList("order", ArrayList(bookOrder))
        outState.putStringArrayList("originalOrder", ArrayList(originalOrder))
        outState.putBoolean("bookTab", bookTab)
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
            holder.row.tvName.highlightStyle = tag.styleObj()
            holder.row.tvStyle.text = getString(if (tag.bookUrl.isEmpty()) R.string.highlight_tag_global else R.string.highlight_tag_book)
            holder.row.editLabel.setOnClickListener {
                if (!saving) showDialogFragment(HighlightTagEditDialog.create(
                    tags.first { it.id == tag.id }, false, bookUrl))
            }
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
