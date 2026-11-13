package com.mathieu.madtrainer.data

import android.content.Context
import com.mathieu.madtrainer.coach.SavedPlan
import com.mathieu.madtrainer.coach.UserProfile
import com.mathieu.madtrainer.strava.StravaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Stockage local en JSON (programmes + historique). */
class Repository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val workoutsFile = File(context.filesDir, "workouts.json")
    private val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val profileFile = File(context.filesDir, "profile.json")
    private val planFile = File(context.filesDir, "coach_plan.json")
    private val stravaFile = File(context.filesDir, "strava.json")

    private val _strava = MutableStateFlow(StravaCache())
    val strava: StateFlow<StravaCache> = _strava

    private val _profile = MutableStateFlow(UserProfile())
    val profile: StateFlow<UserProfile> = _profile
    private val _plan = MutableStateFlow<SavedPlan?>(null)
    val plan: StateFlow<SavedPlan?> = _plan

    private val _workouts = MutableStateFlow<List<Workout>>(emptyList())
    val workouts: StateFlow<List<Workout>> = _workouts
    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions

    suspend fun load() = withContext(Dispatchers.IO) {
        _workouts.value = runCatching {
            json.decodeFromString(ListSerializer(Workout.serializer()), workoutsFile.readText())
        }.getOrElse {
            WorkoutEngine.defaultWorkouts().also { writeWorkouts(it) }
        }
        _profile.value = runCatching { json.decodeFromString(UserProfile.serializer(), profileFile.readText()) }
            .getOrDefault(UserProfile())
        _strava.value = runCatching { json.decodeFromString(StravaCache.serializer(), stravaFile.readText()) }
            .getOrDefault(StravaCache())
        _plan.value = runCatching { json.decodeFromString(SavedPlan.serializer(), planFile.readText()) }.getOrNull()
        _sessions.value = sessionsDir.listFiles().orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { f -> runCatching { json.decodeFromString(SessionRecord.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    private fun writeWorkouts(list: List<Workout>) {
        workoutsFile.writeText(json.encodeToString(ListSerializer(Workout.serializer()), list))
    }

    suspend fun saveStrava(c: StravaCache) = withContext(Dispatchers.IO) {
        stravaFile.writeText(json.encodeToString(StravaCache.serializer(), c))
        _strava.value = c
    }

    suspend fun saveProfile(p: UserProfile) = withContext(Dispatchers.IO) {
        profileFile.writeText(json.encodeToString(UserProfile.serializer(), p))
        _profile.value = p
    }

    /** Enregistre le plan du coach et remplace les séances du plan précédent. */
    suspend fun savePlan(plan: SavedPlan, workouts: List<Workout>) = withContext(Dispatchers.IO) {
        val oldIds = _plan.value?.workoutIds.orEmpty().toSet()
        val list = _workouts.value.filterNot { it.id in oldIds } + workouts
        writeWorkouts(list)
        _workouts.value = list
        val saved = plan.copy(workoutIds = workouts.map { it.id })
        planFile.writeText(json.encodeToString(SavedPlan.serializer(), saved))
        _plan.value = saved
    }

    suspend fun saveWorkout(w: Workout) = withContext(Dispatchers.IO) {
        val list = _workouts.value.toMutableList()
        val i = list.indexOfFirst { it.id == w.id }
        if (i >= 0) list[i] = w else list.add(w)
        writeWorkouts(list)
        _workouts.value = list
    }

    suspend fun deleteWorkout(id: String) = withContext(Dispatchers.IO) {
        val list = _workouts.value.filterNot { it.id == id }
        writeWorkouts(list)
        _workouts.value = list
    }

    suspend fun saveSession(s: SessionRecord) = withContext(Dispatchers.IO) {
        File(sessionsDir, "${s.id}.json").writeText(json.encodeToString(SessionRecord.serializer(), s))
        _sessions.value = (listOf(s) + _sessions.value.filterNot { it.id == s.id }).sortedByDescending { it.startedAt }
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        File(sessionsDir, "$id.json").delete()
        _sessions.value = _sessions.value.filterNot { it.id == id }
    }
}
