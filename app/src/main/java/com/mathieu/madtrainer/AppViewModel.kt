package com.mathieu.madtrainer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.mathieu.madtrainer.ble.BikeManager
import com.mathieu.madtrainer.ble.HeartRateManager
import com.mathieu.madtrainer.data.Repository
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.SettingsStore
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.StepPosition
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutEngine
import com.mathieu.madtrainer.data.WorkoutStep
import com.mathieu.madtrainer.update.UpdateState
import com.mathieu.madtrainer.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiveSession(
    val running: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long = 0L,
    val elapsed: Int = 0,
    val workout: Workout? = null,
    val position: StepPosition? = null,
    val samples: List<Sample> = emptyList(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settingsStore = SettingsStore(app)
    val repo = Repository(app)
    val bike = BikeManager(app, settingsStore)
    val hr = HeartRateManager(app, settingsStore, onBpm = { bike.setExternalHeartRate(it) }, log = { bike.log(it) })

    private val _session = MutableStateFlow(LiveSession())
    val session: StateFlow<LiveSession> = _session

    /** Dernière séance terminée, pour afficher le résumé. */
    private val _lastFinished = MutableStateFlow<SessionRecord?>(null)
    val lastFinished: StateFlow<SessionRecord?> = _lastFinished

    private var ticker: Job? = null
    private var appliedStep = -1

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    init {
        viewModelScope.launch { repo.load() }
        checkForUpdate()
        reconnectHeartRate()
    }

    // ------------------------------------------------------------ mises à jour

    fun checkForUpdate() {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = runCatching { Updater.check() }.fold(
                onSuccess = { info -> if (info != null) UpdateState.Available(info) else UpdateState.UpToDate },
                onFailure = { UpdateState.Error(it.message ?: "Vérification impossible") },
            )
        }
    }

    /** Télécharge puis lance l'installeur Android. */
    fun downloadAndInstall() {
        val app = getApplication<Application>()
        val info = when (val st = _update.value) {
            is UpdateState.Available -> st.info
            is UpdateState.ReadyToInstall -> {
                installUpdate(st.file)
                return
            }
            else -> return
        }
        _update.value = UpdateState.Downloading(info, 0f)
        viewModelScope.launch {
            runCatching {
                Updater.download(app, info) { p -> _update.value = UpdateState.Downloading(info, p) }
            }.onSuccess { file ->
                _update.value = UpdateState.ReadyToInstall(info, file)
                installUpdate(file)
            }.onFailure {
                _update.value = UpdateState.Error(it.message ?: "Téléchargement impossible")
            }
        }
    }

    private fun installUpdate(file: java.io.File) {
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            // Android demande d'autoriser l'app une seule fois ; revenir ensuite et appuyer sur Installer
            Updater.openInstallPermissionSettings(app)
        } else {
            Updater.install(app, file)
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
    }

    // ------------------------------------------------------------ séance

    fun startSession(workout: Workout?) {
        if (_session.value.running) return
        _lastFinished.value = null
        appliedStep = -1
        _session.value = LiveSession(
            running = true,
            startedAt = System.currentTimeMillis(),
            workout = workout,
            position = workout?.let { WorkoutEngine.position(it, 0) },
        )
        bike.startMachine()
        workout?.steps?.firstOrNull()?.let { applyStep(0, it) }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    fun togglePause() {
        val s = _session.value
        if (!s.running) return
        if (s.paused) {
            bike.startMachine()
            // ré-appliquer la cible de l'étape courante
            val w = s.workout
            val p = s.position
            if (w != null && p != null && !p.finished) applyStep(p.index, w.steps[p.index])
        } else {
            bike.pauseMachine()
        }
        _session.value = s.copy(paused = !s.paused)
    }

    /** Passe directement à l'étape suivante du programme. */
    fun skipStep() {
        val s = _session.value
        val w = s.workout ?: return
        val p = s.position ?: return
        val nextStart = w.steps.take(p.index + 1).sumOf { it.durationSec }
        _session.value = s.copy(elapsed = nextStart - 1)
    }

    fun stopSession(save: Boolean) {
        val s = _session.value
        if (!s.running) return
        ticker?.cancel()
        ticker = null
        bike.stopMachine()
        _session.value = LiveSession()
        if (save && s.elapsed >= 10) {
            val rec = WorkoutEngine.summarize(s.startedAt, s.elapsed, s.workout?.name, s.samples)
            _lastFinished.value = rec
            viewModelScope.launch { repo.saveSession(rec) }
        }
    }

    fun dismissSummary() {
        _lastFinished.value = null
    }

    private fun tick() {
        val s = _session.value
        if (!s.running || s.paused) return
        val e = s.elapsed + 1
        val m = bike.metrics.value
        val sample = Sample(
            t = e,
            power = m.powerW,
            cadence = m.cadence,
            speed = m.speedKmh,
            hr = m.heartRate,
            resistance = m.resistanceRaw?.let { bike.rawToLevel(it) },
            distanceM = m.distanceM,
            kcal = m.totalKcal,
        )
        var pos: StepPosition? = null
        val w = s.workout
        if (w != null) {
            pos = WorkoutEngine.position(w, e)
            if (pos.finished) {
                _session.value = s.copy(elapsed = e, position = pos, samples = s.samples + sample)
                stopSession(save = true)
                return
            }
            if (pos.index != appliedStep) applyStep(pos.index, w.steps[pos.index])
        }
        _session.value = s.copy(elapsed = e, position = pos, samples = s.samples + sample)
    }

    private fun applyStep(index: Int, step: WorkoutStep) {
        appliedStep = index
        when (step.mode) {
            StepMode.RESISTANCE -> bike.setResistanceLevel(step.target)
            StepMode.POWER -> bike.setTargetPower(step.target.toInt())
            StepMode.FREE -> Unit
        }
    }

    // ------------------------------------------------------------ programmes / historique

    fun saveWorkout(w: Workout) = viewModelScope.launch { repo.saveWorkout(w) }
    fun deleteWorkout(id: String) = viewModelScope.launch { repo.deleteWorkout(id) }
    fun deleteSession(id: String) = viewModelScope.launch { repo.deleteSession(id) }

    /** Se reconnecte automatiquement à la montre / ceinture mémorisée si les autorisations sont déjà accordées. */
    fun reconnectHeartRate() {
        val app = getApplication<Application>()
        val s = settingsStore.settings.value
        val addr = s.hrAddress ?: return
        val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(app, perm) != PackageManager.PERMISSION_GRANTED) return
        if (!bike.isBluetoothEnabled) return
        runCatching { hr.connect(addr, s.hrName) }
    }

    override fun onCleared() {
        hr.release()
        bike.release()
        super.onCleared()
    }
}
