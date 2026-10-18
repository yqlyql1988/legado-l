package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HighlightTag
import io.legado.app.databinding.DialogHighlightTagAddBinding
import io.legado.app.databinding.ItemHighlightTagAddBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HighlightTagAddDialog : BaseDialogFragment(R.layout.dialog_highlight_tag_add) {
    interface Callback {
        fun labelLibrary(): List<HighlightTag>
        fun addedLabelIds(): Set<String>
        fun chooseLabel(id: String)
        fun createLabel(name: String)
    }

    private val binding by viewBinding(DialogHighlightTagAddBinding::bind)
    private var results = emptyList<HighlightTag>()
    private val host get() = activity as? Callback
    private val adapter = ResultsAdapter()

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.setBackgroundColor(requireContext().backgroundColor)
        binding.titleBar.toolbar.apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { dismiss() }
        }
        binding.tagResults.adapter = adapter
        binding.tagResults.addItemDecoration(VerticalDivider(requireContext()))
        binding.etTagSearch.doAfterTextChanged { search() }
        binding.btnCreateTag.setOnClickListener {
            val name = binding.etTagSearch.text.toString().trim()
            if (name.isNotEmpty() && host?.labelLibrary()?.none { it.name.equals(name, true) } == true) {
                host?.createLabel(name)
                dismiss()
            }
        }
        search()
    }

    private fun search() {
        val query = binding.etTagSearch.text.toString().trim()
        val library = host?.labelLibrary().orEmpty()
        results = library.filter { query.isEmpty() || it.name.contains(query, true) || it.note.contains(query, true) }
            .sortedWith(compareByDescending<HighlightTag> { it.name.equals(query, true) }
                .thenByDescending { it.common }.thenBy { it.sortOrder }.thenBy { it.id })
        binding.btnCreateTag.isVisible = query.isNotEmpty() && library.none { it.name.equals(query, true) }
        binding.btnCreateTag.text = getString(R.string.highlight_tag_create_named, query)
        adapter.notifyDataSetChanged()
    }

    private class Holder(val row: ItemHighlightTagAddBinding) : RecyclerView.ViewHolder(row.root)
    private inner class ResultsAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = results.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
            Holder(ItemHighlightTagAddBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tag = results[position]
            holder.row.tvName.text = if (tag.common) "★ ${tag.name}" else tag.name
            holder.row.tvNote.text = tag.note
            holder.row.tvNote.isVisible = tag.note.isNotBlank()
            holder.row.tvPreview.highlightStyle = tag.styleObj()
            holder.row.tvStatus.setText(if (tag.id in host?.addedLabelIds().orEmpty())
                R.string.highlight_tag_already_added else R.string.highlight_tag_select_existing)
            holder.row.root.setOnClickListener {
                host?.chooseLabel(tag.id)
                dismiss()
            }
        }
    }
}
