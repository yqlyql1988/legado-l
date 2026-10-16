package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemTextSelectMenuConfigBinding
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.help.HighlightMenuConfig
import io.legado.app.ui.book.read.HighlightActionPopup
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.TextSelectMenuItem
import io.legado.app.ui.book.read.loadTextSelectMenuConfig
import io.legado.app.ui.book.read.saveTextSelectMenuConfig
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyTint
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

class TextSelectMenuConfigDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { MenuAdapter(requireContext()) }
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val annotationMode get() = arguments?.getBoolean("annotation") == true

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(if (annotationMode) R.string.highlight_menu_config else R.string.text_select_menu_config)
        initView()
        initMenu()
        initData()
    }

    private fun initView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        val itemTouchCallback = ItemTouchCallback(adapter).apply {
            isCanDrag = false
        }
        itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun initMenu() {
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.toolBar.inflateMenu(R.menu.text_select_menu_config)
        binding.toolBar.menu.applyTint(requireContext())
    }

    private fun initData() {
        val config = if (annotationMode) HighlightMenuConfig.load(requireContext())
            else loadTextSelectMenuConfig(requireContext()).normalized()
        adapter.setItems(rowsOf(toEntries(config.bar), toEntries(config.more)))
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        if (item?.itemId == R.id.menu_reset_text_select) {
            val config = if (annotationMode) HighlightMenuConfig.default() else TextSelectMenuConfig.default()
            commitRows(toEntries(config.bar), toEntries(config.more))
        }
        return true
    }

    private fun toEntries(keys: List<String>): MutableList<Row.Entry> {
        return keys.mapNotNull { key ->
            if (annotationMode) HighlightActionPopup.Action.entries.firstOrNull { it.name == key }
                ?.let { Row.Entry(it.name, it.titleRes) }
            else TextSelectMenuItem.byKey[key]?.let { Row.Entry(it.key, it.titleRes) }
        }.toMutableList()
    }

    private fun rowsOf(bar: List<Row.Entry>, more: List<Row.Entry>): List<Row> {
        return buildList(bar.size + more.size + 2) {
            add(Row.Divider(ZONE_BAR))
            addAll(bar)
            add(Row.Divider(ZONE_MORE))
            addAll(more)
        }
    }

    private fun splitEntries(): Pair<MutableList<Row.Entry>, MutableList<Row.Entry>> {
        val items = adapter.getItems()
        val moreIndex = items.indexOfFirst { it is Row.Divider && it.zone == ZONE_MORE }
        if (moreIndex < 0) {
            return items.filterIsInstance<Row.Entry>().toMutableList() to mutableListOf()
        }
        val bar = items.subList(0, moreIndex).filterIsInstance<Row.Entry>().toMutableList()
        val more = items.subList(moreIndex + 1, items.size)
            .filterIsInstance<Row.Entry>()
            .toMutableList()
        return bar to more
    }

    private fun commitRows(bar: List<Row.Entry>, more: List<Row.Entry>) {
        adapter.setItems(rowsOf(bar, more))
        val config = TextSelectMenuConfig(bar.map { it.key }, more.map { it.key })
        if (annotationMode) HighlightMenuConfig.save(requireContext(), config)
        else saveTextSelectMenuConfig(requireContext(), config)
    }

    private fun moveAcrossDivider(position: Int) {
        val entry = adapter.getItem(position) as? Row.Entry ?: return
        val (bar, more) = splitEntries()
        if (bar.removeAll { it.key == entry.key }) {
            more.add(0, entry)
        } else {
            more.removeAll { it.key == entry.key }
            bar.add(entry)
        }
        commitRows(bar, more)
    }

    private sealed interface Row {
        data class Divider(val zone: Int) : Row
        data class Entry(val key: String, val titleRes: Int) : Row
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class MenuAdapter(context: Context) :
        RecyclerAdapter<Row, ItemTextSelectMenuConfigBinding>(context),
        ItemTouchCallback.Callback {

        override fun getViewBinding(parent: ViewGroup): ItemTextSelectMenuConfigBinding {
            return ItemTextSelectMenuConfigBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTextSelectMenuConfigBinding,
            item: Row,
            payloads: MutableList<Any>
        ) {
            when (item) {
                is Row.Divider -> {
                    binding.llEntry.gone()
                    binding.tvSection.visible()
                    binding.tvSection.setText(
                        if (item.zone == ZONE_BAR) {
                            R.string.text_menu_zone_bar
                        } else {
                            R.string.text_menu_zone_more
                        }
                    )
                }

                is Row.Entry -> {
                    binding.tvSection.gone()
                    binding.llEntry.visible()
                    binding.tvTitle.setText(item.titleRes)
                    val moreIndex = getItems().indexOfFirst {
                        it is Row.Divider && it.zone == ZONE_MORE
                    }
                    val inBar = moreIndex < 0 || holder.bindingAdapterPosition < moreIndex
                    binding.ivMove.setImageResource(
                        if (inBar) R.drawable.ic_arrow_drop_down else R.drawable.ic_arrow_drop_up
                    )
                    binding.ivMove.contentDescription = context.getString(
                        if (inBar) R.string.move_to_more else R.string.move_to_floating_bar
                    )
                }
            }
        }

        override fun registerListener(
            holder: ItemViewHolder,
            binding: ItemTextSelectMenuConfigBinding
        ) {
            binding.ivMove.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    moveAcrossDivider(position)
                }
            }
            binding.ivDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                false
            }
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            swapItem(srcPosition, targetPosition)
            return true
        }

        override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            val (bar, more) = splitEntries()
            commitRows(bar, more)
        }
    }

    companion object {
        fun annotations() = TextSelectMenuConfigDialog().apply {
            arguments = Bundle().apply { putBoolean("annotation", true) }
        }
        private const val ZONE_BAR = 0
        private const val ZONE_MORE = 1
    }
}
