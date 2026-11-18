package com.mathieu.madtrainer.coach

/** Groupes musculaires proposés en filtre (regroupent les 17 muscles de la banque). */
enum class MuscleGroup(val label: String, val muscles: Set<String>) {
    QUADS("Quadriceps", setOf("quadriceps")),
    HAMSTRINGS("Ischios", setOf("hamstrings")),
    GLUTES("Fessiers / hanches", setOf("glutes", "abductors", "adductors")),
    CALVES("Mollets", setOf("calves")),
    ABS("Abdos", setOf("abdominals")),
    LOWER_BACK("Lombaires", setOf("lower back")),
    BACK("Dos", setOf("lats", "middle back", "traps")),
    CHEST("Pectoraux", setOf("chest")),
    SHOULDERS("Épaules / nuque", setOf("shoulders", "neck")),
    ARMS("Bras", setOf("biceps", "triceps", "forearms")),
    ;

    companion object {
        fun of(muscle: String): MuscleGroup? = entries.firstOrNull { muscle in it.muscles }
    }
}

/** Libellés français des valeurs de la banque. */
object ExerciseLabels {
    val MUSCLES = mapOf(
        "abdominals" to "abdos", "abductors" to "abducteurs", "adductors" to "adducteurs", "biceps" to "biceps",
        "calves" to "mollets", "chest" to "pectoraux", "forearms" to "avant-bras", "glutes" to "fessiers",
        "hamstrings" to "ischios", "lats" to "grand dorsal", "lower back" to "lombaires", "middle back" to "milieu du dos",
        "neck" to "nuque", "quadriceps" to "quadriceps", "shoulders" to "épaules", "traps" to "trapèzes", "triceps" to "triceps",
    )
    val EQUIPMENT = linkedMapOf(
        "body only" to "Poids du corps", "none" to "Sans matériel", "other" to "Autre", "bands" to "Élastique",
        "dumbbell" to "Haltères", "kettlebells" to "Kettlebell", "exercise ball" to "Swiss ball", "foam roll" to "Rouleau",
        "medicine ball" to "Médecine-ball", "barbell" to "Barre", "e-z curl bar" to "Barre EZ", "cable" to "Poulie", "machine" to "Machine",
    )
    val CATEGORIES = linkedMapOf(
        "strength" to "Renfo", "stretching" to "Étirement", "plyometrics" to "Pliométrie", "cardio" to "Cardio",
        "powerlifting" to "Force athlétique", "olympic weightlifting" to "Haltérophilie", "strongman" to "Strongman",
    )
    val LEVELS = linkedMapOf("b" to "Débutant", "i" to "Intermédiaire", "e" to "Expert")
    val FORCES = linkedMapOf("push" to "Poussée", "pull" to "Tirage", "static" to "Statique")
    val MECHANICS = linkedMapOf("c" to "Polyarticulaire", "i" to "Isolation")

    fun muscle(m: String) = MUSCLES[m] ?: m
    fun equipment(e: String) = EQUIPMENT[e] ?: e
    fun category(c: String) = CATEGORIES[c] ?: c
    fun level(l: String) = LEVELS[l] ?: l
}

/** Critères de recherche dans la banque ; ensemble vide = pas de filtre. */
data class ExerciseFilter(
    val query: String = "",
    val groups: Set<MuscleGroup> = emptySet(),
    /** Matériel brut de la banque (null = tout matériel) */
    val equipment: Set<String>? = null,
    val categories: Set<String> = emptySet(),
    val levels: Set<String> = emptySet(),
    val forces: Set<String> = emptySet(),
    val mechanics: Set<String> = emptySet(),
    /** Compte aussi les muscles secondaires pour le filtre de groupe */
    val includeSecondary: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(groups, categories, levels, forces, mechanics).count { it.isNotEmpty() } + (if (equipment != null) 1 else 0)
}

/** Recherche, filtres, alternatives et associations d'exercices. */
object ExerciseLibrary {

    /** Muscles « opposés » : bons candidats pour une superserie antagoniste. */
    val ANTAGONISTS: Map<String, Set<String>> = mapOf(
        "chest" to setOf("lats", "middle back"),
        "lats" to setOf("chest", "shoulders"),
        "middle back" to setOf("chest"),
        "shoulders" to setOf("lats", "middle back"),
        "biceps" to setOf("triceps"),
        "triceps" to setOf("biceps"),
        "quadriceps" to setOf("hamstrings", "glutes"),
        "hamstrings" to setOf("quadriceps"),
        "glutes" to setOf("quadriceps"),
        "abdominals" to setOf("lower back"),
        "lower back" to setOf("abdominals"),
        "adductors" to setOf("abductors"),
        "abductors" to setOf("adductors"),
    )

    val PUSH_MUSCLES = setOf("chest", "shoulders", "triceps")
    val PULL_MUSCLES = setOf("lats", "middle back", "biceps", "traps")

