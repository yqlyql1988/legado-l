package io.legado.app.ui.book.tag

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTagFilter
import io.legado.app.data.entities.BookTagOverviewStat
import io.legado.app.data.entities.BookTagSort
import io.legado.app.data.entities.aggregateBookTagOverview
import io.legado.app.data.entities.bookTags
import io.legado.app.data.entities.removeBookTag
import io.legado.app.data.entities.replaceBookTag
import io.legado.app.data.entities.sortBookTagOverview
import io.legado.app.databinding.ActivityBookTagBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.ItemBookTagBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.getToolbarTextColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookTagActivity : BaseActivity<ActivityBookTagBinding>() {

    override val binding by viewBinding(ActivityBookTagBinding::inflate)

    private val searchView: SearchView by lazy {
        binding.root.findViewById(R.id.search_view)
    }
    private val adapter = TagAdapter()
    private var tagStats = emptyList<BookTagOverviewStat>()
    private var sort = BookTagSort.TOTAL
    private var filter = BookTagFilter.ALL
    private var sortItem: MenuItem? = null
    private var sortView: TextView? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sort = BookTagSort.entries.firstOrNull { it.name == getPrefString(SORT_PREFERENCE) }
            ?: BookTagSort.TOTAL
        filter = BookTagFilter.entries.firstOrNull { it.name == getPrefString(FILTER_PREFERENCE) }
            ?: BookTagFilter.ALL
        updateFilterLabel()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.applyNavigationBarPadding()
        binding.recyclerView.adapter = adapter

        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.book_tag_search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                return true
            }
            override fun onQueryTextChange(newText: String?) = render().let { false }
        })
        searchView.isVisible = savedInstanceState?.getBoolean("searchVisible") == true
        searchView.setQuery(savedInstanceState?.getString("query").orEmpty(), false)

        lifecycleScope.launch {
            appDb.bookDao.flowShelfTagBooks().collect { books ->
                val requestedSort = sort
                val stats = withContext(IO) {
                    aggregateBookTagOverview(books, requestedSort)
                }
                tagStats = if (sort == requestedSort) stats else sortBookTagOverview(stats, sort)
                render()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_tag, menu)
        sortItem = menu.findItem(R.id.menu_sort).apply {
            val configuration = resources.configuration
            if (configuration.screenWidthDp < 360 * configuration.fontScale) {
                actionView = null
            }
        }
        sortView = sortItem?.actionView as? TextView
        val toolbarTextColor = getToolbarTextColor(binding.titleBar.usesTransparentForeground)
        sortView?.setTextColor(toolbarTextColor)
        sortView?.compoundDrawableTintList = ColorStateList.valueOf(toolbarTextColor)
        updateSortLabel()
        sortView?.setOnClickListener { showSortMenu(it) }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_sort) {
            showSortMenu(binding.titleBar.toolbar.findViewById<View>(R.id.menu_sort)
                ?: binding.titleBar.toolbar)
            return true
        }
        if (item.itemId == R.id.menu_filter) {
            showFilterMenu(binding.titleBar.toolbar.findViewById<View>(R.id.menu_filter)
                ?: binding.titleBar.toolbar)
            return true
        }
        if (item.itemId == R.id.menu_search) {
            searchView.isVisible = !searchView.isVisible
            if (searchView.isVisible) {
                searchView.requestFocus()
            } else {
                searchView.setQuery("", false)
                searchView.clearFocus()
            }
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("searchVisible", searchView.isVisible)
        outState.putString("query", searchView.query.toString())
        super.onSaveInstanceState(outState)
    }

    private fun updateSortLabel() {
        val description = getString(
            if (sort == BookTagSort.USER) R.string.book_tag_sort_user
            else R.string.book_tag_sort_total
        )
        sortItem?.contentDescription = description
        sortItem?.tooltipText = description
        sortView?.contentDescription = description
        sortView?.tooltipText = description
        sortView?.setText(
            if (sort == BookTagSort.USER) R.string.book_tag_sort_user_short
            else R.string.book_tag_sort_total_short
        )
    }

    private fun showSortMenu(anchor: View) = PopupAction(this).apply {
        setVertical(true)
        setActionItems(BookTagSort.entries.map { option ->
            PopupAction.PopupActionItem(
                title = getString(
                    if (option == BookTagSort.USER) R.string.book_tag_sort_user
                    else R.string.book_tag_sort_total
                ),
                value = option.name,
                checkable = true,
                checked = sort == option,
            )
        })
        onActionClick = { value ->
            dismiss()
            sort = BookTagSort.valueOf(value)
            putPrefString(SORT_PREFERENCE, sort.name)
            tagStats = sortBookTagOverview(tagStats, sort)
            updateSortLabel()
            render()
            binding.recyclerView.scrollToPosition(0)
        }
        showAsDropDown(anchor, 0, 4.dpToPx())
    }

    private fun filterTitle(option: BookTagFilter) = getString(when (option) {
        BookTagFilter.ALL -> R.string.book_tag_filter_all
        BookTagFilter.USER -> R.string.book_my_tags
        BookTagFilter.SOURCE -> R.string.book_source_tags
    })

    private fun updateFilterLabel() {
        binding.titleBar.subtitle = if (filter == BookTagFilter.ALL) null else filterTitle(filter)
    }

    private fun showFilterMenu(anchor: View) = PopupAction(this).apply {
        setVertical(true)
        setActionItems(BookTagFilter.entries.map { option ->
            PopupAction.PopupActionItem(
                title = filterTitle(option),
                value = option.name,
                checkable = true,
                checked = filter == option,
            )
        })
        onActionClick = { value ->
            dismiss()
            filter = BookTagFilter.valueOf(value)
            putPrefString(FILTER_PREFERENCE, filter.name)
            updateFilterLabel()
            render()
            binding.recyclerView.scrollToPosition(0)
        }
        showAsDropDown(anchor, 0, 4.dpToPx())
    }

    private fun render() {
        val query = searchView.query.toString().trim()
        val items = tagStats.filter {
            filter.includes(it) && (query.isEmpty() || it.name.contains(query, ignoreCase = true))
        }
        adapter.items = items
        binding.tvEmpty.isVisible = items.isEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun showUserTagMenu(anchor: View, tag: BookTagOverviewStat) {
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
        var items: List<BookTagOverviewStat> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder {
            return TagHolder(
                ItemBookTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            val tag = items[position]
            holder.binding.tvName.text = tag.name
            holder.binding.tvCount.text = getString(R.string.book_tag_total_count, tag.totalBookCount)
            holder.binding.tvDetails.text = getString(
                R.string.book_tag_source_user_count,
                tag.userBookCount,
                tag.sourceBookCount,
            )
            holder.binding.tagMore.isInvisible = tag.userBookCount == 0
            holder.binding.tagMore.contentDescription = getString(R.string.book_tag_actions, tag.name)
            holder.binding.tagMore.setOnClickListener { showUserTagMenu(it, tag) }
            holder.binding.root.setOnClickListener {
                SearchActivity.startLocal(this@BookTagActivity, tag.name)
            }
            holder.binding.root.setOnLongClickListener {
                if (tag.userBookCount > 0) {
                    showUserTagMenu(holder.binding.root, tag)
                    true
                } else false
            }
        }
    }

    private class TagHolder(val binding: ItemBookTagBinding) :
        RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val SORT_PREFERENCE = "bookTagOverviewSort"
        const val FILTER_PREFERENCE = "bookTagOverviewFilter"
    }
}
