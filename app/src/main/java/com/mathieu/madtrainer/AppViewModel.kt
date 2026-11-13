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
import com.mathieu.madtrainer.ble.ConnState
import com.mathieu.madtrainer.coach.AnthropicClient
import com.mathieu.madtrainer.coach.CoachParser
import com.mathieu.madtrainer.coach.CoachPrompt
import com.mathieu.madtrainer.coach.Equipment
import com.mathieu.madtrainer.coach.SavedPlan
import com.mathieu.madtrainer.coach.UserProfile
import com.mathieu.madtrainer.coach.PainLog
import com.mathieu.madtrainer.coach.PlayerStep
import com.mathieu.madtrainer.coach.SavedStrengthPlan
import com.mathieu.madtrainer.coach.StrengthPlayer
import com.mathieu.madtrainer.coach.StrengthProfile
import com.mathieu.madtrainer.coach.ExerciseCatalog
import com.mathieu.madtrainer.coach.StrengthPrompt
import com.mathieu.madtrainer.coach.StrengthSession
import com.mathieu.madtrainer.strava.StravaCache
import com.mathieu.madtrainer.strava.StravaClient
import com.mathieu.madtrainer.strava.StravaSummary
import android.content.Intent
import android.net.Uri
import com.mathieu.madtrainer.data.Repository
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.SettingsStore
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.StepPosition
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutEngine
import com.mathieu.madtrainer.data.WorkoutStep
import com.mathieu.madtrainer.data.WorkoutConverter
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.ProgressReport
import com.mathieu.madtrainer.data.SessionReport
import com.mathieu.madtrainer.data.VoiceCoach
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.run.RunTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.mathieu.madtrainer.update.UpdateState
import com.mathieu.madtrainer.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface CoachState {
    data object Idle : CoachState
    data object Generating : CoachState
    /** [truncated] : réponse coupée, seules les séances complètes ont été importées. */
    data class Done(val workouts: Int, val truncated: Boolean = false) : CoachState
    data class Error(val message: String) : CoachState
}

sealed interface StravaState {
    data object Idle : StravaState
    data object Working : StravaState
    data class Ok(val message: String) : StravaState
    data class Error(val message: String) : StravaState
}

/** Lecteur guidé d'une séance de renfo. */
data class StrengthPlayerState(
    val session: StrengthSession? = null,
    val steps: List<PlayerStep> = emptyList(),
    val index: Int = 0,
    /** Secondes restantes pour une étape chronométrée ; null pour une étape en répétitions */
    val remaining: Int? = null,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val painBefore: Int = 0,
    val elapsed: Int = 0,
) {
    val step: PlayerStep? get() = steps.getOrNull(index)
}

