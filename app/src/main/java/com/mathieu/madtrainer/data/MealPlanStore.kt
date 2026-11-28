package com.mathieu.madtrainer.data

import android.content.Context
import com.mathieu.madtrainer.coach.Goal
import com.mathieu.madtrainer.coach.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/** Planificateur de repas : état en mémoire, sauvegardé automatiquement à chaque modification. */
object MealPlanStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private val _state = MutableStateFlow(MealPlanState())
    val state: StateFlow<MealPlanState> get() = _state

    /** Jours conservés dans l'historique des repas. */
    private const val KEEP_DAYS = 60

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "meal_plan.json")
        file = f
        _state.value = runCatching { json.decodeFromString(MealPlanState.serializer(), f.readText()) }.getOrDefault(MealPlanState())
    }

    @Synchronized
    private fun update(change: (MealPlanState) -> MealPlanState) {
        val today = LocalDate.now().toEpochDay()
        val next = change(_state.value).let { s -> s.copy(days = s.days.filter { it.epochDay > today - KEEP_DAYS }) }
        _state.value = next
        val f = file ?: return
        val text = json.encodeToString(MealPlanState.serializer(), next)
        io.execute { runCatching { f.writeText(text) } }
    }

    /** Valeurs du calculateur : celles saisies, sinon déduites du profil et du dernier poids connu. */
    fun targetsFor(s: MealPlanState, p: UserProfile, weightKg: Double?): NutritionTargets {
        if (s.targetsEdited) return s.targets
        val activity = when (p.sessionsPerWeek) {
            in Int.MIN_VALUE..0 -> ActivityLevel.SEDENTARY
            in 1..2 -> ActivityLevel.LIGHT
            in 3..5 -> ActivityLevel.MODERATE
            else -> ActivityLevel.ACTIVE
        }
        return s.targets.copy(
            sex = p.sex,
            age = p.age,
            weightKg = weightKg ?: p.weightKg,
            heightCm = p.heightCm,
            activity = activity,
            goal = if (p.goal == Goal.WEIGHT_LOSS) CalorieGoal.LOSS else CalorieGoal.MAINTAIN,
        )
    }

    fun setTargets(t: NutritionTargets) = update { it.copy(targets = t, targetsEdited = true) }

    fun entries(s: MealPlanState, day: Long): List<FoodEntry> = s.days.firstOrNull { it.epochDay == day }?.entries.orEmpty()

    private fun nextId(s: MealPlanState): Long = (s.days.flatMap { it.entries }.maxOfOrNull { it.id } ?: 0L) + 1

    private fun editDay(day: Long, change: (List<FoodEntry>) -> List<FoodEntry>) = update { s ->
        val current = entries(s, day)
        s.copy(days = s.days.filter { it.epochDay != day } + MealDay(day, change(current)))
    }

    fun add(day: Long, food: Food, slot: MealSlot) {
        val id = nextId(_state.value)
        editDay(day) { it + MealPlan.defaultEntry(id, food, slot) }
    }

    fun replace(day: Long, entry: FoodEntry) = editDay(day) { list -> list.map { if (it.id == entry.id) entry else it } }

    fun remove(day: Long, entryId: Long) = editDay(day) { list -> list.filter { it.id != entryId } }

    fun clear(day: Long) = editDay(day) { emptyList() }

    fun applyTemplate(day: Long, tpl: MealPlan.Template, targetKcal: Int) {
        val s = _state.value
        val entries = MealPlan.applyTemplate(tpl, targetKcal, MealPlan.foodsById(s.customFoods), nextId(s), variant = day)
        editDay(day) { entries }
    }

    /** Entrées des jours [from] à [to] inclus. */
    fun entriesBetween(s: MealPlanState, from: Long, to: Long): List<FoodEntry> =
        s.days.filter { it.epochDay in from..to }.flatMap { it.entries }

    /** Copie les repas du jour [from] sur chacun des jours [targets] (remplace leur contenu). */
    fun copyDay(from: Long, targets: List<Long>) = update { s ->
        val source = entries(s, from)
        var id = nextId(s)
        val copies = targets.filter { it != from }.map { d -> MealDay(d, source.map { it.copy(id = id++) }) }
        s.copy(days = s.days.filter { it.epochDay !in targets || it.epochDay == from } + copies)
    }

    /** Applique une journée type à plusieurs jours ; [onlyEmpty] laisse intacts les jours déjà remplis. */
    fun applyTemplateToDays(targets: List<Long>, tpl: MealPlan.Template, targetKcal: Int, onlyEmpty: Boolean) = update { s ->
        val foods = MealPlan.foodsById(s.customFoods)
        var id = nextId(s)
        val chosen = targets.filter { !onlyEmpty || entries(s, it).isEmpty() }
        val filled = chosen.map { d ->
            val list = MealPlan.applyTemplate(tpl, targetKcal, foods, id, variant = d)
            id += list.size
            MealDay(d, list)
        }
        s.copy(days = s.days.filter { it.epochDay !in chosen } + filled)
    }

    fun clearDays(targets: List<Long>) = update { s -> s.copy(days = s.days.filter { it.epochDay !in targets }) }

    /** Coche / décoche un aliment de la liste de courses de la semaine [weekStart] ; garde 8 semaines. */
    fun toggleShopping(weekStart: Long, foodId: String) = update { s ->
        val current = s.shoppingChecked[weekStart].orEmpty()
        val next = if (foodId in current) current - foodId else current + foodId
        s.copy(shoppingChecked = (s.shoppingChecked + (weekStart to next)).filterKeys { it > weekStart - 56 })
    }

    fun resetShopping(weekStart: Long) = update { s -> s.copy(shoppingChecked = s.shoppingChecked - weekStart) }

    fun addCustomFood(food: Food) = update { s -> s.copy(customFoods = s.customFoods.filter { it.id != food.id } + food) }

    /** Supprime un aliment perso et ses occurrences dans les repas. */
    fun removeCustomFood(id: String) = update { s ->
        s.copy(
            customFoods = s.customFoods.filter { it.id != id },
            days = s.days.map { d -> d.copy(entries = d.entries.filter { it.foodId != id }) },
        )
    }
}
