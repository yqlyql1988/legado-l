package io.legado.app.ui.about

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.databinding.ActivityReadRecordManageBinding
import io.legado.app.databinding.DialogReadRecordFilterBinding
import io.legado.app.databinding.ItemReadRecordManageBinding
import io.legado.app.databinding.ViewReadStatisticsMinimumBinding
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.book.ReadRecordFilterItem
import io.legado.app.help.book.ReadRecordTimeRange
import io.legado.app.help.book.ReadStatisticsSnapshot
import io.legado.app.help.book.filterReadRecords
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Callable

class ReadRecordManageActivity : BaseActivity<ActivityReadRecordManageBinding>() {
    override val binding by viewBinding(ActivityReadRecordManageBinding::inflate)
    private val adapter by lazy { ManageAdapter(this) }
    private val searchView: SearchView by lazy { binding.titleBar.findViewById(R.id.search_view) }
    private var snapshot = ReadStatisticsSnapshot(emptyList(), emptyList(), 0)
    private var range = ReadRecordTimeRange.ALL
    private var date = LocalDate.now()
    private var hour = LocalTime.now().hour
    private var minimum = 0L
    private var maximum: Long? = null
    private var query = ""
    private var loaded = false
    private var deleting = false
    private var loadJob: Job? = null
    private var visible = emptyList<ReadRecordFilterItem>()
    private val selected = linkedSetOf<Pair<String, String>>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        query = intent.getStringExtra("query").orEmpty()
        savedInstanceState?.let { state ->
            range = ReadRecordTimeRange.entries.getOrElse(state.getInt("range")) { ReadRecordTimeRange.ALL }
            date = runCatching { LocalDate.parse(state.getString("date")) }.getOrDefault(date)
            hour = state.getInt("hour", hour).coerceIn(0, 23)
            minimum = state.getLong("minimum").coerceAtLeast(0)
            maximum = if (state.getBoolean("hasMaximum")) state.getLong("maximum").coerceAtLeast(minimum) else null
            query = state.getString("query").orEmpty()
            selected += state.getStringArrayList("names").orEmpty().zip(state.getStringArrayList("authors").orEmpty())
        }
        binding.recyclerView.adapter = adapter
        binding.footer.applyNavigationBarPadding()
        binding.filter.setOnClickListener { showFilter() }
        binding.selectAll.setOnClickListener {
            val identities = visible.map { it.record.bookName to it.record.author }
            if (selected.containsAll(identities)) selected.clear() else selected.addAll(identities)
            updateSelection()
        }
        binding.deleteSelected.setOnClickListener {
            confirmDelete(visible.filter { (it.record.bookName to it.record.author) in selected })
        }
        searchView.applyTint(primaryTextColor)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = true
            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty()
                render()
                return true
            }
        })
        if (query.isNotEmpty()) searchView.isIconified = false
        searchView.setQuery(query, false)
        render()
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    private fun loadRecords() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            snapshot = withContext(IO) {
                appDb.runInTransaction(Callable {
                    val dao = appDb.readRecordDao
                    ReadStatisticsSnapshot(dao.allShow, dao.dailyShow(), 0, dao.hourlyShow())
                })
            }
            loaded = true
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("range", range.ordinal)
        outState.putString("date", date.toString())
        outState.putInt("hour", hour)
        outState.putLong("minimum", minimum)
        outState.putBoolean("hasMaximum", maximum != null)
        maximum?.let { outState.putLong("maximum", it) }
        outState.putString("query", query)
        outState.putStringArrayList("names", ArrayList(selected.map { it.first }))
        outState.putStringArrayList("authors", ArrayList(selected.map { it.second }))
        super.onSaveInstanceState(outState)
    }

    private fun duration(value: Long) = formatDuring(value, AppConfig.readRecordUseDays, AppConfig.readRecordShowSeconds)

    private fun rangeLabel(value: ReadRecordTimeRange, day: LocalDate, clock: Int): String = when (value) {
        ReadRecordTimeRange.ALL -> getString(R.string.read_statistics_all)
        ReadRecordTimeRange.YEAR -> getString(R.string.read_statistics_year_period, day.year)
        ReadRecordTimeRange.MONTH -> getString(R.string.read_statistics_month_period, day.year, day.monthValue)
        ReadRecordTimeRange.DAY -> day.toString()
        ReadRecordTimeRange.HOUR -> "${day} " + getString(R.string.read_statistics_hour_range, clock, clock + 1)
    }

    private fun render() {
        visible = snapshot.filterReadRecords(range, date, hour, minimum, maximum, query)
        if (loaded) selected.retainAll(visible.mapTo(hashSetOf()) { it.record.bookName to it.record.author })
        val bounds = maximum?.let { getString(R.string.read_record_manage_between, duration(minimum), duration(it)) }
            ?: if (minimum > 0) getString(R.string.read_record_manage_from, duration(minimum))
            else getString(R.string.read_record_manage_no_limit)
        binding.filter.text = getString(R.string.read_record_manage_summary, rangeLabel(range, date, hour), bounds)
        binding.filter.contentDescription = getString(R.string.read_record_manage_filter) + "：" + binding.filter.text
        binding.empty.isVisible = loaded && visible.isEmpty()
        adapter.setItems(visible)
        updateSelection()
    }

    private fun updateSelection() {
        binding.count.text = getString(R.string.read_record_manage_count, visible.size, selected.size)
        binding.selectAll.isChecked = visible.isNotEmpty() && selected.size == visible.size
        binding.selectAll.isEnabled = !deleting && visible.isNotEmpty()
        binding.deleteSelected.text = getString(R.string.read_record_manage_delete, selected.size)
        binding.deleteSelected.isEnabled = !deleting && selected.isNotEmpty()
        binding.filter.isEnabled = !deleting
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(items: List<ReadRecordFilterItem>) {
        if (deleting || items.isEmpty()) return
        val targets = items.map { ReadRecordBook(it.record.bookName, it.record.author) }
        val names = items.take(5).joinToString("\n") { "${it.record.bookName} · ${it.record.displayAuthor}" } +
            if (items.size > 5) "\n" + getString(R.string.read_record_manage_more, items.size - 5) else ""
        alert(R.string.delete) {
            setMessage(getString(R.string.read_record_manage_confirm, items.size, names,
                duration(items.sumOf { it.duration }), duration(items.sumOf { it.record.readTime })))
            yesButton {
                deleting = true
                updateSelection()
                lifecycleScope.launch {
                    try {
                        withContext(IO) {
                            appDb.readRecordDao.deleteBooks(targets)
                            ReadRecordCoverCache.prune()
                        }
                        selected.removeAll(targets.map { it.bookName to it.author }.toSet())
                        loadRecords()
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        toastOnUi(R.string.read_record_manage_failed)
                    } finally {
                        deleting = false
                        updateSelection()
                    }
                }
            }
            noButton()
        }
    }

    private fun showFilter() {
        val form = DialogReadRecordFilterBinding.inflate(layoutInflater)
        var candidateRange = range
        var candidateDate = date
        form.range.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.read_record_manage_hour), getString(R.string.read_statistics_day),
                getString(R.string.read_statistics_month), getString(R.string.read_statistics_year), getString(R.string.read_statistics_all)))
        form.hour.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            (0..23).map { getString(R.string.read_statistics_hour_range, it, it + 1) })
        form.hour.setSelection(hour)
        form.minimum.label.setText(R.string.read_record_manage_minimum)
        form.maximum.label.setText(R.string.read_record_manage_maximum)
        form.upperEnabled.isChecked = maximum != null
        fun setDuration(row: ViewReadStatisticsMinimumBinding, value: Long) {
            row.hours.setText((value / 3_600_000).toString())
            row.minutes.setText((value / 60_000 % 60).toString())
            row.seconds.setText((value / 1_000 % 60).toString())
        }
        setDuration(form.minimum, minimum)
        setDuration(form.maximum, maximum ?: 0L)
        for (row in listOf(form.minimum, form.maximum)) {
            listOf(row.hours, row.minutes, row.seconds).forEachIndexed { index, input ->
                input.layoutParams = input.layoutParams.apply { width = (if (index == 0) 48 else 36).dpToPx() }
                input.contentDescription = "${row.label.text} " + getString(listOf(R.string.read_statistics_hour_unit,
                    R.string.read_statistics_minute_unit, R.string.read_statistics_second_unit)[index])
                input.doAfterTextChanged { row.error.isVisible = false }
            }
        }
        fun refresh() {
            form.date.isVisible = candidateRange != ReadRecordTimeRange.ALL
            form.date.text = rangeLabel(candidateRange, candidateDate, form.hour.selectedItemPosition.coerceAtLeast(0))
            form.hour.isVisible = candidateRange == ReadRecordTimeRange.HOUR
            form.maximum.root.isVisible = form.upperEnabled.isChecked
        }
        form.range.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                candidateRange = ReadRecordTimeRange.entries[position]
                refresh()
            }
        }
        form.range.setSelection(range.ordinal)
        form.hour.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
        }
        form.upperEnabled.setOnCheckedChangeListener { _, _ -> refresh() }
        form.date.setOnClickListener {
            chooseDate(candidateRange, candidateDate) { candidateDate = it; refresh() }
        }
        form.presets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val values = when (position) {
                    1 -> 0L to null
                    2 -> 0L to 600_000L
                    3 -> 600_000L to 3_600_000L
                    4 -> 3_600_000L to 18_000_000L
                    5 -> 18_000_000L to null
                    else -> return
                }
                setDuration(form.minimum, values.first)
                form.upperEnabled.isChecked = values.second != null
                setDuration(form.maximum, values.second ?: 0)
                refresh()
            }
        }
        refresh()
        val dialog = alert(R.string.read_record_manage_filter) {
            customView { form.root }
            okButton()
            cancelButton()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun parse(row: ViewReadStatisticsMinimumBinding): Long? =
                readStatisticsMinimumMillis(row.hours.text.toString(), row.minutes.text.toString(), row.seconds.text.toString()).also {
                    row.error.setText(R.string.read_statistics_minimum_invalid)
                    row.error.isVisible = it == null
                }
            val low = parse(form.minimum) ?: return@setOnClickListener
            val high = if (form.upperEnabled.isChecked) parse(form.maximum) ?: return@setOnClickListener else null
            if (high != null && high < low) {
                form.maximum.error.setText(R.string.read_record_manage_bounds_error)
                form.maximum.error.isVisible = true
                return@setOnClickListener
            }
            range = candidateRange
            date = candidateDate
            hour = form.hour.selectedItemPosition.coerceIn(0, 23)
            minimum = low
            maximum = high
            selected.clear()
            dialog.dismiss()
            render()
        }
    }

    private fun chooseDate(value: ReadRecordTimeRange, current: LocalDate, selected: (LocalDate) -> Unit) {
        val today = LocalDate.now()
        val first = snapshot.daily.minOfOrNull { it.date }?.let(LocalDate::parse)?.coerceAtMost(today) ?: today
        if (value == ReadRecordTimeRange.YEAR || value == ReadRecordTimeRange.MONTH) {
            val years = (today.year downTo first.year).toList()
            alert(R.string.read_statistics_choose_year) {
                singleChoiceItems(years.map { getString(R.string.read_statistics_year_period, it) }.toTypedArray(),
                    years.indexOf(current.year)) { dialog, index ->
                    val year = years[index]
                    dialog.dismiss()
                    if (value == ReadRecordTimeRange.YEAR) selected(LocalDate.of(year, 1, 1))
                    else {
                        val months = (1..if (year == today.year) today.monthValue else 12).toList()
                        alert(R.string.read_statistics_choose_month) {
                            singleChoiceItems(months.map { getString(R.string.read_statistics_month_number, it) }.toTypedArray(),
                                months.indexOf(current.monthValue)) { monthDialog, monthIndex ->
                                selected(LocalDate.of(year, months[monthIndex], 1))
                                monthDialog.dismiss()
                            }
                            cancelButton()
                        }
                    }
                }
                cancelButton()
            }
        } else {
            val picker = DatePickerDialog(this, { _, year, month, day ->
                selected(LocalDate.of(year, month + 1, day))
            }, current.year, current.monthValue - 1, current.dayOfMonth)
            picker.datePicker.maxDate = System.currentTimeMillis()
            picker.show()
        }
    }

    private inner class ManageAdapter(context: Context) :
        RecyclerAdapter<ReadRecordFilterItem, ItemReadRecordManageBinding>(context) {
        override fun getViewBinding(parent: ViewGroup) = ItemReadRecordManageBinding.inflate(inflater, parent, false)
        override fun convert(holder: ItemViewHolder, binding: ItemReadRecordManageBinding,
            item: ReadRecordFilterItem, payloads: MutableList<Any>) {
            val record = item.record
            binding.name.text = record.bookName
            binding.author.text = record.displayAuthor.ifBlank { getString(R.string.read_record_no_author) }
            binding.duration.text = getString(R.string.read_record_manage_times, duration(item.duration), duration(record.readTime))
            binding.selected.isChecked = (record.bookName to record.author) in selected
            binding.selected.contentDescription = "${record.bookName} · ${binding.author.text}"
            binding.selected.isEnabled = !deleting
            binding.delete.isEnabled = !deleting
            binding.delete.contentDescription = getString(R.string.read_record_manage_delete_one, record.bookName)
        }
        override fun registerListener(holder: ItemViewHolder, binding: ItemReadRecordManageBinding) {
            fun toggle() {
                if (deleting) return
                val record = getItemByLayoutPosition(holder.layoutPosition)?.record ?: return
                val identity = record.bookName to record.author
                if (!selected.add(identity)) selected.remove(identity)
                updateSelection()
            }
            binding.root.setOnClickListener { toggle() }
            binding.selected.setOnClickListener { toggle() }
            binding.delete.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { confirmDelete(listOf(it)) }
            }
        }
    }
}
