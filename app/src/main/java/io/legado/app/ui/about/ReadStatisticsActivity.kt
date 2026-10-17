package io.legado.app.ui.about

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.databinding.ActivityReadStatisticsBinding
import io.legado.app.databinding.DialogReadStatisticsRankingBinding
import io.legado.app.databinding.DialogReadStatisticsPeriodBinding
import io.legado.app.databinding.ItemReadStatisticsBinding
import io.legado.app.databinding.ViewReadStatisticsHeaderBinding
import io.legado.app.help.book.ReadStatisticsPeriod
import io.legado.app.help.book.ReadStatisticsSnapshot
import io.legado.app.help.book.ReadStatisticsSummary
import io.legado.app.help.book.visibleReadStatisticsBooks
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getCompatDrawable
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Callable

class ReadStatisticsActivity : BaseActivity<ActivityReadStatisticsBinding>() {
    override val binding by viewBinding(ActivityReadStatisticsBinding::inflate)
    private val header by lazy {
        ViewReadStatisticsHeaderBinding.inflate(layoutInflater, binding.recyclerView, false)
    }
    private val contentPrimary get() = getCompatColor(R.color.primaryText)
    private val contentSecondary get() = getCompatColor(R.color.secondaryText)
    private val adapter by lazy { StatisticsAdapter(this) }
    private var dataJob: Job? = null
    private var snapshot = ReadStatisticsSnapshot(emptyList(), emptyList(), 0)
    private var bookshelf: Map<Pair<String, String>, Book> = emptyMap()
    private var period = ReadStatisticsPeriod.MONTH
    private var year = LocalDate.now().year
    private var month = LocalDate.now().monthValue
    private var selectedDay: String? = LocalDate.now().toString()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        savedInstanceState?.let {
            period = ReadStatisticsPeriod.entries.getOrElse(it.getInt("period")) { ReadStatisticsPeriod.MONTH }
            year = it.getInt("year", year)
            month = it.getInt("month", month).coerceIn(1, 12)
            if (it.containsKey("day")) selectedDay = it.getString("day")
        }
        adapter.addHeaderView { header }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        header.tabAll.setOnClickListener { selectPeriod(ReadStatisticsPeriod.ALL) }
        header.tabYear.setOnClickListener { selectPeriod(ReadStatisticsPeriod.YEAR) }
        header.tabMonth.setOnClickListener { selectPeriod(ReadStatisticsPeriod.MONTH) }
        header.previousPeriod.setOnClickListener { movePeriod(-1) }
        header.nextPeriod.setOnClickListener { movePeriod(1) }
        header.tvPeriod.setOnClickListener { showPeriodPicker() }
        val surface = ColorUtils.blendColors(backgroundColor, accentColor, .06f)
        header.summary.background = rounded(surface)
        header.trendPanel.background = rounded(surface)
    }

    override fun onResume() {
        super.onResume()
        dataJob?.cancel()
        dataJob = lifecycleScope.launch {
            val loaded = withContext(IO) {
                val stats = appDb.runInTransaction(Callable {
                    val dao = appDb.readRecordDao
                    ReadStatisticsSnapshot(
                        dao.allShow, dao.dailyShow(),
                        dao.statisticsStates().map { it.startedAt }.filter { it > 0 }.minOrNull() ?: 0,
                    )
                })
                stats to appDb.bookDao.all.sortedBy { it.durChapterTime }.associateBy { it.name to it.author }
            }
            snapshot = loaded.first
            bookshelf = loaded.second
            clampPeriod()
            render()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.read_statistics, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_statistics_ranking) {
            showRankingSettings()
            return true
        }
        if (item.itemId == R.id.menu_statistics_help) {
            alert(R.string.read_statistics_help) {
                setMessage(getString(R.string.read_statistics_coverage, earliestDate().toString()))
                okButton()
            }
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showRankingSettings() {
        val settings = DialogReadStatisticsRankingBinding.inflate(layoutInflater)
        val rows = listOf(settings.dayMinimum, settings.monthMinimum, settings.yearMinimum, settings.allMinimum)
        val labels = listOf(R.string.read_statistics_day_minimum, R.string.read_statistics_month_minimum,
            R.string.read_statistics_year_minimum, R.string.read_statistics_all_minimum)
        val keys = listOf("readStatisticsDayMinimum", "readStatisticsMonthMinimum",
            "readStatisticsYearMinimum", "readStatisticsAllMinimum")
        val values = listOf(AppConfig.readStatisticsDayMinimum, AppConfig.readStatisticsMonthMinimum,
            AppConfig.readStatisticsYearMinimum, AppConfig.readStatisticsAllMinimum)
        val units = listOf(R.string.read_statistics_hour_unit, R.string.read_statistics_minute_unit,
            R.string.read_statistics_second_unit)
        rows.forEachIndexed { index, row ->
            row.label.setText(labels[index])
            ViewCompat.setAccessibilityHeading(row.label, true)
            val parts = listOf(values[index] / 3_600_000, values[index] / 60_000 % 60, values[index] / 1_000 % 60)
            listOf(row.hours, row.minutes, row.seconds).forEachIndexed { part, field ->
                field.setText(parts[part].toString())
                field.contentDescription = getString(R.string.read_statistics_minimum_accessibility,
                    getString(labels[index]), getString(units[part]))
                field.backgroundTintList = null
                field.background = StateListDrawable().apply {
                    fun box(focused: Boolean) = GradientDrawable().apply {
                        cornerRadius = 8.dpToPx().toFloat()
                        setColor(ColorUtils.blendColors(backgroundColor, accentColor, .03f))
                        setStroke(1.dpToPx(), if (focused) accentColor
                            else ColorUtils.blendColors(backgroundColor, contentSecondary, .35f))
                    }
                    addState(intArrayOf(android.R.attr.state_focused), box(true))
                    addState(intArrayOf(), box(false))
                }
                field.doAfterTextChanged { row.error.isVisible = false }
            }
        }
        val dialog = alert(R.string.read_statistics_ranking_settings) {
            customView { settings.root }
            okButton()
            cancelButton()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val durations = rows.mapIndexed { index, row ->
                readStatisticsMinimumMillis(row.hours.text.toString(), row.minutes.text.toString(),
                    row.seconds.text.toString()).also { value ->
                    row.error.isVisible = value == null
                    row.error.setText(R.string.read_statistics_minimum_invalid)
                }?.let { value ->
                    // Preserve any subsecond remainder in an existing preference left unchanged.
                    if (value == values[index] / 1_000 * 1_000) values[index] else value
                }
            }
            val invalid = durations.indexOfFirst { it == null }
            if (invalid >= 0) {
                rows[invalid].error.post {
                    val error = rows[invalid].error
                    error.requestRectangleOnScreen(Rect(0, 0, error.width, error.height))
                }
                return@setOnClickListener
            }
            defaultSharedPreferences.edit().apply {
                keys.forEachIndexed { index, key -> putLong(key, checkNotNull(durations[index])) }
            }.apply()
            dialog.dismiss()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("period", period.ordinal)
        outState.putInt("year", year)
        outState.putInt("month", month)
        outState.putString("day", selectedDay)
        super.onSaveInstanceState(outState)
    }

    private fun earliestDate(): LocalDate {
        val start = snapshot.startedAt.takeIf { it > 0 }?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: LocalDate.now()
        val firstDetail = snapshot.daily.minOfOrNull { it.date }?.let { LocalDate.parse(it) }
        return minOf(start, firstDetail ?: start, LocalDate.now())
    }

    private fun clampPeriod() {
        val today = LocalDate.now()
        year = year.coerceIn(earliestDate().year, today.year)
        if (period == ReadStatisticsPeriod.MONTH) {
            val selected = YearMonth.of(year, month)
                .coerceIn(YearMonth.from(earliestDate()), YearMonth.from(today))
            year = selected.year
            month = selected.monthValue
        }
    }

    private fun selectPeriod(value: ReadStatisticsPeriod) {
        val changed = period != value
        period = value
        clampPeriod()
        if (changed) resetSelectedDay()
        render()
        binding.recyclerView.scrollToPosition(0)
    }

    private fun movePeriod(offset: Long) {
        if (period == ReadStatisticsPeriod.MONTH) {
            val selected = YearMonth.of(year, month).plusMonths(offset)
            year = selected.year
            month = selected.monthValue
        } else year += offset.toInt()
        clampPeriod()
        resetSelectedDay()
        render()
    }

    private fun resetSelectedDay() {
        selectedDay = LocalDate.now().takeIf { YearMonth.from(it) == YearMonth.of(year, month) }?.toString()
    }

    private fun jumpToPeriod(value: YearMonth, selectToday: Boolean = false) {
        val changed = year != value.year || month != value.monthValue
        year = value.year
        month = value.monthValue
        clampPeriod()
        if (changed || selectToday) resetSelectedDay()
        render()
        binding.recyclerView.scrollToPosition(0)
    }

    private fun showYearPicker(selected: Int, returnToCurrent: Boolean = false, onSelect: (Int) -> Unit) {
        val years = (LocalDate.now().year downTo earliestDate().year).toList()
        val checked = years.indexOf(selected).coerceAtLeast(0)
        val dialog = alert(R.string.read_statistics_choose_year) {
            singleChoiceItems(years.map { getString(R.string.read_statistics_year_period, it) }.toTypedArray(), checked) { dialog, index ->
                onSelect(years[index])
                dialog.dismiss()
            }
            cancelButton()
            if (returnToCurrent) neutralButton(R.string.read_statistics_current_year) {
                onSelect(LocalDate.now().year)
            }
        }
        dialog.listView.setSelection(checked)
    }

    private fun showPeriodPicker() {
        when (period) {
            ReadStatisticsPeriod.ALL -> return
            ReadStatisticsPeriod.YEAR -> showYearPicker(year, returnToCurrent = true) {
                jumpToPeriod(YearMonth.of(it, month))
            }
            ReadStatisticsPeriod.MONTH -> {
                val picker = DialogReadStatisticsPeriodBinding.inflate(layoutInflater)
                val first = YearMonth.from(earliestDate())
                val current = YearMonth.now()
                var candidate = YearMonth.of(year, month).coerceIn(first, current)
                val months = (1..12).map { value ->
                    AppCompatButton(this).apply {
                        text = getString(R.string.read_statistics_month_number, value)
                        textSize = 15f
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = 48.dpToPx()
                        minimumHeight = 48.dpToPx()
                        setPadding(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
                        backgroundTintList = null
                        picker.periodMonths.addView(this, GridLayout.LayoutParams().apply {
                            width = 0
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                        })
                    }
                }
                fun updatePicker() {
                    picker.periodYear.text = getString(R.string.read_statistics_year_period, candidate.year)
                    picker.periodYear.contentDescription = getString(R.string.read_statistics_select_year, candidate.year)
                    months.forEachIndexed { index, button ->
                        val value = YearMonth.of(candidate.year, index + 1)
                        button.isEnabled = value in first..current
                        button.isSelected = value == candidate
                        button.contentDescription = getString(R.string.read_statistics_month_period, value.year, value.monthValue)
                        button.alpha = if (button.isEnabled) 1f else .35f
                        button.setTextColor(if (button.isSelected) accentColor else contentPrimary)
                        button.background = rounded(
                            ColorUtils.blendColors(backgroundColor, accentColor, if (button.isSelected) .20f else .04f),
                            button.isSelected,
                        )
                        button.setOnClickListener {
                            candidate = value
                            updatePicker()
                        }
                    }
                }
                picker.periodYear.setTextColor(contentPrimary)
                picker.periodYear.setCompoundDrawablesRelative(null, null, periodDropdown(), null)
                picker.periodYear.setOnClickListener {
                    showYearPicker(candidate.year) {
                        candidate = YearMonth.of(it, candidate.monthValue).coerceIn(first, current)
                        updatePicker()
                    }
                }
                updatePicker()
                alert(R.string.read_statistics_choose_month) {
                    customView { picker.root }
                    okButton { jumpToPeriod(candidate) }
                    cancelButton()
                    neutralButton(R.string.read_statistics_current_month) {
                        jumpToPeriod(YearMonth.now(), selectToday = true)
                    }
                }
            }
        }
    }

    private fun periodDropdown() = getCompatDrawable(R.drawable.ic_arrow_drop_down)?.mutate()?.apply {
        setTint(contentPrimary)
        setBounds(0, 0, 18.dpToPx(), 18.dpToPx())
    }

    private fun duration(value: Long) = formatDuring(
        value, AppConfig.readRecordUseDays, AppConfig.readRecordShowSeconds,
    )

    private fun render() {
        val summary = snapshot.summary(period, year, month)
        val minimum = when (period) {
            ReadStatisticsPeriod.ALL -> AppConfig.readStatisticsAllMinimum
            ReadStatisticsPeriod.YEAR -> AppConfig.readStatisticsYearMinimum
            ReadStatisticsPeriod.MONTH -> AppConfig.readStatisticsMonthMinimum
        }
        val visibleBooks = summary.books.visibleReadStatisticsBooks(minimum)
        val buttons = listOf(header.tabAll, header.tabYear, header.tabMonth)
        buttons.forEachIndexed { index, button ->
            val selected = index == period.ordinal
            button.isSelected = selected
            button.backgroundTintList = ColorStateList.valueOf(
                ColorUtils.blendColors(backgroundColor, accentColor, if (selected) .20f else .04f),
            )
            button.setTextColor(if (selected) accentColor else contentPrimary)
        }
        val today = LocalDate.now()
        val first = earliestDate()
        with(header) {
            tvPeriod.text = when (period) {
                ReadStatisticsPeriod.ALL -> getString(R.string.read_statistics_all_period)
                ReadStatisticsPeriod.YEAR -> getString(R.string.read_statistics_year_period, year)
                ReadStatisticsPeriod.MONTH -> getString(R.string.read_statistics_month_period, year, month)
            }
            val canSelect = period != ReadStatisticsPeriod.ALL
            tvPeriod.minHeight = if (canSelect) 48.dpToPx() else 0
            tvPeriod.minimumHeight = if (canSelect) 48.dpToPx() else 0
            tvPeriod.isClickable = canSelect
            tvPeriod.isFocusable = canSelect
            tvPeriod.contentDescription = if (canSelect)
                getString(R.string.read_statistics_select_period, tvPeriod.text) else null
            tvPeriod.setCompoundDrawablesRelative(null, null, if (canSelect) periodDropdown() else null, null)
            previousPeriod.isVisible = period != ReadStatisticsPeriod.ALL
            nextPeriod.isVisible = period != ReadStatisticsPeriod.ALL
            previousPeriod.isEnabled = if (period == ReadStatisticsPeriod.MONTH)
                YearMonth.of(year, month) > YearMonth.from(first) else year > first.year
            nextPeriod.isEnabled = if (period == ReadStatisticsPeriod.MONTH)
                YearMonth.of(year, month) < YearMonth.from(today) else year < today.year
            previousPeriod.contentDescription = getString(if (period == ReadStatisticsPeriod.MONTH)
                R.string.read_statistics_previous_month else R.string.read_statistics_previous_year)
            nextPeriod.contentDescription = getString(if (period == ReadStatisticsPeriod.MONTH)
                R.string.read_statistics_next_month else R.string.read_statistics_next_year)
            tvDurationLabel.setText(when (period) {
                ReadStatisticsPeriod.ALL -> R.string.read_statistics_total_duration
                ReadStatisticsPeriod.YEAR -> R.string.read_statistics_year_duration
                ReadStatisticsPeriod.MONTH -> R.string.read_statistics_month_duration
            })
            tvDuration.text = duration(summary.duration)
            tvDays.text = "${getString(R.string.read_statistics_day_count, summary.days.size)}\n${getString(R.string.read_statistics_days)}"
            tvBooks.text = "${getString(R.string.read_statistics_book_count, summary.books.size)}\n${getString(R.string.read_statistics_books)}"
            tvAverage.text = "${duration(summary.average)}\n${getString(R.string.read_statistics_average)}"
            tvHistory.isVisible = period == ReadStatisticsPeriod.ALL && summary.historyDuration > 0
            tvHistory.text = getString(R.string.read_statistics_history, duration(summary.historyDuration))
            tvRankTitle.setText(when (period) {
                ReadStatisticsPeriod.ALL -> R.string.read_statistics_all_books
                ReadStatisticsPeriod.YEAR -> R.string.read_statistics_year_books
                ReadStatisticsPeriod.MONTH -> R.string.read_statistics_month_books
            })
            tvEmpty.isVisible = visibleBooks.isEmpty()
            tvEmpty.setText(if (summary.books.isEmpty()) R.string.read_statistics_empty else R.string.read_statistics_filtered_empty)
            calendarPanel.isVisible = period == ReadStatisticsPeriod.MONTH
            chart.isVisible = period != ReadStatisticsPeriod.MONTH
            tvPeak.isVisible = period != ReadStatisticsPeriod.MONTH
        }
        if (period == ReadStatisticsPeriod.MONTH) {
            header.tvTrendTitle.setText(R.string.read_statistics_calendar)
            renderCalendar(summary)
        } else renderChart(summary)
        adapter.setItems(visibleBooks)
    }

    private fun renderChart(summary: ReadStatisticsSummary) {
        val bars = if (period == ReadStatisticsPeriod.ALL) {
            header.tvTrendTitle.setText(R.string.read_statistics_years_chart)
            (earliestDate().year..LocalDate.now().year).map { value ->
                ReadStatisticsBar(value.toString(), summary.days.filterKeys { it.startsWith("$value-") }.values.sum())
            }
        } else {
            header.tvTrendTitle.setText(R.string.read_statistics_months_chart)
            (1..12).map { value ->
                val prefix = YearMonth.of(year, value).toString()
                ReadStatisticsBar(value.toString(), summary.days.filterKeys { it.startsWith(prefix) }.values.sum())
            }
        }
        header.chart.bars = bars
        val peak = bars.maxByOrNull { it.duration }
        header.tvPeak.text = peak?.takeIf { it.duration > 0 }?.let {
            val label = if (period == ReadStatisticsPeriod.ALL) getString(R.string.read_statistics_year_period, it.label.toInt())
                else getString(R.string.read_statistics_month_period, year, it.label.toInt())
            getString(R.string.read_statistics_peak, label, duration(it.duration))
        }.orEmpty()
    }

    private fun renderCalendar(summary: ReadStatisticsSummary) {
        val grid = header.calendarGrid
        grid.removeAllViews()
        val selectedMonth = YearMonth.of(year, month)
        if (selectedDay?.startsWith("$selectedMonth-") == false) selectedDay = null
        fun addCell(view: View, height: Int) {
            grid.addView(view, GridLayout.LayoutParams().apply {
                width = 0
                this.height = height.dpToPx()
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        resources.getStringArray(R.array.read_statistics_weekdays).forEach { day ->
            addCell(TextView(this).apply {
                text = day
                gravity = Gravity.CENTER
                setTextColor(contentSecondary)
                textSize = 12f
            }, 32)
        }
        repeat(selectedMonth.atDay(1).dayOfWeek.value - 1) { addCell(View(this), 44) }
        val max = summary.days.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        for (day in 1..selectedMonth.lengthOfMonth()) {
            val date = selectedMonth.atDay(day)
            val key = date.toString()
            val elapsed = summary.days[key] ?: 0L
            addCell(AppCompatButton(this).apply {
                text = day.toString()
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                isEnabled = date <= LocalDate.now()
                isSelected = key == selectedDay
                setTextColor(if (isEnabled) contentPrimary else contentSecondary)
                alpha = if (isEnabled) 1f else .35f
                val strength = if (elapsed <= 0) .03f else .15f + .50f * elapsed.toFloat() / max
                background = rounded(ColorUtils.blendColors(backgroundColor, accentColor, strength), key == selectedDay)
                contentDescription = getString(R.string.read_statistics_day_accessibility, key, duration(elapsed))
                setOnClickListener {
                    selectedDay = key.takeUnless { it == selectedDay }
                    renderCalendar(summary)
                }
            }, 44)
        }
        header.dayBooks.removeAllViews()
        val day = selectedDay
        header.tvSelectedDay.isVisible = day != null
        header.dayBooks.isVisible = day != null
        if (day == null) {
            header.tvSelectedDay.text = ""
            return
        }
        header.tvSelectedDay.text = getString(
            R.string.read_statistics_selected_day, day, duration(summary.days[day] ?: 0),
        )
        val times = snapshot.daily.filter { it.date == day }
            .associate { (it.bookName to it.author) to it.readTime }
        val dayBooks = snapshot.records.mapNotNull { record ->
            times[record.bookName to record.author]?.takeIf { it > 0 }?.let { record.copy(readTime = it) }
        }.sortedByDescending { it.readTime }
        val books = dayBooks.visibleReadStatisticsBooks(AppConfig.readStatisticsDayMinimum)
        if (books.isEmpty()) {
            header.dayBooks.addView(TextView(this).apply {
                setText(if (dayBooks.isEmpty()) R.string.read_statistics_day_empty else R.string.read_statistics_filtered_empty)
                setTextColor(contentSecondary)
                setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
            })
        } else books.forEachIndexed { index, record ->
            val row = ItemReadStatisticsBinding.inflate(layoutInflater, header.dayBooks, false)
            bindBook(row, record, index, books.first().readTime)
            row.root.setOnClickListener { openRecordedBook(record) }
            header.dayBooks.addView(row.root)
        }
    }

    private fun rounded(color: Int, selected: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 12.dpToPx().toFloat()
        if (selected) setStroke(2.dpToPx(), accentColor) else setStroke(2.dpToPx(), Color.TRANSPARENT)
    }

    private fun bindBook(row: ItemReadStatisticsBinding, item: ReadRecordShow, index: Int, maximum: Long) {
        row.tvRank.text = (index + 1).toString()
        row.tvBookName.text = item.bookName
        row.tvAuthor.text = item.displayAuthor.ifBlank { getString(R.string.read_record_no_author) }
        row.tvReadingTime.text = duration(item.readTime)
        row.progress.progress = (1000.0 * item.readTime / maximum.coerceAtLeast(1)).toInt()
        row.progress.progressTintList = ColorStateList.valueOf(accentColor)
        loadReadRecordCover(row.ivCover, item, bookshelf[item.bookName to item.author])
    }

    private inner class StatisticsAdapter(context: Context) :
        RecyclerAdapter<ReadRecordShow, ItemReadStatisticsBinding>(context) {
        override fun getViewBinding(parent: ViewGroup) = ItemReadStatisticsBinding.inflate(inflater, parent, false)

        override fun convert(holder: ItemViewHolder, binding: ItemReadStatisticsBinding, item: ReadRecordShow, payloads: MutableList<Any>) {
            bindBook(binding, item, holder.layoutPosition - getHeaderCount(), getItem(0)?.readTime ?: 1)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadStatisticsBinding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { openRecordedBook(it) }
            }
        }
    }
}
