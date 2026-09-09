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

    // The last CONFIRMED point (anchor) and a fix that's arrived since but
    // hasn't been judged yet - see handleNewLocation() for why confirmation
    // needs to wait for the fix after it.
    private var lastGoodLocation: Location? = null
    private var pendingLocation: Location? = null

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
        flushPendingLocation()
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
        pendingLocation = null
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
     * Low-accuracy fixes are dropped outright. Among the rest, a fix is
     * never trusted the moment it arrives - it's held as [pendingLocation]
     * until the *next* fix comes in, and only then judged against the path
     * from [lastGoodLocation] (the last confirmed point) through it to that
     * next fix. A real multipath/atmospheric spike bulges out from the true
     * path and then snaps back, so the detour it forces - (anchor→pending)
     * + (pending→next), compared to the direct anchor→next distance - is
     * far longer than the direct route. Genuine movement, whether a walk or
     * a sprint or a car, stays close to a straight line over one fix
     * interval, so its detour ratio stays near 1 regardless of how fast it
     * actually is. This is what lets speed be judged by consistency with
     * neighboring fixes instead of a flat per-fix speed ceiling, which had
     * to be tuned low enough to catch jitter that it also caught anything
     * faster than a run.
     *
     * Below the noise floor ([MIN_DISTANCE_METERS]), confirmed movement
     * still isn't counted, nor does it advance the anchor - only pure GPS
     * jitter while standing still would otherwise report a few meters of
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

        val pending = pendingLocation
        if (pending == null) {
            pendingLocation = location
            return
        }

        // Out-of-chronological-order fix (seen in practice with some
        // chipsets' fused-location batching) - wait for a properly ordered
        // one rather than judging pending against a bogus interval.
        if (location.elapsedRealtimeNanos <= pending.elapsedRealtimeNanos) return

        val anchorToPending = anchor.distanceTo(pending)
        val pendingToNext = pending.distanceTo(location)
        val anchorToNext = anchor.distanceTo(location)
        val detourRatio = (anchorToPending + pendingToNext) / anchorToNext.coerceAtLeast(MIN_DETOUR_DENOMINATOR_METERS)

        val anchorToPendingSeconds =
            (pending.elapsedRealtimeNanos - anchor.elapsedRealtimeNanos) / 1_000_000_000.0
        val impliedSpeedMps = if (anchorToPendingSeconds > 0) anchorToPending / anchorToPendingSeconds else Double.MAX_VALUE

        // pending becomes the next round's anchor candidate either way -
        // confirmed or rejected, `location` is now the freshest fix we have.
        pendingLocation = location

        val looksLikeRealMovement = detourRatio <= MAX_DETOUR_RATIO && impliedSpeedMps <= ABSOLUTE_MAX_SPEED_MPS
        if (!looksLikeRealMovement) return

        if (anchorToPending >= MIN_DISTANCE_METERS) {
            totalDistanceMeters += anchorToPending
            lastGoodLocation = pending
            updateDistanceDisplay()
        }
    }

    /**
     * The last fix of a run never gets a follow-up fix to confirm it against,
     * so without this its distance would just be silently dropped. Since
     * we're finalizing anyway, commit it straight against the noise floor
     * instead of waiting for a detour-ratio judgment that will never come.
     */
    private fun flushPendingLocation() {
        val anchor = lastGoodLocation ?: return
        val pending = pendingLocation ?: return
        val distance = anchor.distanceTo(pending)
        if (distance >= MIN_DISTANCE_METERS) {
            totalDistanceMeters += distance
            lastGoodLocation = pending
        }
        pendingLocation = null
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

        // How much longer the anchor→pending→next path can be than the
        // direct anchor→next distance before pending is treated as a
        // multipath/atmospheric spike rather than real movement. A real
        // spike bulges out and snaps back, roughly doubling (or worse) the
        // path length for that one fix; genuine movement - at a walk, a
        // sprint, or driving - stays close to a straight line over a single
        // fix interval, so its ratio sits near 1 regardless of speed. This
        // is what replaced a flat per-fix speed ceiling: that ceiling had to
        // be tuned low enough to reject jitter that it also rejected
        // anything faster than a run (e.g. a car). Needs real-world tuning
        // once there's outdoor GPS data to check it against.
        private const val MAX_DETOUR_RATIO = 1.3

        // Guards the detour-ratio math against a near-zero anchor→next
        // distance (anchor and next fix essentially the same point) turning
        // a real spike-and-snap-back into a division by ~0 instead of the
        // very large ratio it should read as.
        private const val MIN_DETOUR_DENOMINATOR_METERS = 1.0f

        // A last-resort sanity ceiling, not a running-speed limit like the
        // per-fix check it replaced: catches only fixes so far off (e.g. a
        // GPS teleport from a timestamp glitch) that even two consecutive
        // bad fixes agreeing with each other would otherwise pass the
        // detour-ratio check. 300 km/h.
        private const val ABSOLUTE_MAX_SPEED_MPS = 83.3

        private const val PREFS_NAME = "runner_prefs"
        private const val KEY_WEIGHT_KG = "weight_kg"
        private const val DEFAULT_WEIGHT_KG = 65.0
        private const val MIN_WEIGHT_KG = 20.0
        private const val MAX_WEIGHT_KG = 250.0
    }
}