    /** « push » / « pull » pour un exercice du haut du corps (force de la banque, sinon déduite du muscle), null sinon. */
    fun upperBodyDirection(ex: CatalogExercise): String? {
        if (ex.m !in PUSH_MUSCLES && ex.m !in PULL_MUSCLES) return null
        return when (ex.f) {
            "push", "pull" -> ex.f
            "static" -> null
            else -> if (ex.m in PUSH_MUSCLES) "push" else "pull"
        }
    }

    fun matches(ex: CatalogExercise, f: ExerciseFilter): Boolean {
        if (f.groups.isNotEmpty()) {
            val muscles = if (f.includeSecondary) ex.muscles else listOf(ex.m)
            if (f.groups.none { g -> muscles.any { it in g.muscles } }) return false
        }
        if (f.equipment != null && ex.e !in f.equipment) return false
        if (f.categories.isNotEmpty() && ex.c !in f.categories) return false
        if (f.levels.isNotEmpty() && ex.l !in f.levels) return false
        if (f.forces.isNotEmpty() && ex.f !in f.forces) return false
        if (f.mechanics.isNotEmpty() && ex.k !in f.mechanics) return false
        return true
    }

    /** Résultats triés : pertinence du texte puis exercices courants (nom français), plus simples d'abord. */
    fun filter(catalog: ExerciseCatalog, f: ExerciseFilter, limit: Int = 300): List<CatalogExercise> {
        val base = if (f.query.isBlank()) catalog.all.sortedWith(friendly) else catalog.search(f.query, limit = catalog.all.size)
        return base.filter { matches(it, f) }.take(limit)
    }

    /** Ordre « maison » : nom français connu, niveau facile, matériel léger, nom court. */
    val friendly: Comparator<CatalogExercise> =
        compareBy<CatalogExercise> { if (it.fr != null) 0 else 1 }
            .thenBy { ExerciseLabels.LEVELS.keys.indexOf(it.l) }
            .thenBy { if (it.e in ExerciseCatalog.BODY_EQUIPMENT) 0 else 1 }
            .thenBy { it.label }

    /** Exercices équivalents (même muscle principal, même type de mouvement) pour remplacer [ex]. */
    fun alternatives(catalog: ExerciseCatalog, ex: CatalogExercise, allowed: Set<String>?, limit: Int = 6): List<CatalogExercise> =
        catalog.all.asSequence()
            .filter { it.id != ex.id && it.m == ex.m && it.c == ex.c && (ex.f == null || it.f == ex.f) }
            .filter { allowed == null || it.e in allowed }
            .sortedWith(compareBy<CatalogExercise> { if (it.k == ex.k) 0 else 1 }.then(friendly))
            .take(limit).toList()

    /** Exercices qui s'associent bien à [ex] : muscles antagonistes (superserie), même matériel de préférence. */
    fun complements(catalog: ExerciseCatalog, ex: CatalogExercise, allowed: Set<String>?, limit: Int = 6): List<CatalogExercise> {
        val targets = ANTAGONISTS[ex.m] ?: return emptyList()
        return catalog.all.asSequence()
            .filter { it.m in targets && it.c == "strength" && (allowed == null || it.e in allowed) }
            .sortedWith(compareBy<CatalogExercise> { if (it.e == ex.e) 0 else 1 }.thenBy { if (it.k == ex.k) 0 else 1 }.then(friendly))
            .take(limit).toList()
    }

    /** Meilleurs exercices pour un ensemble de muscles, avec le matériel disponible. */
    fun bestFor(
        catalog: ExerciseCatalog,
        muscles: Set<String>,
        allowed: Set<String>?,
        category: String = "strength",
        exclude: Set<String> = emptySet(),
        limit: Int = 4,
    ): List<CatalogExercise> =
        catalog.all.asSequence()
            .filter { it.m in muscles && it.c == category && it.id !in exclude && it.l != "e" && (allowed == null || it.e in allowed) }
            .sortedWith(friendly)
            .take(limit).toList()

    /** Exercice d'une séance pré-rempli à partir d'une entrée de la banque (séries, reps ou durée, repos). */
    fun toSessionExercise(ex: CatalogExercise): StrengthExercise {
        val oneSide = UNILATERAL.any { ex.n.contains(it, ignoreCase = true) }
        val base = StrengthExercise(name = ex.label, image_id = ex.id, per_side = oneSide, instructions = ex.d)
        return when {
            ex.c == "stretching" -> base.copy(sets = 2, duration_sec = 30, rest_sec = 15)
            ex.c == "cardio" -> base.copy(sets = 1, duration_sec = 300, rest_sec = 60, per_side = false)
            ex.f == "static" -> base.copy(sets = 3, duration_sec = 30, rest_sec = 30)
            ex.c == "plyometrics" -> base.copy(sets = 3, reps = 8, rest_sec = 60)
            ex.k == "i" -> base.copy(sets = 3, reps = 12, rest_sec = 45)
            else -> base.copy(sets = 3, reps = 10, rest_sec = 60)
        }
    }

    private val UNILATERAL = listOf("One-Arm", "One Arm", "One-Leg", "Single", "Alternat", "Lunge", "Split", "Side Bridge", "Side Leg", "One Knee", "Unilateral")
}
