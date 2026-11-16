package com.mathieu.madtrainer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.LocalTime

/** Une prise d'eau : heure (ms) et quantité. */
data class Drink(val time: Long, val ml: Int)

data class HydrationState(
    val day: Long = 0,
    val drinks: List<Drink> = emptyList(),
    /** Total des jours précédents (epochDay → ml), 30 derniers jours. */
    val history: Map<Long, Int> = emptyMap(),
    val weightKg: Double = 75.0,
    /** Minutes de sport enregistrées aujourd'hui (augmentent l'objectif). */
    val exerciseMin: Int = 0,
    val reminders: Boolean = true,
    val wakeHour: Int = 8,
    val sleepHour: Int = 22,
    val lastReminderAt: Long = 0,
) {
    val drunkMl: Int get() = drinks.sumOf { it.ml }
    val targetMl: Int get() = Hydration.targetMl(weightKg, exerciseMin)
    fun expectedNow(): Int = Hydration.expectedMl(targetMl, LocalTime.now().toSecondOfDay() / 60, wakeHour, sleepHour)
}

/**
 * Compteur d'eau, dans des préférences partagées : lu par l'écran, par la vérification périodique
 * (WorkManager) et par le bouton « +250 ml » de la notification, même quand l'appli est fermée.
 */
object HydrationStore {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(HydrationState())
    val state: StateFlow<HydrationState> get() = _state

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("hydration", Context.MODE_PRIVATE)
        _state.value = read()
    }

    private fun today() = LocalDate.now().toEpochDay()

    private fun read(): HydrationState {
        val day = prefs.getLong("day", today())
        var s = HydrationState(
            day = day,
            drinks = prefs.getString("drinks", "").orEmpty().split(",").mapNotNull { e ->
                val p = e.split(":")
                if (p.size == 2) Drink(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null) else null
            },
            history = prefs.getString("history", "").orEmpty().split(";").mapNotNull { e ->
                val p = e.split(":")
                if (p.size == 2) (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toIntOrNull() ?: return@mapNotNull null) else null
            }.toMap(),
            weightKg = prefs.getFloat("weightKg", 75f).toDouble(),
            exerciseMin = if (prefs.getLong("exerciseDay", 0) == today()) prefs.getInt("exerciseMin", 0) else 0,
            reminders = prefs.getBoolean("reminders", true),
            wakeHour = prefs.getInt("wakeHour", 8),
            sleepHour = prefs.getInt("sleepHour", 22),
            lastReminderAt = prefs.getLong("lastReminderAt", 0),
        )
        // Nouveau jour : la veille passe dans l'historique
        if (day != today()) {
            val hist = (s.history + (day to s.drunkMl)).filterKeys { it >= today() - 30 }.filterValues { it > 0 }
            s = s.copy(day = today(), drinks = emptyList(), history = hist)
            write(s)
        }
        return s
    }

    private fun write(s: HydrationState) {
        prefs.edit()
            .putLong("day", s.day)
            .putString("drinks", s.drinks.joinToString(",") { "${it.time}:${it.ml}" })
            .putString("history", s.history.entries.joinToString(";") { "${it.key}:${it.value}" })
            .putFloat("weightKg", s.weightKg.toFloat())
            .putInt("exerciseMin", s.exerciseMin)
            .putLong("exerciseDay", s.day)
            .putBoolean("reminders", s.reminders)
            .putInt("wakeHour", s.wakeHour)
            .putInt("sleepHour", s.sleepHour)
            .putLong("lastReminderAt", s.lastReminderAt)
            .apply()
    }

    @Synchronized
    private fun update(f: (HydrationState) -> HydrationState) {
        val s = f(read())
        write(s)
        _state.value = s
    }

    /** Relit l'état (changement de jour, ajout depuis la notification…). */
    fun refresh() {
        if (::prefs.isInitialized) _state.value = read()
    }

    fun add(ml: Int) = update { it.copy(drinks = it.drinks + Drink(System.currentTimeMillis(), ml)) }

    fun undo() = update { it.copy(drinks = it.drinks.dropLast(1)) }

    /** Poids du profil et minutes de sport du jour, pour l'objectif. */
    fun setContext(weightKg: Double, exerciseMin: Int) {
        val s = _state.value
        if (s.weightKg != weightKg || s.exerciseMin != exerciseMin) update { it.copy(weightKg = weightKg, exerciseMin = exerciseMin) }
    }

    fun setReminders(on: Boolean) = update { it.copy(reminders = on) }

    fun setHours(wake: Int, sleep: Int) = update { it.copy(wakeHour = wake.coerceIn(4, 12), sleepHour = sleep.coerceIn(18, 24)) }

    fun markReminded() = update { it.copy(lastReminderAt = System.currentTimeMillis()) }
}
