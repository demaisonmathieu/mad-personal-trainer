package com.mathieu.madtrainer.coach

/** Un conseil sur la construction d'une séance de renfo, avec éventuellement des exercices à ajouter. */
data class SessionAdvice(
    val level: Level,
    val title: String,
    val text: String,
    val suggestions: List<CatalogExercise> = emptyList(),
    /** Exercices de la séance concernés (indices) */
    val exercises: List<Int> = emptyList(),
) {
    enum class Level { WARNING, TIP, GOOD }
}

/**
 * Analyse d'une séance en cours de construction : douleurs déclarées, équilibre poussée / tirage,
 * groupes oubliés, ordre des exercices, supersets antagonistes, volume et durée.
 */
object SessionAdvisor {

    /** Exercice de la séance relié à la banque (null si exercice libre non reconnu). */
    private class Item(val index: Int, val ex: StrengthExercise, val cat: CatalogExercise?)

    fun analyze(session: StrengthSession, catalog: ExerciseCatalog, profile: StrengthProfile, estimatedSec: Int): List<SessionAdvice> {
        val allowed = ExerciseCatalog.allowedEquipment(profile.equipment)
        val items = session.exercises.mapIndexed { i, e -> Item(i, e, catalog.find(e)) }
        val known = items.filter { it.cat != null }
        val inSession = known.mapNotNull { it.cat?.id }.toSet()
        val out = mutableListOf<SessionAdvice>()

        if (items.isEmpty()) {
            out += SessionAdvice(
                SessionAdvice.Level.TIP, "Par où commencer",
                "Une séance équilibrée : 1 à 2 exercices de mobilité pour s'échauffer, puis les polyarticulaires (squat, fente, pompe, rowing), " +
                    "le gainage, et quelques étirements pour finir.",
                ExerciseLibrary.bestFor(catalog, setOf("quadriceps", "chest", "middle back", "abdominals"), allowed, limit = 4),
            )
            return out
        }

        painWarnings(known, profile)?.let { out += it }

        known.filter { it.cat!!.l == "e" }.takeIf { it.isNotEmpty() }?.let { list ->
            out += SessionAdvice(
                SessionAdvice.Level.WARNING, "Exercices de niveau expert",
                list.joinToString { it.ex.name } + " : technique exigeante, à réserver à une pratique encadrée ou confirmée.",
                exercises = list.map { it.index },
            )
        }

        known.filter { it.cat!!.e !in allowed }.takeIf { it.isNotEmpty() }?.let { list ->
            out += SessionAdvice(
                SessionAdvice.Level.TIP, "Matériel non déclaré",
                list.joinToString { "${it.ex.name} (${ExerciseLabels.equipment(it.cat!!.e).lowercase()})" } +
                    " : ce matériel n'est pas dans ton profil renfo.",
                exercises = list.map { it.index },
            )
        }

        // Équilibre poussée / tirage du haut du corps (séries)
        val strength = known.filter { it.cat!!.c != "stretching" }
        val push = strength.filter { ExerciseLibrary.upperBodyDirection(it.cat!!) == "push" }.sumOf { it.ex.sets }
        val pull = strength.filter { ExerciseLibrary.upperBodyDirection(it.cat!!) == "pull" }.sumOf { it.ex.sets }
        if (push >= pull + 3 || (push > 0 && pull == 0 && push >= 2)) {
            out += SessionAdvice(
                SessionAdvice.Level.TIP, "Plus de poussée que de tirage",
                "$push séries de poussée pour $pull de tirage : ajoute du dos (rowing, oiseau) pour garder des épaules équilibrées et une bonne posture.",
                ExerciseLibrary.bestFor(catalog, setOf("middle back", "lats"), allowed, exclude = inSession),
            )
        } else if (pull >= push + 3 || (pull > 0 && push == 0 && pull >= 2)) {
            out += SessionAdvice(
                SessionAdvice.Level.TIP, "Plus de tirage que de poussée",
                "$pull séries de tirage pour $push de poussée : une pompe ou un développé équilibrerait la séance.",
                ExerciseLibrary.bestFor(catalog, setOf("chest", "shoulders"), allowed, exclude = inSession),
            )
        }

        // Groupes majeurs oubliés (séance d'au moins 4 exercices de renfo)
        if (strength.size >= 4) {
            val worked = strength.flatMap { it.cat!!.muscles }.toSet()
            val missing = buildList {
                if (worked.none { it in setOf("quadriceps", "hamstrings", "glutes") }) add("les jambes" to setOf("quadriceps", "glutes"))
                if ("abdominals" !in worked) add("la sangle abdominale" to setOf("abdominals"))
                if (worked.none { it in setOf("lower back", "glutes", "hamstrings") }) add("la chaîne postérieure" to setOf("glutes", "hamstrings", "lower back"))
            }
            if (missing.isNotEmpty()) {
                out += SessionAdvice(
                    SessionAdvice.Level.TIP, "Groupes non travaillés",
                    "Rien pour " + missing.joinToString(", ") { it.first } + ".",
                    missing.flatMap { ExerciseLibrary.bestFor(catalog, it.second, allowed, exclude = inSession, limit = 2) }.distinctBy { it.id },
                )
            }
            // Prévention course : fessiers moyens, mollets, travail unilatéral
            val runner = worked.any { it in setOf("abductors", "glutes") } && "calves" in worked
            if (!runner) {
                out += SessionAdvice(
                    SessionAdvice.Level.TIP, "Prévention course",
                    "Pour un coureur, fessiers moyens (stabilité du bassin) et mollets / tendon d'Achille sont les meilleures assurances contre les blessures.",
                    listOfNotNull("Single_Leg_Glute_Bridge", "Monster_Walk", "Side_Leg_Raises", "Calf_Raise_On_A_Dumbbell", "Kettlebell_One-Legged_Deadlift")
                        .mapNotNull { catalog.byId(it) }
                        .filter { it.id !in inSession && it.e in allowed },
                )
            }
        }

        // Ordre : pliométrie et polyarticulaires avant l'isolation, étirements statiques à la fin
        orderTips(items)?.let { out += it }

        // Même muscle principal deux fois de suite → alterner ; antagonistes côte à côte → superserie
        val sameInARow = known.zipWithNext().filter { (a, b) ->
            b.index == a.index + 1 && a.cat!!.c == "strength" && b.cat!!.c == "strength" && a.cat.m == b.cat.m
        }
        if (sameInARow.isNotEmpty()) {
            val (a, b) = sameInARow.first()
            out += SessionAdvice(
                SessionAdvice.Level.TIP, "Même muscle deux fois de suite",
                "${a.ex.name} puis ${b.ex.name} sollicitent tous deux les ${ExerciseLabels.muscle(a.cat!!.m)}. " +
                    "Intercale un autre groupe pour mieux récupérer, ou garde-les volontairement enchaînés pour une pré-fatigue.",
                exercises = listOf(a.index, b.index),
            )
        }
        val supersets = known.zipWithNext().filter { (a, b) ->
            b.index == a.index + 1 && a.cat!!.c == "strength" && b.cat!!.c == "strength" &&
                b.cat.m in ExerciseLibrary.ANTAGONISTS[a.cat.m].orEmpty()
        }
        if (supersets.isNotEmpty()) {
            out += SessionAdvice(
                SessionAdvice.Level.GOOD, "Superséries possibles",
                supersets.joinToString(" ; ") { (a, b) -> "${a.ex.name} + ${b.ex.name}" } +
                    " : muscles opposés, tu peux les enchaîner sans repos entre les deux et gagner du temps.",
                exercises = supersets.flatMap { listOf(it.first.index, it.second.index) },
            )
        } else if (strength.isNotEmpty()) {
            val last = strength.last().cat!!
            ExerciseLibrary.complements(catalog, last, allowed, limit = 3).filter { it.id !in inSession }.takeIf { it.isNotEmpty() }?.let {
                out += SessionAdvice(
                    SessionAdvice.Level.TIP, "Idée de supersérie",
                    "Après ${strength.last().ex.name}, un exercice des muscles opposés s'enchaîne bien sans temps mort.",
                    it,
                )
            }
        }

        // Volume par groupe
        val setsByGroup = strength.groupBy { MuscleGroup.of(it.cat!!.m) }.mapValues { (_, l) -> l.sumOf { it.ex.sets } }
        setsByGroup.filter { it.key != null && it.value > 10 }.forEach { (g, n) ->
            out += SessionAdvice(
                SessionAdvice.Level.WARNING, "Beaucoup de volume : ${g!!.label.lowercase()}",
                "$n séries sur le même groupe dans une séance : au-delà d'une dizaine, le gain est faible et les courbatures gênent la course.",
            )
        }

        // Retour au calme
        val lastIsStretch = items.last().cat?.c == "stretching"
        if (strength.size >= 3 && !lastIsStretch) {
            val worked = strength.map { it.cat!!.m }.toSet()
            out += SessionAdvice(
                SessionAdvice.Level.TIP, "Retour au calme",
                "Termine par 1 ou 2 étirements doux des muscles travaillés.",
                ExerciseLibrary.bestFor(catalog, worked, allowed, category = "stretching", exclude = inSession, limit = 3),
            )
        }

        // Durée
        val target = profile.sessionMinutes * 60
        val minutes = (estimatedSec + 30) / 60
        if (target > 0 && estimatedSec > target * 1.25) {
            out += SessionAdvice(
                SessionAdvice.Level.WARNING, "Séance longue",
                "Environ $minutes min pour ${profile.sessionMinutes} min prévues : retire une série ou un exercice.",
            )
        }

        if (out.none { it.level != SessionAdvice.Level.GOOD } && strength.size >= 3) {
            out += SessionAdvice(SessionAdvice.Level.GOOD, "Séance équilibrée", "Rien à redire : groupes, ordre et volume sont cohérents.")
        }
        return out
    }

