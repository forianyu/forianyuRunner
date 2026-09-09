package com.forianyu.runner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.forianyu.runner.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Short form for the cramped stage comparison table - full HH:mm:ss
    // doesn't fit its columns without wrapping (see updateStageDisplay()).
    private val stageTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var isTracking = false
    private var totalDistanceMeters = 0.0
    private var lastGoodLocation: Location? = null
    private var startElapsedRealtimeMs = 0L
    private var sessionStartWallClockMs = 0L
    private var weightKg = DEFAULT_WEIGHT_KG

    // Stages (단계): slices of the run split by "다음 단계" presses. completedStages
    // holds everything closed out so far; the stageStart* fields describe the
    // one still in progress (or, right after stop, the final one about to be
    // closed - see stopTracking()).
    private val completedStages = mutableListOf<StageResult>()
    private var stageIndex = 1
    private var stageStartElapsedRealtimeMs = 0L
    private var stageStartWallClockMs = 0L
    private var stageStartDistanceMeters = 0.0

    // Trailing 60-second (elapsedMs, cumulativeDistanceMeters) samples, oldest
    // first, used to compute a continuously-updated "average speed over the
    // last minute" - see recordDistanceSampleAndUpdateAvgSpeed() for why this
    // replaced a simpler once-a-minute snapshot comparison.
    private val speedHistory = ArrayDeque<Pair<Long, Double>>()
    private var lastAnnounceElapsedMs = 0L

    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val voiceAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(voiceAudioAttributes)
        .build()

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimeDisplay()
            updateStageDisplay()
            recordDistanceSampleAndUpdateAvgSpeed()
            checkTenMinuteAnnouncement()
            timerHandler.postDelayed(this, TIMER_TICK_MS)
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateCurrentTimeDisplay()
            clockHandler.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) handleNewLocation(location)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                proceedIfGpsEnabled()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        binding.startButton.setOnClickListener { onStartButtonClicked() }
        binding.nextStageButton.setOnClickListener { advanceStage() }

        weightKg = loadSavedWeightKg()
        binding.weightInput.setText(weightKg.toInt().toString())
        binding.weightInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistWeightFromInput() }
        binding.weightInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                persistWeightFromInput()
                binding.weightInput.clearFocus()
                true
            } else {
                false
            }
        }

        clockHandler.post(clockRunnable)

        audioManager = getSystemService(AudioManager::class.java)
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.KOREAN
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        timerHandler.removeCallbacks(timerRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        tts?.stop()
        tts?.shutdown()
    }

    private fun onStartButtonClicked() {
        if (isTracking) {
            stopTracking()
        } else {
            resetStats()
            if (hasLocationPermission()) {
                proceedIfGpsEnabled()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun proceedIfGpsEnabled() {
        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager == null || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.gps_disabled)
                .setPositiveButton(R.string.open_location_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }
        startTracking()
    }

    private fun startTracking() {
        // Lint's permission check can't see through hasLocationPermission()
        // called earlier in a different method, so it flags the call below
        // as possibly unchecked. Re-checking right here also guards the
        // real (if rare) case of the permission being revoked between that
        // earlier check and this call.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            return
        }

        isTracking = true
        startElapsedRealtimeMs = SystemClock.elapsedRealtime()
        sessionStartWallClockMs = System.currentTimeMillis()
        completedStages.clear()
        stageIndex = 1
        stageStartElapsedRealtimeMs = startElapsedRealtimeMs
        stageStartWallClockMs = sessionStartWallClockMs
        stageStartDistanceMeters = 0.0

        binding.startButton.text = getString(R.string.stop)
        binding.startButton.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.accent_stop)
        binding.statusText.setText(R.string.waiting_for_gps)
        binding.nextStageButton.visibility = View.VISIBLE
        updateStageDisplay()

        timerHandler.post(timerRunnable)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopTracking() {
        isTracking = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        timerHandler.removeCallbacks(timerRunnable)
        binding.startButton.text = getString(R.string.start)
        binding.startButton.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.accent)
        binding.nextStageButton.visibility = View.GONE
        binding.stageCard.visibility = View.GONE
        updateOverallAvgSpeedDisplay()

        val allStages = ArrayList(completedStages).apply { add(closeCurrentStage()) }
        startActivity(
            Intent(this, ResultActivity::class.java).apply {
                putParcelableArrayListExtra(ResultActivity.EXTRA_STAGES, allStages)
                putExtra(ResultActivity.EXTRA_SESSION_START_WALL_CLOCK_MS, sessionStartWallClockMs)
                putExtra(ResultActivity.EXTRA_WEIGHT_KG, weightKg)
            }
        )
    }

    private fun resetStats() {
        totalDistanceMeters = 0.0
        lastGoodLocation = null
        speedHistory.clear()
        lastAnnounceElapsedMs = 0L
        completedStages.clear()
        updateDistanceDisplay()
        binding.stageCard.visibility = View.GONE
        binding.nextStageButton.visibility = View.GONE
        binding.avgSpeedValueText.text = ZERO_SPEED_TEXT
        binding.overallAvgSpeedValueText.text = OVERALL_SPEED_PLACEHOLDER
        binding.timeValueText.text = getString(R.string.zero_time)
        binding.statusText.setText(R.string.waiting_for_gps)
    }

    /** Closes the in-progress stage into a [StageResult] snapshot as of right now. */
    private fun closeCurrentStage(): StageResult {
        val now = SystemClock.elapsedRealtime()
        return StageResult(
            index = stageIndex,
            startWallClockMs = stageStartWallClockMs,
            endWallClockMs = System.currentTimeMillis(),
            distanceMeters = totalDistanceMeters - stageStartDistanceMeters,
            durationMs = now - stageStartElapsedRealtimeMs
        )
    }

    /** "다음 단계": closes the in-progress stage and starts a new one from here. */
    private fun advanceStage() {
        if (!isTracking) return
        val closed = closeCurrentStage()
        completedStages.add(closed)
        stageIndex++
        stageStartElapsedRealtimeMs = SystemClock.elapsedRealtime()
        stageStartWallClockMs = System.currentTimeMillis()
        stageStartDistanceMeters = totalDistanceMeters
        updateStageDisplay()
    }

    private fun updateStageDisplay() {
        if (!isTracking) {
            binding.stageCard.visibility = View.GONE
            return
        }
        binding.stageCard.visibility = View.VISIBLE

        val prev = completedStages.lastOrNull()
        binding.prevStageRow.visibility = if (prev != null) View.VISIBLE else View.GONE
        if (prev != null) {
            binding.prevStageStartText.text = stageTimeFormat.format(Date(prev.startWallClockMs))
            binding.prevStageEndText.text = stageTimeFormat.format(Date(prev.endWallClockMs))
            binding.prevStageDurationText.text = RunFormat.duration(prev.durationMs)
            binding.prevStageDistanceText.text = RunFormat.distanceKmValue(prev.distanceMeters)
            binding.prevStageSpeedText.text = RunFormat.speedKmhValue(prev.distanceMeters, prev.durationMs)
        }

        val currentDurationMs = SystemClock.elapsedRealtime() - stageStartElapsedRealtimeMs
        val currentDistanceMeters = totalDistanceMeters - stageStartDistanceMeters
        binding.currentStageStartText.text = stageTimeFormat.format(Date(stageStartWallClockMs))
        binding.currentStageDurationText.text = RunFormat.duration(currentDurationMs)
        binding.currentStageDistanceText.text = RunFormat.distanceKmValue(currentDistanceMeters)
        binding.currentStageSpeedText.text = RunFormat.speedKmhValue(currentDistanceMeters, currentDurationMs)
    }

    /**
     * Turns raw GPS fixes into a monotonically increasing distance total.
     *
     * Two GPS-specific error sources would otherwise inflate the total: (1)
     * position jitter while standing still, which [Location.distanceTo] would
     * happily report as a few meters of "movement" every single fix, and (2)
     * occasional multipath/atmospheric glitches that report an impossible
     * jump. Low-accuracy fixes are dropped outright; among the rest, only
     * movement past a small noise floor is accepted, and only if the implied
     * speed is physically plausible for a runner - otherwise the fix is
     * discarded without disturbing the last known-good anchor point, so a
     * single bad sample can't drag the next real one along with it.
     */
    private fun handleNewLocation(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) {
            binding.statusText.text = getString(R.string.low_accuracy, location.accuracy.toInt())
            return
        }

        val previous = lastGoodLocation
        if (previous == null) {
            lastGoodLocation = location
            binding.statusText.text = ""
            return
        }

        val deltaMeters = previous.distanceTo(location)
        val deltaSeconds = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
        // A non-positive delta means this fix arrived out of chronological
        // order relative to the last one (seen in practice with some
        // chipsets' fused-location batching). Treating that as "0 speed"
        // used to let it slide straight past the speed-plausibility check
        // below, so a large position jump with a bogus timestamp could get
        // added to the total in an instant - producing average speeds well
        // above anything a person (or often a car) could actually reach.
        if (deltaSeconds <= 0) return
        val speedMps = deltaMeters / deltaSeconds
        if (speedMps > MAX_REALISTIC_SPEED_MPS) return

        binding.statusText.text = ""
        if (deltaMeters >= MIN_DISTANCE_METERS) {
            totalDistanceMeters += deltaMeters
            lastGoodLocation = location
            updateDistanceDisplay()
        }
    }

    private fun updateDistanceDisplay() {
        binding.distanceValueText.text =
            String.format(Locale.US, "%.2f", totalDistanceMeters / 1000.0)
    }

    private fun updateTimeDisplay() {
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        binding.timeValueText.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun updateCurrentTimeDisplay() {
        binding.currentTimeText.text = clockFormat.format(Date())
    }

    /**
     * Keeps a rolling ~60-second history of (time, cumulative distance) and
     * reports the average speed across it, recomputed on every tick.
     *
     * The previous approach compared two fixed snapshots exactly 60 seconds
     * apart and only updated once a minute. Because distance itself only
     * accumulates in bursts (each GPS fix has to clear [MIN_DISTANCE_METERS]
     * before it counts, to reject stationary jitter), which of those bursts
     * landed inside a given 60-second snapshot was mostly luck - one window
     * could catch a burst right at its edge and read far too high, the next
     * could catch none and read 0, even though the runner's actual pace
     * barely changed. A continuously-sliding window doesn't eliminate the
     * burstiness, but every reading now averages over a nearly-constant
     * ~60-second span instead of comparing two arbitrary instants, so the
     * burstiness mostly cancels out instead of swinging the result.
     */
    private fun recordDistanceSampleAndUpdateAvgSpeed() {
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        speedHistory.addLast(elapsedMs to totalDistanceMeters)
        // Keep one entry at-or-before the 60s mark as the trailing-window anchor.
        while (speedHistory.size > 1 && elapsedMs - speedHistory[1].first >= MINUTE_MS) {
            speedHistory.removeFirst()
        }

        val (oldestElapsedMs, oldestDistanceMeters) = speedHistory.first()
        val windowSeconds = (elapsedMs - oldestElapsedMs) / 1000.0
        // Too short a window makes the result noise-dominated (a single GPS
        // burst can imply an absurd speed); wait for a more stable baseline.
        if (windowSeconds < MIN_SPEED_WINDOW_SECONDS) return

        val windowDistanceMeters = totalDistanceMeters - oldestDistanceMeters
        val speedKmh = (windowDistanceMeters / windowSeconds) * 3.6
        binding.avgSpeedValueText.text = String.format(Locale.US, "%.1f km/h", speedKmh)
    }

    /** Distance/time over the whole tracked run, shown once the run is stopped. */
    private fun updateOverallAvgSpeedDisplay() {
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        val elapsedHours = elapsedMs / 3_600_000.0
        val distanceKm = totalDistanceMeters / 1000.0
        val speedKmh = if (elapsedHours > 0) distanceKm / elapsedHours else 0.0
        binding.overallAvgSpeedValueText.text = String.format(Locale.US, "%.1f km/h", speedKmh)
    }

    /** Every 10 minutes, read out cumulative distance and the since-start average speed. */
    private fun checkTenMinuteAnnouncement() {
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        if (elapsedMs - lastAnnounceElapsedMs < ANNOUNCE_INTERVAL_MS) return
        lastAnnounceElapsedMs = elapsedMs

        val distanceKm = totalDistanceMeters / 1000.0
        val elapsedHours = elapsedMs / 3_600_000.0
        val avgSpeedKmh = if (elapsedHours > 0) distanceKm / elapsedHours else 0.0
        speak(getString(R.string.progress_announcement, distanceKm, avgSpeedKmh))
    }

    /**
     * Speaks [text] without stepping on whatever else is already playing: focus
     * is requested as transient+"may duck" rather than exclusive, which asks
     * other apps (a music player, say) to briefly lower their volume instead
     * of pausing, and is released the moment speech finishes so they return to
     * full volume immediately.
     */
    private fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady) return
        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        engine.setAudioAttributes(voiceAudioAttributes)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, ANNOUNCEMENT_UTTERANCE_ID)
    }

    private fun loadSavedWeightKg(): Double {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getFloat(KEY_WEIGHT_KG, DEFAULT_WEIGHT_KG.toFloat()).toDouble()
    }

    private fun persistWeightFromInput() {
        val typed = binding.weightInput.text.toString().toDoubleOrNull()
        weightKg = if (typed != null && typed in MIN_WEIGHT_KG..MAX_WEIGHT_KG) typed else DEFAULT_WEIGHT_KG
        binding.weightInput.setText(weightKg.toInt().toString())
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(KEY_WEIGHT_KG, weightKg.toFloat())
            .apply()
    }

    companion object {
        // Sub-1-second fixes cut the straight-line chord-cutting error on
        // curves/corners: each fix only replaces the true curved path with a
        // straight segment out to the next fix, so more fixes per turn means
        // shorter, more path-hugging segments.
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L
        private const val TIMER_TICK_MS = 500L
        private const val CLOCK_TICK_MS = 1000L
        private const val MINUTE_MS = 60_000L
        private const val MIN_SPEED_WINDOW_SECONDS = 15.0
        private const val ANNOUNCE_INTERVAL_MS = 10 * MINUTE_MS
        private const val ANNOUNCEMENT_UTTERANCE_ID = "progress_announcement"
        private const val ZERO_SPEED_TEXT = "0.0 km/h"
        private const val OVERALL_SPEED_PLACEHOLDER = "-"

        // GPS fixes reporting worse than this are noise, not position.
        // Tightened alongside MIN_DISTANCE_METERS below: a lower noise floor
        // only stays safe against stationary jitter if the fixes it's
        // applied to are themselves reasonably precise.
        private const val MAX_ACCURACY_METERS = 15f

        // Below this, consecutive fixes while stationary look like "movement"
        // purely from GPS jitter; only count steps past this noise floor.
        // Lowered from 3m now that MAX_ACCURACY_METERS is tighter - catches
        // real movement sooner (less of a run's final few meters gets
        // dropped as an uncounted sub-threshold tail) at the cost of letting
        // slightly more stationary jitter through.
        private const val MIN_DISTANCE_METERS = 1.5f

        // ~43 km/h - well above sustainable running speed, so anything faster
        // is a GPS glitch (multipath/atmospheric jump), not a real step.
        private const val MAX_REALISTIC_SPEED_MPS = 12.0

        private const val PREFS_NAME = "runner_prefs"
        private const val KEY_WEIGHT_KG = "weight_kg"
        private const val DEFAULT_WEIGHT_KG = 65.0
        private const val MIN_WEIGHT_KG = 20.0
        private const val MAX_WEIGHT_KG = 250.0
    }
}
