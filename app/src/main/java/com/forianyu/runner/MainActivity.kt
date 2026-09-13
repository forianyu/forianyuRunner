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

    // A short-horizon smoothed pace, in m/s, used to sanity-check each new
    // fix against how fast the last few fixes actually moved - see
    // handleNewLocation() for why this replaced a flat speed ceiling.
    private var recentPaceMps = 0.0

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
            // Belt-and-suspenders: stopTracking() already removes this
            // callback, but if a stray chain ever survives it (e.g. a
            // second MainActivity instance from relaunching the app while
            // an old one was still tracking in the background), this stops
            // it from doing anything - including firing the 10-minute
            // announcement - the moment tracking is no longer on.
            if (!isTracking) return
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

        // Guards against ever having two overlapping tick chains - a
        // rapid double-tap of the button, or a stale one somehow left
        // over - which is what let the 10-minute announcement fire twice
        // at once.
        timerHandler.removeCallbacks(timerRunnable)
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
        // Cuts off a 10-minute announcement that happened to start right as
        // stop was pressed, rather than letting it keep playing afterward.
        tts?.stop()
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
        recentPaceMps = 0.0
        speedHistory.clear()
        lastAnnounceElapsedMs = 0L
        completedStages.clear()
        updateDistanceDisplay()
        binding.stageCard.visibility = View.GONE
        binding.nextStageButton.visibility = View.GONE
        binding.avgSpeedValueText.text = ZERO_SPEED_TEXT
        binding.avgSpeed5MinValueText.text = ZERO_SPEED_TEXT
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
     * Low-accuracy fixes are dropped outright. Among the rest, every fix is
     * judged against [recentPaceMps] - a smoothed estimate of how fast the
     * last few fixes actually moved - rather than a flat speed ceiling: if
     * the implied speed since the last point is no more than
     * [SPIKE_MARGIN_MPS] above that recent pace, it's accepted as real
     * movement (at a walk, a run, a bike, or driving - whatever that recent
     * pace happens to be) and folded into the pace estimate. If it's far
     * above - a GPS multipath/atmospheric spike - the raw jump is never
     * trusted, but the elapsed time isn't thrown away either: that interval
     * is credited with `recentPaceMps × elapsedSeconds`, i.e. "assume they
     * kept going at their recent pace," so a run through a patch of bad
     * signal degrades gracefully to an estimate instead of either inflating
     * the total (trusting the spike) or stalling it near zero (discarding
     * every fix that looks even a little off, which real GPS noise on a
     * curving path does constantly at ordinary running speeds).
     *
     * Below the noise floor ([MIN_DISTANCE_METERS]), accepted movement still
     * isn't counted, nor does it advance the anchor - only pure GPS jitter
     * while standing still would otherwise report a few meters of
     * "movement" on every fix.
     */
    private fun handleNewLocation(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) {
            binding.statusText.text = getString(R.string.low_accuracy, location.accuracy.toInt())
            return
        }
        binding.statusText.text = ""

        val anchor = lastGoodLocation
        if (anchor == null) {
            lastGoodLocation = location
            return
        }

        val elapsedSeconds = (location.elapsedRealtimeNanos - anchor.elapsedRealtimeNanos) / 1_000_000_000.0
        // Out-of-chronological-order fix (seen in practice with some
        // chipsets' fused-location batching) - skip it rather than divide by
        // a non-positive interval.
        if (elapsedSeconds <= 0) return

        val rawDistance = anchor.distanceTo(location)
        val impliedSpeedMps = rawDistance / elapsedSeconds

        if (impliedSpeedMps <= recentPaceMps + SPIKE_MARGIN_MPS) {
            recentPaceMps = recentPaceMps + PACE_EMA_ALPHA * (impliedSpeedMps - recentPaceMps)
            if (rawDistance >= MIN_DISTANCE_METERS) {
                totalDistanceMeters += rawDistance
                lastGoodLocation = location
                updateDistanceDisplay()
            }
        } else {
            // A spike: don't trust the jump, but don't lose the elapsed time
            // either - and don't let this reading drag the pace estimate
            // down or up, since it's exactly what we don't trust.
            totalDistanceMeters += recentPaceMps * elapsedSeconds
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
     * Keeps a rolling ~5-minute history of (time, cumulative distance) and
     * reports the average speed over both a 1-minute and a 5-minute trailing
     * window from it, recomputed on every tick.
     *
     * The previous approach compared two fixed snapshots exactly 60 seconds
     * apart and only updated once a minute. Because distance itself only
     * accumulates in bursts (each GPS fix has to clear [MIN_DISTANCE_METERS]
     * before it counts, to reject stationary jitter), which of those bursts
     * landed inside a given 60-second snapshot was mostly luck - one window
     * could catch a burst right at its edge and read far too high, the next
     * could catch none and read 0, even though the runner's actual pace
     * barely changed. A continuously-sliding window doesn't eliminate the
     * burstiness, but every reading now averages over a nearly-constant span
     * instead of comparing two arbitrary instants, so the burstiness mostly
     * cancels out instead of swinging the result. The 1-minute window still
     * jumps around more than most runners want from a glance-at-your-wrist
     * number; the 5-minute one is added alongside it as a steadier read on
     * pace, not a replacement.
     */
    private fun recordDistanceSampleAndUpdateAvgSpeed() {
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        speedHistory.addLast(elapsedMs to totalDistanceMeters)
        // Keep only what the longer (5-minute) window needs; the 1-minute
        // window is found within the same retained history below.
        while (speedHistory.size > 1 && elapsedMs - speedHistory[1].first >= FIVE_MINUTE_MS) {
            speedHistory.removeFirst()
        }

        binding.avgSpeedValueText.text = windowedSpeedText(elapsedMs, MINUTE_MS)
        binding.avgSpeed5MinValueText.text = windowedSpeedText(elapsedMs, FIVE_MINUTE_MS)
    }

    /** Average speed over the trailing [windowMs] found within [speedHistory]. */
    private fun windowedSpeedText(nowElapsedMs: Long, windowMs: Long): String {
        // The newest retained sample that's still at-or-before the window's
        // start, i.e. the anchor of the trailing window (falls back to the
        // very first sample while the run itself is younger than the window).
        val (anchorElapsedMs, anchorDistanceMeters) =
            speedHistory.lastOrNull { nowElapsedMs - it.first >= windowMs } ?: speedHistory.first()

        val windowSeconds = (nowElapsedMs - anchorElapsedMs) / 1000.0
        // Too short a window makes the result noise-dominated (a single GPS
        // burst can imply an absurd speed); wait for a more stable baseline.
        if (windowSeconds < MIN_SPEED_WINDOW_SECONDS) return ZERO_SPEED_TEXT

        val windowDistanceMeters = totalDistanceMeters - anchorDistanceMeters
        val speedKmh = (windowDistanceMeters / windowSeconds) * 3.6
        return String.format(Locale.US, "%.1f km/h", speedKmh)
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
        private const val FIVE_MINUTE_MS = 5 * MINUTE_MS
        private const val MIN_SPEED_WINDOW_SECONDS = 15.0
        private const val ANNOUNCE_INTERVAL_MS = 10 * MINUTE_MS
        private const val ANNOUNCEMENT_UTTERANCE_ID = "progress_announcement"
        private const val ZERO_SPEED_TEXT = "0.0 km/h"
        private const val OVERALL_SPEED_PLACEHOLDER = "-"

        // GPS fixes reporting worse than this are noise, not position.
        private const val MAX_ACCURACY_METERS = 15f

        // Below this, consecutive fixes while stationary look like "movement"
        // purely from GPS jitter; only count steps past this noise floor.
        private const val MIN_DISTANCE_METERS = 3.0f

        // How far above the recent smoothed pace ([recentPaceMps]) a fix's
        // implied speed can be before it's treated as a GPS spike rather
        // than real acceleration. This is a per-fix-interval allowance, not
        // an absolute speed limit, so it applies the same whether the
        // recent pace is a walk, a run, a bike, or a car: real acceleration
        // (a runner's sprint, a car pulling onto a highway) changes speed by
        // at most a few m/s over one sub-second fix interval, while a
        // multipath/atmospheric spike typically implies tens of m/s more
        // than whatever was actually happening. Needs real-world tuning
        // once there's outdoor GPS data (running, cycling, driving) to
        // check it against.
        private const val SPIKE_MARGIN_MPS = 8.0

        // How quickly recentPaceMps follows genuine speed changes: each
        // accepted fix moves it this fraction of the way from its old value
        // to the new one. Low enough to smooth out fix-to-fix noise, high
        // enough to track a real pace change (speeding up, slowing down)
        // within a couple of seconds.
        private const val PACE_EMA_ALPHA = 0.3

        private const val PREFS_NAME = "runner_prefs"
        private const val KEY_WEIGHT_KG = "weight_kg"
        private const val DEFAULT_WEIGHT_KG = 65.0
        private const val MIN_WEIGHT_KG = 20.0
        private const val MAX_WEIGHT_KG = 250.0
    }
}