    private fun orderTips(items: List<Item>): SessionAdvice? {
        val notes = mutableListOf<String>()
        val idx = mutableListOf<Int>()
        val firstStrength = items.indexOfFirst { it.cat?.c == "strength" }
        // Étirement statique long (≥ 45 s) avant le renfo ; la mobilité courte en échauffement reste bienvenue
        items.filter { it.cat?.c == "stretching" && (it.ex.duration_sec ?: 0) >= 45 && it.index < firstStrength }.forEach {
            notes += "${it.ex.name} : les étirements statiques prolongés diminuent la force, garde-les pour la fin."
            idx += it.index
        }
        // Pliométrie après du renfo
        items.filter { it.cat?.c == "plyometrics" && firstStrength in 0 until it.index }.forEach {
            notes += "${it.ex.name} : les sauts demandent de la fraîcheur, place-les en début de séance."
            idx += it.index
        }
        // Isolation avant un polyarticulaire du même groupe
        items.forEach { a ->
            val ca = a.cat ?: return@forEach
            if (ca.k != "i" || ca.c != "strength") return@forEach
            val later = items.firstOrNull { b -> b.index > a.index && b.cat != null && b.cat.k == "c" && b.cat.c == "strength" && MuscleGroup.of(b.cat.m) == MuscleGroup.of(ca.m) }
            if (later != null) {
                notes += "${a.ex.name} avant ${later.ex.name} : fais plutôt le polyarticulaire d'abord, l'isolation ensuite."
                idx += a.index
            }
        }
        if (notes.isEmpty()) return null
        return SessionAdvice(SessionAdvice.Level.TIP, "Ordre des exercices", notes.joinToString("\n"), exercises = idx.distinct())
    }