/** Analyse IA du rapport de séance. */
sealed interface ReportAiState {
    data object Idle : ReportAiState
    data class Working(val sessionId: String) : ReportAiState
    data class Error(val sessionId: String, val message: String) : ReportAiState
}

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

    // ------------------------------------------------------------ coach IA

    private val _coach = MutableStateFlow<CoachState>(CoachState.Idle)
    val coach: StateFlow<CoachState> = _coach

    fun saveProfile(p: UserProfile) = viewModelScope.launch { repo.saveProfile(p) }

    private fun equipment(): Equipment {
        val caps = bike.caps.value
        val range = caps.resistanceRange
        val legacy = caps.legacyDomyos || !caps.hasFtms
        return Equipment(
            resistanceMin = if (range != null && legacy) range.min else 1,
            resistanceMax = if (range != null && legacy) range.max else 15,
            hasHeartRate = hr.state.value == ConnState.CONNECTED || settingsStore.settings.value.hrAddress != null,
        )
    }

    fun buildCoachPrompt(): String {
        val strava = if (settingsStore.settings.value.includeStrava) StravaSummary.describe(repo.strava.value) else null
        // Plan commun aux deux sports (+ jours de renfo réservés)
        return CoachPrompt.buildGlobal(
            repo.profile.value, repo.sessions.value, equipment(),
            otherActivities = strava, strength = repo.strengthProfile.value,
        )
    }

    // ------------------------------------------------------------ Strava

    private val _strava = MutableStateFlow<StravaState>(StravaState.Idle)
    val stravaState: StateFlow<StravaState> = _strava

    /** Ouvre la page d'autorisation Strava ; le retour arrive dans [handleStravaRedirect]. */
    fun connectStrava() {
        val s = settingsStore.settings.value
        if (s.stravaClientId.isBlank() || s.stravaClientSecret.isBlank()) {
            _strava.value = StravaState.Error("Renseigne d'abord le Client ID et le Client Secret de ton application Strava")
            return
        }
        val app = getApplication<Application>()
        app.startActivity(
            Intent(Intent.ACTION_VIEW, StravaClient.authorizeUri(s.stravaClientId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Appelé par MainActivity quand Strava redirige vers madtrainer://localhost/strava?code=… */
    fun handleStravaRedirect(uri: Uri) {
        if (uri.scheme != "madtrainer" || uri.path?.startsWith("/strava") != true) return
        uri.getQueryParameter("error")?.let {
            _strava.value = StravaState.Error("Autorisation Strava refusée")
            return
        }
        val code = uri.getQueryParameter("code") ?: return
        val scope = uri.getQueryParameter("scope").orEmpty()
        if (!scope.contains("activity:read")) {
            _strava.value = StravaState.Error("Coche l'accès aux activités lors de l'autorisation Strava")
            return
        }
        val s = settingsStore.settings.value
        _strava.value = StravaState.Working
        viewModelScope.launch {
            runCatching { StravaClient.exchangeCode(s.stravaClientId, s.stravaClientSecret, code) }
                .onSuccess { r ->
                    settingsStore.update {
                        it.copy(stravaAccess = r.tokens.access_token, stravaRefresh = r.tokens.refresh_token, stravaExpiresAt = r.tokens.expires_at)
                    }
                    repo.saveStrava(repo.strava.value.copy(athleteName = r.athleteName))
                    syncStrava()
                }
                .onFailure { _strava.value = StravaState.Error(it.message ?: "Connexion Strava impossible") }
        }
    }

    private suspend fun validStravaToken(): String {
        val s = settingsStore.settings.value
        if (!s.stravaConnected) error("Strava n'est pas connecté")
        if (s.stravaAccess.isNotBlank() && s.stravaExpiresAt > System.currentTimeMillis() / 1000 + 120) return s.stravaAccess
        val t = StravaClient.refresh(s.stravaClientId, s.stravaClientSecret, s.stravaRefresh)
        settingsStore.update { it.copy(stravaAccess = t.access_token, stravaRefresh = t.refresh_token, stravaExpiresAt = t.expires_at) }
        return t.access_token
    }

    /** Récupère les activités des 90 derniers jours et le poids du profil Strava. */
    fun syncStrava() {
        _strava.value = StravaState.Working
        viewModelScope.launch {
            runCatching {
                val token = validStravaToken()
                val after = System.currentTimeMillis() / 1000 - 90L * 24 * 3600
                val acts = StravaClient.activities(token, after)
                val weight = runCatching { StravaClient.athleteWeight(token) }.getOrNull()
                val zones = runCatching { StravaClient.hrZoneLows(token) }.getOrNull()
                val cache = repo.strava.value.copy(weightKg = weight, lastSync = System.currentTimeMillis(), activities = acts, hrZoneLows = zones)
                repo.saveStrava(cache)
                // Zones déjà reprises de Strava : on les tient à jour
                val p = repo.profile.value
                if (zones != null && p.hrZonesSource == "Strava" && p.hrZoneLows != zones) repo.saveProfile(p.copy(hrZoneLows = zones))
                cache
            }.onSuccess { c ->
                _strava.value = StravaState.Ok("${c.activities.size} activités synchronisées" + if (c.hrZoneLows != null) " · zones cardio récupérées" else "")
            }.onFailure { _strava.value = StravaState.Error(it.message ?: "Synchronisation impossible") }
        }
    }

    fun useStravaWeight() {
        val w = repo.strava.value.weightKg ?: return
        saveProfile(repo.profile.value.copy(weightKg = w))
    }

    /** Utilise les zones cardio de Strava partout dans l'app (séances en zone, pilotage de l'elliptique, coach). */
    fun useStravaHrZones() {
        val z = repo.strava.value.hrZoneLows ?: return
        saveProfile(repo.profile.value.copy(hrZoneLows = z, hrZonesSource = "Strava"))
    }

    /** Revient aux zones calculées sur la FC max. */
    fun resetHrZones() = saveProfile(repo.profile.value.copy(hrZoneLows = null, hrZonesSource = null))

    fun disconnectStrava() {
        settingsStore.update { it.copy(stravaAccess = "", stravaRefresh = "", stravaExpiresAt = 0) }
        viewModelScope.launch { repo.saveStrava(StravaCache()) }
        _strava.value = StravaState.Idle
    }

    /** Importe la réponse de l'IA (collée ou reçue de l'API) : plan + séances dans Programmes. */
    fun importCoachResponse(response: String) {
        val eq = equipment()
        CoachParser.parseDetailed(response).fold(
            onSuccess = { parsed ->
                val plan = parsed.value
                val workouts = CoachParser.toGlobalWorkouts(plan, eq.resistanceMin, eq.resistanceMax)
                viewModelScope.launch {
                    repo.saveGlobalPlan(SavedPlan(System.currentTimeMillis(), plan, response), workouts)
                    _coach.value = CoachState.Done(workouts.size, parsed.truncated)
                }
            },
            onFailure = { _coach.value = CoachState.Error(it.message ?: "Réponse illisible") },
        )
    }

    fun generateWithApi() {
        val s = settingsStore.settings.value
        if (s.anthropicKey.isBlank()) {
            _coach.value = CoachState.Error("Renseigne d'abord ta clé API dans les réglages du coach")
            return
        }
        if (_coach.value == CoachState.Generating) return
        _coach.value = CoachState.Generating
        val prompt = buildCoachPrompt()
        viewModelScope.launch {
            runCatching { AnthropicClient.complete(s.anthropicKey, s.anthropicModel, prompt) }
                .onSuccess { importCoachResponse(it) }
                .onFailure { _coach.value = CoachState.Error(it.message ?: "Échec de l'appel à l'API") }
        }
    }

    fun resetCoachState() {
        _coach.value = CoachState.Idle
    }

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    // ------------------------------------------------------------ renfo

    private val _strengthCoach = MutableStateFlow<CoachState>(CoachState.Idle)
    val strengthCoach: StateFlow<CoachState> = _strengthCoach

    /** Banque d'images d'exercices (assets/exercises.json, domaine public). */
    val exerciseCatalog: ExerciseCatalog by lazy {
        runCatching {
            ExerciseCatalog.parse(getApplication<Application>().assets.open("exercises.json").bufferedReader().use { it.readText() })
        }.getOrElse { ExerciseCatalog(emptyList()) }
    }

    fun saveStrengthProfile(p: StrengthProfile) = viewModelScope.launch { repo.saveStrengthProfile(p) }

    fun buildStrengthPrompt(): String {
        val strava = if (settingsStore.settings.value.includeStrava) StravaSummary.describe(repo.strava.value) else null
        return StrengthPrompt.build(repo.profile.value, repo.strengthProfile.value, repo.sessions.value, repo.painLogs.value, strava,
            imageIds = exerciseCatalog.idsForPrompt(repo.strengthProfile.value.equipment),
        )
    }

    fun importStrengthResponse(response: String) {
        StrengthPrompt.parseDetailed(response).fold(
            onSuccess = { parsed ->
                val plan = parsed.value
                viewModelScope.launch {
                    repo.saveStrengthPlan(SavedStrengthPlan(System.currentTimeMillis(), plan, response))
                    _strengthCoach.value = CoachState.Done(plan.sessions.size, parsed.truncated)
                }
            },
            onFailure = { _strengthCoach.value = CoachState.Error(it.message ?: "Réponse illisible") },
        )
    }

    fun generateStrengthWithApi() {
        val s = settingsStore.settings.value
        if (s.anthropicKey.isBlank()) {
            _strengthCoach.value = CoachState.Error("Renseigne d'abord ta clé API dans les réglages IA (onglet Coach)")
            return
        }
        if (_strengthCoach.value == CoachState.Generating) return
        _strengthCoach.value = CoachState.Generating
        val prompt = buildStrengthPrompt()
        viewModelScope.launch {
            runCatching { AnthropicClient.complete(s.anthropicKey, s.anthropicModel, prompt) }
                .onSuccess { importStrengthResponse(it) }
                .onFailure { _strengthCoach.value = CoachState.Error(it.message ?: "Échec de l'appel à l'API") }
        }
    }

    private val _player = MutableStateFlow(StrengthPlayerState())
    val player: StateFlow<StrengthPlayerState> = _player
    private var playerTicker: Job? = null

    fun startStrength(session: StrengthSession, painBefore: Int) {
        RunTracker.ensureTts()
        val steps = StrengthPlayer.expand(session)
        if (steps.isEmpty()) return
        _player.value = StrengthPlayerState(session, steps, 0, steps[0].seconds, painBefore = painBefore)
        RunTracker.speak("C'est parti pour ${session.name}.")
        announcePlayerStep()
        playerTicker?.cancel()
        playerTicker = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                playerTick()
            }
        }
    }

    private fun announcePlayerStep() {
        val p = _player.value
        val step = p.step ?: return
        RunTracker.vibrate(if (step.rest) 1 else 3)
        RunTracker.speak(step.spoken(p.steps.getOrNull(p.index + 1)))
    }

    private fun playerTick() {
        val p = _player.value
        if (p.session == null || p.finished || p.paused) return
        val r = p.remaining
        _player.value = p.copy(elapsed = p.elapsed + 1, remaining = r?.minus(1))
        if (r != null) {
            if (r - 1 in 1..3) RunTracker.speak("${r - 1}")
            if (r - 1 <= 0) nextStrengthStep()
        }
    }

    /** Étape suivante (bouton « Fait » pour les répétitions, ou fin du chrono). */
    fun nextStrengthStep() {
        val p = _player.value
        val next = p.index + 1
        if (next >= p.steps.size) {
            playerTicker?.cancel()
            _player.value = p.copy(finished = true, remaining = null)
            RunTracker.vibrate(3)
            RunTracker.speak("Séance terminée, bravo ! Note ta douleur pour adapter les prochaines séances.")
            return
        }
        _player.value = p.copy(index = next, remaining = p.steps[next].seconds)
        announcePlayerStep()
    }

    fun previousStrengthStep() {
        val p = _player.value
        val prev = (p.index - 1).coerceAtLeast(0)
        _player.value = p.copy(index = prev, remaining = p.steps[prev].seconds)
        announcePlayerStep()
    }

    fun toggleStrengthPause() {
        _player.value = _player.value.copy(paused = !_player.value.paused)
    }

    /** Fin de séance : enregistre la douleur avant/après (réutilisée dans le prochain prompt). */
    fun finishStrength(painAfter: Int?, note: String) {
        val p = _player.value
        playerTicker?.cancel()
        val session = p.session
        if (session != null && painAfter != null) {
            viewModelScope.launch {
                repo.addPainLog(PainLog(System.currentTimeMillis(), session.name, p.painBefore, painAfter, note))
                repo.saveStrengthProfile(repo.strengthProfile.value.copy(painLevel = painAfter))
            }
        }
        _player.value = StrengthPlayerState()
    }

    // ------------------------------------------------------------ running

    fun setSport(s: Sport) = settingsStore.update { it.copy(sport = s) }

    /** « Ma semaine » : reporter (+1) ou avancer (-1) tout le plan d'une semaine. */
    fun shiftPlans(weeks: Int) = viewModelScope.launch { repo.shiftPlans(weeks) }

    /** Déplace une séance cardio du plan vers un autre jour (et éventuellement une autre semaine). */
    fun moveWorkout(w: Workout, day: String, week: Int? = null) =
        saveWorkout(w.copy(planDay = day, planWeek = week ?: w.planWeek))

    fun updateStrengthSession(index: Int, s: StrengthSession) = viewModelScope.launch { repo.updateStrengthSession(index, s) }

    /** « Ma semaine » : cocher / décocher une séance du plan. */
    fun setDone(key: String, done: Boolean) = viewModelScope.launch { repo.setDone(key, done) }

    /** Programme running choisi, lancé depuis l'écran Course (permissions GPS d'abord). */
    private val _pendingRun = MutableStateFlow<Workout?>(null)
    val pendingRun: StateFlow<Workout?> = _pendingRun

    fun selectRunWorkout(w: Workout?) {
        _pendingRun.value = w
    }

    /** Bouton ▶ d'un programme : elliptique = démarre ; running = prépare l'écran Course. */
    /** « Faire sur l'autre machine » : crée la version convertie et la lance dans l'autre mode. */
    fun runOnOtherSport(w: Workout): Workout {
        val target = if (w.sport == Sport.RUNNING) Sport.ELLIPTICAL else Sport.RUNNING
        val converted = WorkoutConverter.convert(w, target)
        saveWorkout(converted)
        setSport(target)
        launchWorkout(converted)
        return converted
    }

    fun launchWorkout(w: Workout) {
        if (w.sport == Sport.RUNNING) _pendingRun.value = w else startSession(w)
    }

    /** Sauvegarde indépendante du ViewModel : une course peut finir écran éteint. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        RunTracker.init(app)
        RunTracker.heartRate = { hr.bpm.value }
        RunTracker.hrZones = { repo.profile.value.hrZones }
        RunTracker.voiceEnabled = { settingsStore.settings.value.voice }
        RunTracker.hrAlertsEnabled = { settingsStore.settings.value.hrAlerts }
        RunTracker.weightKg = { repo.profile.value.weightKg }
        RunTracker.onFinished = { rec -> appScope.launch { repo.saveSession(rec) } }
    }

    init {
        // On n'attend le vélo que pendant la courte phase de découverte des services (quelques secondes)
        hr.isBikeBusy = { bike.state.value == ConnState.DISCOVERING }
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
        hrAlarm.reset()
        RunTracker.ensureTts()
        RunTracker.speak(if (workout == null) "C'est parti, séance libre." else "C'est parti pour ${workout.name}.")
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
            if (w != null && p != null && !p.finished) applyStep(p.index, w.steps[p.index], announce = false)
            RunTracker.speak("Reprise")
        } else {
            RunTracker.speak("Pause")
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
            val rec = WorkoutEngine.summarize(s.startedAt, s.elapsed, s.workout?.name, s.samples, s.workout?.steps.orEmpty())
            _lastFinished.value = rec
            RunTracker.speak(
                "Séance terminée. ${com.mathieu.madtrainer.data.spokenDuration(s.elapsed)}" +
                    (if (rec.kcal > 0) ", ${rec.kcal} kilocalories" else "") + ". Ton rapport de progression est prêt.",
            )
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
            step = s.workout?.let { WorkoutEngine.position(it, e).index },
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
            // Coach vocal : préavis 30 s avant l'étape suivante, alerte si le cardio est trop haut
            val cur = w.steps[pos.index]
            if (VoiceCoach.shouldPreWarn(cur, pos.stepElapsed)) {
                RunTracker.vibrate(1)
                RunTracker.speak(VoiceCoach.preStepText(w.steps.getOrNull(pos.index + 1), repo.profile.value.hrZones))
            }
            checkHrAlarm(e, m.heartRate, cur, w.steps)
            // Étape en zone cardio : la résistance suit la fréquence cardiaque (toutes les 15 s, après 20 s d'étape)
            val step = w.steps[pos.index]
            val hrNow = m.heartRate
            if (step.mode == StepMode.HR_ZONE && hrNow != null && pos.stepElapsed >= 20 && pos.stepElapsed % 15 == 0) {
                val maxR = bike.caps.value.resistanceRange?.let { bike.rawToLevel(it.max).toInt() }?.takeIf { it > 1 } ?: 15
                val next = WorkoutConverter.resistanceForZone(hrZoneLevel, hrNow, step.target.toInt(), repo.profile.value.hrZones, 1, maxR)
                if (next != hrZoneLevel) {
                    hrZoneLevel = next
                    bike.setResistanceLevel(next.toDouble())
                }
            }
        }
        if (w == null) checkHrAlarm(e, m.heartRate, null, emptyList())
        _session.value = s.copy(elapsed = e, position = pos, samples = s.samples + sample)
    }

    private val hrAlarm = VoiceCoach.HrAlarm()

    private fun checkHrAlarm(t: Int, hr: Int?, step: WorkoutStep?, steps: List<WorkoutStep>) {
        if (!settingsStore.settings.value.hrAlerts) return
        if (hrAlarm.update(t, hr, VoiceCoach.hrCeiling(step, steps, repo.profile.value.hrZones))) {
            RunTracker.vibrate(2)
            RunTracker.speak(VoiceCoach.hrAlertText(hr ?: 0, running = false))
        }
    }

    fun setVoice(on: Boolean) = settingsStore.update { it.copy(voice = on) }
    fun setHrAlerts(on: Boolean) = settingsStore.update { it.copy(hrAlerts = on) }

    // ------------------------------------------------------------ rapport de progression

    private val _reportAi = MutableStateFlow<ReportAiState>(ReportAiState.Idle)
    val reportAi: StateFlow<ReportAiState> = _reportAi

    fun progressReport(rec: SessionRecord): ProgressReport =
        SessionReport.build(rec, repo.sessions.value, repo.profile.value.hrZones)

    fun buildReportPrompt(rec: SessionRecord): String {
        val p = repo.profile.value
        val profileText = buildString {
            appendLine("- ${p.sex.label}, ${p.age} ans, ${p.weightKg} kg, ${p.heightCm} cm")
            appendLine("- Objectif : ${p.goal.label}, niveau : ${p.level.label}, ${p.sessionsPerWeek} séances par semaine")
            if (p.constraints.isNotBlank()) appendLine("- Contraintes : ${p.constraints.trim()}")
            if (p.runningInfo.isNotBlank()) appendLine("- Repères running : ${p.runningInfo.trim()}")
        }
        val planText = repo.globalPlan.value?.let { plan ->
            val ws = repo.workouts.value.filter { it.id in plan.workoutIds }
            val maxWeek = ws.maxOfOrNull { it.planWeek ?: 1 } ?: 1
            val week = WeekPlanner.currentWeek(plan.createdAt, rec.startedAt, maxWeek)
            val list = ws.filter { (it.planWeek ?: 1) == week }
            if (list.isEmpty()) null else "Semaine $week sur $maxWeek :\n" + list.joinToString("\n") { w ->
                "- ${w.planDay ?: "?"} : ${w.name} (${w.sport.label}, ${w.totalSec / 60} min)"
            }
        }
        return SessionReport.prompt(rec, progressReport(rec), repo.sessions.value, p.hrZones, profileText, planText)
    }

    /** Analyse IA via l'API (clé requise) ; le texte est enregistré avec la séance. */
    fun generateReport(rec: SessionRecord) {
        val s = settingsStore.settings.value
        if (s.anthropicKey.isBlank()) {
            _reportAi.value = ReportAiState.Error(rec.id, "Pas de clé API : copie le prompt, puis colle la réponse de l'IA")
            return
        }
        if (_reportAi.value is ReportAiState.Working) return
        _reportAi.value = ReportAiState.Working(rec.id)
        val prompt = buildReportPrompt(rec)
        viewModelScope.launch {
            runCatching { AnthropicClient.complete(s.anthropicKey, s.anthropicModel, prompt, maxTokens = 2000) }
                .onSuccess { saveReport(rec, it) }
                .onFailure { _reportAi.value = ReportAiState.Error(rec.id, it.message ?: "Échec de l'appel à l'API") }
        }
    }

    /** Réponse de l'IA collée à la main. */
    fun saveReport(rec: SessionRecord, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val current = repo.sessions.value.firstOrNull { it.id == rec.id } ?: rec
            repo.saveSession(current.copy(aiReport = clean))
            if (_lastFinished.value?.id == rec.id) _lastFinished.value = _lastFinished.value?.copy(aiReport = clean)
            _reportAi.value = ReportAiState.Idle
        }
    }

    /** Niveau de résistance courant pour les étapes pilotées par le cardio. */
    private var hrZoneLevel = 5

    private fun applyStep(index: Int, step: WorkoutStep, announce: Boolean = true) {
        appliedStep = index
        if (announce) {
            val w = _session.value.workout
            RunTracker.vibrate(3)
            RunTracker.speak(VoiceCoach.stepText(step, index, w?.steps?.size ?: 1, repo.profile.value.hrZones))
        }
        when (step.mode) {
            StepMode.RESISTANCE -> bike.setResistanceLevel(step.target)
            StepMode.POWER -> bike.setTargetPower(step.target.toInt())
            StepMode.HR_ZONE -> {
                // Niveau de départ selon la zone ; ensuite la résistance suit le cardio de la montre
                val maxR = bike.caps.value.resistanceRange?.let { bike.rawToLevel(it.max).toInt() }?.takeIf { it > 1 } ?: 15
                hrZoneLevel = WorkoutConverter.startLevelForZone(step.target.toInt(), maxR)
                bike.setResistanceLevel(hrZoneLevel.toDouble())
            }
            else -> Unit
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
        if (s.hrName == null) return
        val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(app, perm) != PackageManager.PERMISSION_GRANTED) return
        if (!bike.isBluetoothEnabled) return
        runCatching { hr.reconnectSaved() }
    }

    override fun onCleared() {
        hr.release()
        bike.release()
        super.onCleared()
    }
}
