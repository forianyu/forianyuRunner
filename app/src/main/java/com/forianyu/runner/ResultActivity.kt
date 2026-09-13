package com.forianyu.runner

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.forianyu.runner.data.RunDatabase
import com.forianyu.runner.data.RunRecord
import com.forianyu.runner.databinding.ActivityResultBinding
import com.forianyu.runner.databinding.ItemStageResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.viewStatsButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        val stages: List<StageResult> = intent.parcelableArrayList<StageResult>(EXTRA_STAGES) ?: emptyList()
        val sessionStartWallClockMs = intent.getLongExtra(EXTRA_SESSION_START_WALL_CLOCK_MS, 0L)
        val weightKg = intent.getDoubleExtra(EXTRA_WEIGHT_KG, 0.0)

        val totalDistanceMeters = stages.sumOf { it.distanceMeters }
        val totalDurationMs = stages.sumOf { it.durationMs }
        val distanceKm = totalDistanceMeters / 1000.0
        val kcal = weightKg * distanceKm * KCAL_PER_KG_PER_KM
        val weightLossGrams = kcal / KCAL_PER_KG_FAT * 1000.0

        // Only on a fresh launch, not a config-change recreation, so a
        // finished run is saved to history exactly once.
        if (savedInstanceState == null) {
            val record = RunRecord(
                startWallClockMs = sessionStartWallClockMs,
                endWallClockMs = stages.lastOrNull()?.endWallClockMs ?: sessionStartWallClockMs,
                totalDistanceMeters = totalDistanceMeters,
                totalDurationMs = totalDurationMs,
                kcal = kcal,
                weightKg = weightKg,
                stageCount = stages.size
            )
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    RunDatabase.getInstance(applicationContext).runRecordDao().insert(record)
                }
            }
        }

        binding.resultSubtitleText.text =
            sessionDateFormat.format(Date(sessionStartWallClockMs)) + " " + getString(R.string.result_subtitle_started_suffix)

        binding.heroDistanceText.text = String.format(Locale.US, "%.2f", distanceKm)
        binding.heroTimeText.text = RunFormat.duration(totalDurationMs).let {
            // RunFormat.duration is mm:ss; the hero wants hh:mm:ss like the running screen.
            val totalSeconds = totalDurationMs / 1000
            String.format(
                Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60
            )
        }
        binding.heroCaptionText.text = getString(R.string.result_hero_caption, stages.size)

        binding.calorieValueText.text = String.format(Locale.US, "%.0f kcal", kcal)
        binding.calorieCaptionText.text = getString(R.string.result_calorie_caption, weightKg.toInt())
        binding.weightLossValueText.text = String.format(Locale.US, "%.0f g", weightLossGrams)

        val inflater = LayoutInflater.from(this)
        stages.forEach { stage ->
            if (binding.stageListContainer.childCount > 0) {
                inflater.inflate(R.layout.table_divider, binding.stageListContainer, true)
            }
            val itemBinding = ItemStageResultBinding.inflate(inflater, binding.stageListContainer, true)
            itemBinding.stageIndexText.text = stage.index.toString()
            itemBinding.stageLabelText.text = getString(R.string.result_stage_item_label, stage.index)
            itemBinding.stageTimeRangeText.text =
                "${clockFormat.format(Date(stage.startWallClockMs))} → ${clockFormat.format(Date(stage.endWallClockMs))}"
            itemBinding.stageDistanceText.text = RunFormat.distanceKm(stage.distanceMeters)
            itemBinding.stageDurationText.text = RunFormat.duration(stage.durationMs)
        }
    }

    companion object {
        const val EXTRA_STAGES = "extra_stages"
        const val EXTRA_SESSION_START_WALL_CLOCK_MS = "extra_session_start_wall_clock_ms"
        const val EXTRA_WEIGHT_KG = "extra_weight_kg"

        // kcal burned per kg of body weight per km run - a standard running
        // energy-expenditure rule of thumb (roughly constant across paces).
        private const val KCAL_PER_KG_PER_KM = 1.036

        // Rough rule of thumb: ~7700 kcal of energy deficit per kg of body fat.
        private const val KCAL_PER_KG_FAT = 7700.0

        private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val sessionDateFormat = SimpleDateFormat("M월 d일 EEEE · a h:mm", Locale.KOREAN)
    }
}

private inline fun <reified T : Parcelable> Intent.parcelableArrayList(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