    /** Zone douloureuse → exercices qui la chargent (avertissement, pas interdiction). */
    private val PAIN_RULES: Map<String, (CatalogExercise) -> Boolean> = mapOf(
        "Sciatique" to { e -> heavyBack(e) || (e.m == "hamstrings" && e.k == "c") || e.c == "plyometrics" },
        "Lombaires" to { e -> heavyBack(e) || e.c == "plyometrics" },
        "Dorsales" to { e -> e.c in HEAVY && e.muscles.any { it in setOf("middle back", "traps", "lats") } },
        "Cervicales / nuque" to { e -> e.m == "neck" || (e.m == "traps" && e.c != "stretching") },
        "Épaules" to { e -> e.c != "stretching" && ((e.m == "shoulders" && e.f == "push") || e.id.containsAny("Dip", "Upright_Row", "Muscle_Up", "Kipping")) },
        "Hanches" to { e -> e.c == "plyometrics" || (e.m in setOf("adductors", "abductors") && e.c == "strength" && e.l != "b") },
        "Genoux" to { e -> e.c == "plyometrics" || e.id.containsAny("Jump", "Lunge", "Pistol", "Sissy", "Split_Squat") },
        "Chevilles" to { e -> e.c == "plyometrics" || e.id.containsAny("Jump", "Hop", "Bound", "Skipping") },
        "Tendon d'Achille" to { e -> e.c == "plyometrics" || e.id.containsAny("Jump", "Hop", "Bound", "Skipping", "Rope_Jumping") },
        "Périoste / tibias" to { e -> e.c == "plyometrics" || e.id.containsAny("Jump", "Hop", "Bound", "Skipping", "Rope_Jumping", "Sprint") },
        "Voûte plantaire" to { e -> e.c == "plyometrics" || e.id.containsAny("Jump", "Hop", "Bound", "Rope_Jumping") },
        "Poignets" to { e ->
            e.c != "stretching" && (e.m == "forearms" || e.id.containsAny("Push-Up", "Pushup", "Push_Up", "Plank", "Dip", "Clean", "Snatch", "Mountain_Climber"))
        },
    )

    private val HEAVY = setOf("powerlifting", "olympic weightlifting", "strongman")

    private fun heavyBack(e: CatalogExercise) =
        e.c in HEAVY || e.id.containsAny("Deadlift", "Good_Morning", "Sit-Up", "Jackknife", "Swing") ||
            (e.m == "lower back" && e.c == "strength" && e.l != "b")

    private fun String.containsAny(vararg parts: String) = parts.any { contains(it, ignoreCase = true) }

    private fun painWarnings(known: List<Item>, profile: StrengthProfile): SessionAdvice? {
        if (profile.painAreas.isEmpty()) return null
        val hits = profile.painAreas.mapNotNull { area ->
            val rule = PAIN_RULES[area] ?: return@mapNotNull null
            known.filter { rule(it.cat!!) }.takeIf { it.isNotEmpty() }?.let { area to it }
        }
        if (hits.isEmpty()) return null
        val text = hits.joinToString("\n") { (area, list) -> "$area : " + list.joinToString { it.ex.name } }
        return SessionAdvice(
            SessionAdvice.Level.WARNING, "À surveiller avec tes douleurs",
            text + "\nCommence par la version facile et arrête si la douleur dépasse 3/10 ou augmente le lendemain.",
            exercises = hits.flatMap { (_, l) -> l.map { it.index } }.distinct(),
        )
    }
}
