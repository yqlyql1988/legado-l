package io.legado.app.ui.highlight

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
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
import io.legado.app.databinding.ActivityHighlightTagBinding
import io.legado.app.databinding.ItemHighlightTagBinding
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTags
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HighlightTagManageActivity : BaseActivity<ActivityHighlightTagBinding>(),
    HighlightTagEditDialog.Callback {
    override val binding by viewBinding(ActivityHighlightTagBinding::inflate)
    private val tags = arrayListOf<HighlightTag>()
    private var original = emptyList<HighlightTag>()
    private val previewIds = hashSetOf<String>()
    private val adapter = TagAdapter()
    private var saving = false
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun onMove(recycler: RecyclerView, source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder): Boolean {
            move(source.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    })

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        original = savedInstanceState?.getString("original")?.let {
            GSON.fromJsonArray<HighlightTag>(it).getOrNull()
        } ?: appDb.highlightTagDao.all()
        tags.addAll(savedInstanceState?.getString("tags")?.let {
            GSON.fromJsonArray<HighlightTag>(it).getOrNull()
        } ?: original)
        previewIds.addAll(savedInstanceState?.getStringArrayList("preview")
            ?: ArrayList(tags.takeLast(2).map { it.id }))
        binding.recyclerView.adapter = adapter
        touchHelper.attachToRecyclerView(binding.recyclerView)
        binding.footer.applyNavigationBarPadding()
        binding.tvAdd.setOnClickListener {
            if (!saving) showDialogFragment(HighlightTagEditDialog.create(
                HighlightTag(style = GSON.toJson(HighlightStyle(
                    underline = HighlightStyle.Underline(color = 0xFF43864C.toInt(), width = 2f)
                )), sortOrder = tags.size), true))
        }
        binding.btnSave.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this) {
            if (saving) return@addCallback
            if (orderedTags() == original) finish()
            else AlertDialog.Builder(this@HighlightTagManageActivity)
                .setMessage(R.string.highlight_tag_unsaved)
                .setPositiveButton(R.string.action_save) { _, _ -> save() }
                .setNegativeButton(R.string.highlight_tag_discard) { _, _ -> finish() }
                .setNeutralButton(R.string.cancel, null).show()
        }
        renderPreview()
    }

    private fun orderedTags() = tags.mapIndexed { index, tag -> tag.copy(sortOrder = index) }

    private fun move(from: Int, to: Int) {
        if (saving || from !in tags.indices || to !in tags.indices || from == to) return
        val moving = tags.removeAt(from)
        tags.add(to, moving)
        adapter.notifyItemMoved(from, to)
        renderPreview()
    }

    override fun saveTag(tag: HighlightTag): Boolean {
        if (saving || tag.name.isBlank() || tags.any { it.id != tag.id && it.name == tag.name }) return false
        val index = tags.indexOfFirst { it.id == tag.id }
        if (index < 0) {
            tags.add(tag)
            adapter.notifyItemInserted(tags.lastIndex)
        } else {
            tags[index] = tag
            adapter.notifyItemChanged(index)
        }
        renderPreview()
        return true
    }

    override fun deleteTag(id: String) {
        if (saving) return
        val index = tags.indexOfFirst { it.id == id }
        if (index < 0) return
        tags.removeAt(index)
        previewIds.remove(id)
        adapter.notifyItemRemoved(index)
        renderPreview()
    }

    private fun renderPreview() {
        binding.tvEmpty.isVisible = tags.isEmpty()
        val scroll = binding.previewTags.parent as android.widget.HorizontalScrollView
        val oldScroll = scroll.scrollX
        binding.previewTags.removeAllViews()
        tags.forEach { tag ->
            binding.previewTags.addView(highlightTagChip(tag, tag.id in previewIds) {
                if (!previewIds.add(tag.id)) previewIds.remove(tag.id)
                renderPreview()
            })
        }
        scroll.post { scroll.scrollTo(oldScroll, 0) }
        val active = HighlightTags.primary(orderedTags(), previewIds)
        binding.tvPreview.highlightStyle = active?.styleObj() ?: HighlightStyle()
        binding.tvPrimary.text = active?.let { getString(R.string.highlight_tag_primary, it.name) }
            ?: getString(R.string.highlight_tag_preview_empty)
    }

    private fun save() {
        if (saving) return
        val updated = orderedTags()
        saving = true
        binding.btnSave.isEnabled = false
        binding.tvAdd.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(IO) { appDb.highlightTagDao.saveTags(updated) }
                ReadBook.upHighlightTags()
                setResult(RESULT_OK)
                finish()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                toastOnUi(R.string.highlight_tag_save_failed)
            } finally {
                saving = false
                binding.btnSave.isEnabled = true
                binding.tvAdd.isEnabled = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("original", GSON.toJson(original))
        outState.putString("tags", GSON.toJson(orderedTags()))
        outState.putStringArrayList("preview", ArrayList(previewIds))
        super.onSaveInstanceState(outState)
    }

    private inner class TagHolder(val row: ItemHighlightTagBinding) : RecyclerView.ViewHolder(row.root)

    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        override fun getItemCount() = tags.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TagHolder(ItemHighlightTagBinding.inflate(layoutInflater, parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            val tag = tags[position]
            holder.row.tvName.text = tag.name
            val underline = tag.styleObj().underline ?: HighlightStyle.Underline()
            holder.row.tvStyle.text = getString(R.string.highlight_tag_style_description,
                getString(underline.kind.labelRes()), underline.width, underline.distance)
            holder.row.tvStyle.setTextColor(underline.color.takeIf { it != 0 }
                ?: holder.row.tvName.currentTextColor)
            holder.row.editLabel.setOnClickListener {
                if (!saving) showDialogFragment(HighlightTagEditDialog.create(tag, false))
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
