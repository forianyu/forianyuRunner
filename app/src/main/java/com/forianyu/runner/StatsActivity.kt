package com.forianyu.runner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forianyu.runner.data.RunDatabase
import com.forianyu.runner.data.RunRecord
import com.forianyu.runner.databinding.ActivityStatsBinding
import com.forianyu.runner.databinding.ItemRunRecordBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class StatsActivity : AppCompatActivity() {

    private enum class PeriodType { DAY, WEEK, MONTH, YEAR }

    private lateinit var binding: ActivityStatsBinding
    private val zoneId = ZoneId.systemDefault()

    private var periodType = PeriodType.DAY
    private var cursorDate: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.tabDay.setOnClickListener { selectPeriodType(PeriodType.DAY) }
        binding.tabWeek.setOnClickListener { selectPeriodType(PeriodType.WEEK) }
        binding.tabMonth.setOnClickListener { selectPeriodType(PeriodType.MONTH) }
        binding.tabYear.setOnClickListener { selectPeriodType(PeriodType.YEAR) }
        binding.prevPeriodButton.setOnClickListener { shiftCursor(-1) }
        binding.nextPeriodButton.setOnClickListener { shiftCursor(1) }

        updateTabHighlight()
        loadPeriod()
    }

    private fun selectPeriodType(type: PeriodType) {
        if (periodType == type) return
        periodType = type
        cursorDate = LocalDate.now()
        updateTabHighlight()
        loadPeriod()
    }

    private fun shiftCursor(direction: Long) {
        cursorDate = when (periodType) {
            PeriodType.DAY -> cursorDate.plusDays(direction)
            PeriodType.WEEK -> cursorDate.plusWeeks(direction)
            PeriodType.MONTH -> cursorDate.plusMonths(direction)
            PeriodType.YEAR -> cursorDate.plusYears(direction)
        }
        loadPeriod()
    }

    private fun updateTabHighlight() {
        val tabs = listOf(
            binding.tabDay to PeriodType.DAY,
            binding.tabWeek to PeriodType.WEEK,
            binding.tabMonth to PeriodType.MONTH,
            binding.tabYear to PeriodType.YEAR
        )
        tabs.forEach { (view, type) ->
            val selected = type == periodType
            view.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else 0)
            view.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.bg_top else R.color.text_secondary)
            )
        }
    }

    /** [start, end) as epoch millis for the currently selected period. */
    private fun periodRangeMs(): Pair<Long, Long> {
        val (start, end) = when (periodType) {
            PeriodType.DAY -> cursorDate to cursorDate.plusDays(1)
            PeriodType.WEEK -> {
                val weekStart = cursorDate.with(DayOfWeek.MONDAY)
                weekStart to weekStart.plusDays(7)
            }
            PeriodType.MONTH -> {
                val monthStart = cursorDate.withDayOfMonth(1)
                monthStart to monthStart.plusMonths(1)
            }
            PeriodType.YEAR -> {
                val yearStart = cursorDate.withDayOfYear(1)
                yearStart to yearStart.plusYears(1)
            }
        }
        return start.atStartOfDay(zoneId).toInstant().toEpochMilli() to
            end.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    private fun periodLabel(): String = when (periodType) {
        PeriodType.DAY -> cursorDate.format(DAY_LABEL_FORMAT)
        PeriodType.WEEK -> {
            val weekStart = cursorDate.with(DayOfWeek.MONDAY)
            val weekEnd = weekStart.plusDays(6)
            "${weekStart.format(WEEK_LABEL_FORMAT)} ~ ${weekEnd.format(WEEK_LABEL_FORMAT)}"
        }
        PeriodType.MONTH -> cursorDate.format(MONTH_LABEL_FORMAT)
        PeriodType.YEAR -> cursorDate.format(YEAR_LABEL_FORMAT)
    }

    private fun loadPeriod() {
        binding.periodLabelText.text = periodLabel()
        val (fromMs, toMs) = periodRangeMs()
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                RunDatabase.getInstance(applicationContext).runRecordDao().getInRange(fromMs, toMs)
            }
            renderRecords(records)
        }
    }

    private fun renderRecords(records: List<RunRecord>) {
        val totalDistanceMeters = records.sumOf { it.totalDistanceMeters }
        val totalDurationMs = records.sumOf { it.totalDurationMs }
        val totalKcal = records.sumOf { it.kcal }

        binding.totalDistanceValueText.text = RunFormat.distanceKm(totalDistanceMeters)
        binding.totalDurationValueText.text = totalDurationHms(totalDurationMs)
        binding.totalCalorieValueText.text = String.format(Locale.US, "%.0f kcal", totalKcal)
        binding.runCountValueText.text = getString(R.string.stats_run_count_value, records.size)

        binding.runListContainer.removeAllViews()
        binding.emptyStateText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        records.forEach { record ->
            if (binding.runListContainer.childCount > 0) {
                inflater.inflate(R.layout.table_divider, binding.runListContainer, true)
            }
            val itemBinding = ItemRunRecordBinding.inflate(inflater, binding.runListContainer, true)
            val startDateTime = Instant.ofEpochMilli(record.startWallClockMs).atZone(zoneId)
            itemBinding.runDateText.text = startDateTime.format(RECORD_DATE_FORMAT)
            itemBinding.runTimeRangeText.text = startDateTime.format(RECORD_TIME_FORMAT)
            itemBinding.runDistanceText.text = RunFormat.distanceKm(record.totalDistanceMeters)
            itemBinding.runDurationText.text = RunFormat.duration(record.totalDurationMs)
        }
    }

    private fun totalDurationHms(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60
        )
    }

    companion object {
        private val DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN)
        private val WEEK_LABEL_FORMAT = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
        private val MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN)
        private val YEAR_LABEL_FORMAT = DateTimeFormatter.ofPattern("yyyy년", Locale.KOREAN)
        private val RECORD_DATE_FORMAT = DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN)
        private val RECORD_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)
    }
}
