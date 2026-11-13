package com.mathieu.madtrainer.run

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.mathieu.madtrainer.data.RunEngine
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutStep
import com.mathieu.madtrainer.data.formatDistance
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.data.spokenDuration
import com.mathieu.madtrainer.data.spokenPace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

data class RunState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long = 0,
    val elapsed: Int = 0,
    val distanceM: Double = 0.0,
    val currentPace: Int? = null,
    val avgPace: Int? = null,
    val hr: Int? = null,
    val gpsFix: Boolean = false,
    val gpsAccuracy: Float? = null,
    val workout: Workout? = null,
    val stepIndex: Int = 0,
    val stepElapsed: Int = 0,
    val stepDistance: Double = 0.0,
    val finished: Boolean = false,
    val samples: List<Sample> = emptyList(),
) {
    val step: WorkoutStep? get() = workout?.steps?.getOrNull(stepIndex)
}

/**
 * Suivi d'une sortie running : GPS du téléphone, cardio de la montre, étapes guidées à la voix.
 * Singleton d'application : continue de tourner écran éteint grâce à [RunService].
 */
@SuppressLint("MissingPermission")
object RunTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ticker: Job? = null

    /** Fournisseurs injectés par l'app. */
    var heartRate: () -> Int? = { null }
    var maxHr: () -> Int = { 180 }
    var weightKg: () -> Double = { 75.0 }
    var onFinished: (SessionRecord) -> Unit = {}

    // GPS
    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private val recentPoints = ArrayDeque<Pair<Long, Double>>() // (temps ms, distance cumulée)
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // Annonces
    private var nextKmAnnounce = 1000.0
    private var lastGuidanceAt = 0L
    private var oneMinuteWarned = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            _state.value = _state.value.copy(gpsFix = false)
            speak("GPS désactivé")
        }
    }

    /** Démarre la localisation (avant le départ, pour avoir un point GPS prêt). */
    fun warmUpGps() {
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        }
        if (tts == null) {
            tts = TextToSpeech(app) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.FRANCE
            }
        }
    }

    fun stopGps() {
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.removeUpdates(locationListener) }
        _state.value = _state.value.copy(gpsFix = false)
    }

    private fun onLocation(loc: Location) {
        val accurate = loc.hasAccuracy() && loc.accuracy <= 25f
        _state.value = _state.value.copy(gpsFix = accurate, gpsAccuracy = if (loc.hasAccuracy()) loc.accuracy else null)
        if (!accurate) return
        val s = _state.value
        lastLat = loc.latitude
        lastLon = loc.longitude
        val prev = lastFix
        val now = System.currentTimeMillis()
        if (s.running && !s.paused && prev != null) {
            val d = prev.distanceTo(loc).toDouble()
            val dt = (now - lastFixAt) / 1000.0
            // Filtre : pas de saut > 12 m/s (43 km/h), ni de micro-bruit à l'arrêt
            if (dt > 0 && d / dt < 12.0 && d >= 1.5) {
                _state.value = s.copy(distanceM = s.distanceM + d, stepDistance = s.stepDistance + d)
                recentPoints.addLast(now to _state.value.distanceM)
                while (recentPoints.size > 1 && now - recentPoints.first().first > 30_000) recentPoints.removeFirst()
                lastFix = loc
                lastFixAt = now
            }
        } else {
            lastFix = loc
            lastFixAt = now
        }
    }

    // ------------------------------------------------------------------ séance

    fun start(workout: Workout?) {
        if (_state.value.running) return
        warmUpGps()
        recentPoints.clear()
        nextKmAnnounce = 1000.0
        oneMinuteWarned = false
        lastFix = null
        _state.value = RunState(
            running = true,
            startedAt = System.currentTimeMillis(),
            workout = workout,
            gpsFix = _state.value.gpsFix,
        )
        ContextCompat.startForegroundService(app, Intent(app, RunService::class.java))
        speak(
            if (workout == null) "C'est parti. Bonne course !"
            else "C'est parti pour ${workout.name}.",
        )
        workout?.steps?.firstOrNull()?.let { announceStep(it, 0) }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    fun togglePause() {
        val s = _state.value
        if (!s.running) return
        _state.value = s.copy(paused = !s.paused)
        lastFix = null
        recentPoints.clear()
        speak(if (s.paused) "Reprise" else "Pause")
    }

    fun skipStep() {
        val s = _state.value
        val w = s.workout ?: return
        advanceStep(s, w)
    }

    fun stop(save: Boolean) {
        val s = _state.value
        if (!s.running) return
        ticker?.cancel()
        app.stopService(Intent(app, RunService::class.java))
        stopGps()
        _state.value = s.copy(running = false, finished = true)
        if (save && s.elapsed >= 30) {
            val rec = buildRecord(s)
            speak("Séance terminée. ${formatDistance(s.distanceM, spoken = true)} en ${spokenDuration(s.elapsed)}.")
            onFinished(rec)
        }
    }

    fun reset() {
        _state.value = RunState(gpsFix = _state.value.gpsFix)
    }

    private fun tick() {
        val s = _state.value
        if (!s.running || s.paused) return
        val now = System.currentTimeMillis()
        val hr = heartRate()
        val current = if (recentPoints.size >= 2) {
            val (t0, d0) = recentPoints.first()
            val (t1, d1) = recentPoints.last()
            RunEngine.pace(d1 - d0, (t1 - t0) / 1000.0)
        } else null
        val e = s.elapsed + 1
        val sample = Sample(
            t = e,
            hr = hr,
            distanceM = s.distanceM.roundToInt(),
            speed = current?.let { 3600.0 / it },
            paceSecPerKm = current,
            lat = lastLat,
            lon = lastLon,
        )
        var ns = s.copy(
            elapsed = e,
            stepElapsed = s.stepElapsed + 1,
            hr = hr,
            currentPace = current,
            avgPace = RunEngine.pace(s.distanceM, e.toDouble()),
            samples = s.samples + sample,
        )
        _state.value = ns

        // Annonce de chaque kilomètre
        if (ns.distanceM >= nextKmAnnounce) {
            val km = (nextKmAnnounce / 1000).toInt()
            nextKmAnnounce += 1000.0
            vibrate(1)
            speak(
                "Kilomètre $km. " + (ns.avgPace?.let { "Allure moyenne ${spokenPace(it)}." } ?: "") +
                    (hr?.let { " Cardio $it." } ?: ""),
            )
        }

        val w = ns.workout ?: return
        val step = ns.step ?: return
        if (RunEngine.stepDone(step, ns.stepElapsed, ns.stepDistance)) {
            advanceStep(ns, w)
            return
        }
        // Prévenir 1 minute avant la fin d'une longue étape en durée
        if (step.distanceM == null && step.durationSec >= 180 && !oneMinuteWarned && step.durationSec - ns.stepElapsed == 60) {
            oneMinuteWarned = true
            speak("Encore une minute")
        }
        // Consignes « accélère / ralentis » au plus toutes les 60 s, après 30 s dans l'étape
        if (ns.stepElapsed >= 30 && now - lastGuidanceAt > 60_000) {
            when (RunEngine.guidance(step, current, hr, maxHr())) {
                RunEngine.Guidance.FASTER -> {
                    lastGuidanceAt = now
                    vibrate(2)
                    speak(
                        if (step.mode == StepMode.PACE) "Accélère un peu. Allure ${current?.let { spokenPace(it) } ?: ""}, objectif ${spokenPace(step.target.roundToInt())}."
                        else "Tu peux accélérer, cardio $hr.",
                    )
                }
                RunEngine.Guidance.SLOWER -> {
                    lastGuidanceAt = now
                    vibrate(2)
                    speak(
                        if (step.mode == StepMode.PACE) "Ralentis un peu. Allure ${current?.let { spokenPace(it) } ?: ""}, objectif ${spokenPace(step.target.roundToInt())}."
                        else "Ralentis, cardio $hr, trop haut pour la zone ${step.target.roundToInt()}.",
                    )
                }
                RunEngine.Guidance.NONE -> Unit
            }
        }
    }

    private fun advanceStep(s: RunState, w: Workout) {
        val next = s.stepIndex + 1
        if (next >= w.steps.size) {
            stop(save = true)
            return
        }
        oneMinuteWarned = false
        lastGuidanceAt = System.currentTimeMillis()
        _state.value = s.copy(stepIndex = next, stepElapsed = 0, stepDistance = 0.0)
        announceStep(w.steps[next], next)
    }

    private fun announceStep(step: WorkoutStep, index: Int) {
        vibrate(3)
        val w = _state.value.workout
        val count = w?.steps?.size ?: 0
        speak(
            "Étape ${index + 1} sur $count. ${step.name}. " +
                "${RunEngine.stepLengthText(step, spoken = true)}, ${RunEngine.targetText(step, maxHr(), spoken = true)}.",
        )
    }

    private fun buildRecord(s: RunState): SessionRecord {
        val hrs = s.samples.mapNotNull { it.hr }.filter { it > 0 }
        return SessionRecord(
            startedAt = s.startedAt,
            durationSec = s.elapsed,
            workoutName = s.workout?.name ?: "Course libre",
            distanceM = s.distanceM.roundToInt(),
            kcal = RunEngine.runKcal(s.distanceM, weightKg()),
            avgHr = if (hrs.isEmpty()) null else hrs.average().roundToInt(),
            maxHr = hrs.maxOrNull(),
            samples = s.samples,
            sport = Sport.RUNNING,
            avgPaceSecPerKm = RunEngine.pace(s.distanceM, s.elapsed.toDouble()),
        )
    }

    // ------------------------------------------------------------------ voix & vibrations

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "run-${System.nanoTime()}")
    }

    /** 1 = court (km), 2 = double (consigne), 3 = long (changement d'étape) */
    private fun vibrate(kind: Int) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
        val effect = when (kind) {
            1 -> VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            2 -> VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1)
            else -> VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { v?.vibrate(effect) }
    }

    /** Texte de la notification de suivi. */
    fun notificationText(): String {
        val s = _state.value
        return "${formatDuration(s.elapsed)} · ${formatDistance(s.distanceM)}" +
            (s.avgPace?.let { " · ${com.mathieu.madtrainer.data.formatPace(it)}/km" } ?: "") +
            (s.hr?.let { " · $it bpm" } ?: "")
    }
}
