package io.legado.app.ui.book.tag

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTagStat
import io.legado.app.data.entities.aggregateBookTagStats
import io.legado.app.data.entities.bookTags
import io.legado.app.data.entities.normalizeBookTags
import io.legado.app.data.entities.removeBookTag
import io.legado.app.data.entities.replaceBookTag
import io.legado.app.databinding.ActivityBookTagBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.ItemBookTagBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookTagActivity : BaseActivity<ActivityBookTagBinding>() {

    override val binding by viewBinding(ActivityBookTagBinding::inflate)

    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private val adapter = TagAdapter()
    private var sourceMode = false
    private var userTags = emptyList<BookTagStat>()
    private var sourceTags = emptyList<BookTagStat>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.applyNavigationBarPadding()
        binding.recyclerView.adapter = adapter

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.book_my_tags))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.book_source_tags))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                sourceMode = tab.position == 1
                searchView.setQuery("", false)
                render()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.book_tag_search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?) = render().let { false }
        })

        lifecycleScope.launch {
            appDb.bookDao.flowShelfTagBooks().collect { books ->
                withContext(IO) {
                    userTags = aggregateBookTagStats(books, sourceTags = false)
                    sourceTags = aggregateBookTagStats(books, sourceTags = true)
                }
                render()
            }
        }
    }

    private fun render() {
        val query = searchView.query.toString().trim()
        val items = (if (sourceMode) sourceTags else userTags).filter {
            query.isEmpty() || it.name.contains(query, ignoreCase = true)
        }
        adapter.items = items
        binding.tvEmpty.isVisible = items.isEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun showUserTagMenu(anchor: View, tag: BookTagStat) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.book_tag_rename).setOnMenuItemClickListener {
                renameTag(tag.name)
                true
            }
            menu.add(R.string.book_tag_remove_all).setOnMenuItemClickListener {
                confirmRemoveTag(tag.name)
                true
            }
            show()
        }
    }

    private fun renameTag(oldTag: String) {
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setSingleLine(true)
            editView.setText(oldTag)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(R.string.book_tag_rename) {
            customView { dialogBinding.root }
            okButton {
                val newTag = dialogBinding.editView.text?.toString()?.trim().orEmpty()
                if (newTag.bookTags().size != 1) {
                    toastOnUi(R.string.book_tag_name_invalid)
                } else {
                    updateTag(oldTag, newTag, remove = false)
                }
            }
            cancelButton()
        }
    }

    private fun confirmRemoveTag(tag: String) {
        alert(
            title = getString(R.string.book_tag_remove_all),
            message = getString(R.string.book_tag_remove_all_hint, tag),
        ) {
            positiveButton(R.string.yes) { updateTag(tag, "", remove = true) }
            negativeButton(R.string.no)
        }
    }

    private fun updateTag(oldTag: String, newTag: String, remove: Boolean) {
        lifecycleScope.launch(IO) {
            appDb.runInTransaction {
                val changed = appDb.bookDao.allShelfByOrder.mapNotNull { book ->
                    val customTag = if (remove) {
                        removeBookTag(book.customTag, oldTag)
                    } else {
                        replaceBookTag(book.customTag, oldTag, newTag)
                    }
                    if (customTag == book.customTag) null else book.copy(customTag = customTag)
                }
                if (changed.isNotEmpty()) {
                    appDb.bookDao.updatePreservingCustomCoverUrl(*changed.toTypedArray())
                }
            }
        }
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        var items: List<BookTagStat> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder {
            return TagHolder(
                ItemBookTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            val tag = items[position]
            holder.binding.tvName.text = tag.name
            holder.binding.tvCount.text = getString(R.string.book_tag_book_count, tag.bookCount)
            holder.binding.root.setOnClickListener {
                SearchActivity.startLocal(this@BookTagActivity, tag.name)
            }
            holder.binding.root.setOnLongClickListener {
                if (sourceMode) false else {
                    showUserTagMenu(holder.binding.root, tag)
                    true
                }
            }
        }
    }

    private class TagHolder(val binding: ItemBookTagBinding) :
        RecyclerView.ViewHolder(binding.root)
}
