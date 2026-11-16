package com.mathieu.madtrainer.coach

import com.mathieu.madtrainer.data.BodyCache
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.HrZones
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.data.formatPace
import com.mathieu.madtrainer.data.Vma
import com.mathieu.madtrainer.strava.StravaActivity
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Repères tirés de l'historique (app + Strava) sur les dernières semaines. */
data class PerfSnapshot(
    /** Allure médiane des sorties course (s/km), null si aucune sortie récente. */
    val runPaceSecKm: Int? = null,
    /** Plus longue sortie course des 6 dernières semaines (min). */
    val longestRunMin: Int? = null,
    /** Minutes de cardio par semaine (moyenne des 4 dernières semaines). */
    val weeklyCardioMin: Int? = null,
    /** Nombre de sorties course sur 4 semaines. */
    val runsLast4Weeks: Int = 0,
)

/** Données d'entrée du plan sans IA. */
data class PlannerInput(
    val profile: UserProfile,
    val sessions: List<SessionRecord> = emptyList(),
    val strava: List<StravaActivity> = emptyList(),
    val strength: StrengthProfile? = null,
    /** Jours déjà occupés par le renfo (« Mardi »…) : on y évite les séances intenses. */
    val strengthDays: List<String> = emptyList(),
    val body: BodyCache? = null,
    val now: Long = System.currentTimeMillis(),
)

/**
 * Plan course + elliptique construit par règles d'entraînement classiques, sans IA :
 * - volume de départ calé sur l'historique récent, +10 % par semaine, semaine allégée toutes les 4 semaines ;
 * - 80 % du temps en endurance (zones 1-2), séances de qualité selon l'objectif et le niveau ;
 * - course limitée en cas de douleur aux membres inférieurs ou de surpoids (l'elliptique prend le relais) ;
 * - allures tirées des sorties récentes, sinon consignes en zones cardio.
 * Produit un [CoachPlan] identique à celui de l'IA (même import, même affichage).
 */
object RulePlanner {
    private const val DAY = 86_400_000L

    enum class Kind { ENDURANCE, LONG, TEMPO, INTERVALS, VO2, RECOVERY }

    private val LOWER_BODY = listOf("genou", "cheville", "hanche", "tendon", "achille", "mollet", "pied", "tibia", "dos", "lomb", "sciatique", "ischio", "quadri")

    private val DAYS_BY_COUNT = mapOf(
        1 to listOf(5),
        2 to listOf(2, 5),
        3 to listOf(0, 2, 5),
        4 to listOf(0, 2, 4, 6),
        5 to listOf(0, 1, 3, 4, 6),
        6 to listOf(0, 1, 2, 4, 5, 6),
        7 to listOf(0, 1, 2, 3, 4, 5, 6),
    )

    // ------------------------------------------------------------------ repères

    fun snapshot(sessions: List<SessionRecord>, strava: List<StravaActivity>, now: Long): PerfSnapshot {
        data class Act(val t: Long, val run: Boolean, val sec: Int, val distM: Double)

        val acts = mutableListOf<Act>()
        sessions.forEach { s ->
            if (s.durationSec > 0) acts += Act(s.startedAt, s.sport == Sport.RUNNING, s.durationSec, s.distanceM.toDouble())
        }
        strava.forEach { a ->
            val t = runCatching { Instant.parse(a.start_date).toEpochMilli() }.getOrNull() ?: return@forEach
            val type = a.sport_type.ifBlank { a.type }
            val run = type in listOf("Run", "TrailRun", "VirtualRun")
            val cardio = run || type in listOf("Ride", "VirtualRide", "Elliptical", "Rowing", "Swim", "Hike", "Walk", "EBikeRide", "MountainBikeRide")
            // Doublon d'une séance de l'app envoyée sur Strava : même départ à 10 min près
            if (cardio && acts.none { abs(it.t - t) < 10 * 60_000L }) acts += Act(t, run, a.moving_time, a.distance)
        }
        val recent4 = acts.filter { it.t in (now - 28 * DAY)..now }
        val runs8 = acts.filter { it.run && it.t in (now - 56 * DAY)..now && it.distM >= 1000 }
        val paces = runs8.map { it.sec / (it.distM / 1000.0) }.filter { it in 180.0..900.0 }.sorted()
        return PerfSnapshot(
            runPaceSecKm = paces.takeIf { it.isNotEmpty() }?.let { it[it.size / 2].roundToInt() },
            longestRunMin = acts.filter { it.run && it.t in (now - 42 * DAY)..now }.maxOfOrNull { it.sec / 60 },
            weeklyCardioMin = recent4.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.sec } / 60 / 4 },
            runsLast4Weeks = recent4.count { it.run },
        )
    }

    /** Course déconseillée (douleur forte des membres inférieurs) / à limiter (douleur modérée ou IMC ≥ 30). */
    private fun runLimit(p: UserProfile, s: StrengthProfile?): Int? {
        val text = (s?.painAreas.orEmpty().joinToString(" ") + " " + p.constraints).lowercase()
        val lower = LOWER_BODY.any { it in text }
        val pain = s?.painLevel ?: 0
        return when {
            lower && pain >= 6 -> 0
            (lower && pain >= 3) || p.bmi >= 30 -> 1
            else -> null
        }
    }

    // ------------------------------------------------------------------ plan

    fun build(input: PlannerInput): CoachPlan {
        val p = input.profile
        val zones = p.hrZones
        val perf = snapshot(input.sessions, input.strava, input.now)
        val n = p.sessionsPerWeek.coerceIn(1, 7)
        val weeks = p.horizonWeeks.coerceIn(1, 16)
        val limit = runLimit(p, input.strength)
        val beginner = p.level == Level.BEGINNER

        // Volume de départ : historique récent si connu, sinon selon le niveau
        val targetWeekly = n * p.sessionMinutes.coerceIn(15, 180)
        val start = perf.weeklyCardioMin?.let { (it * 1.1 / targetWeekly).coerceIn(0.6, 1.0) }
            ?: when (p.level) { Level.BEGINNER -> 0.7; Level.INTERMEDIATE -> 0.85; Level.ADVANCED -> 1.0 }

        val strengthIdx = input.strengthDays.map { WeekPlanner.dayIndex(it) }.filter { it >= 0 }.toSet()
        val workouts = mutableListOf<CoachWorkout>()

        for (w in 1..weeks) {
            val deload = weeks >= 4 && w % 4 == 0
            val ramp = (start * Math.pow(1.1, (w - 1).toDouble())).coerceAtMost(1.1)
            val factor = if (deload) ramp * 0.75 else ramp
            val kinds = weekKinds(n, p, w, deload)
            val days = placeDays(kinds, DAYS_BY_COUNT.getValue(n), strengthIdx)
            val sports = assignSports(kinds, p, limit, beginner, w)
            kinds.forEachIndexed { i, kind ->
                val sport = sports[i]
                var minutes = p.sessionMinutes * factor * when (kind) {
                    Kind.LONG -> 1.4
                    Kind.RECOVERY -> 0.7
                    else -> 1.0
                }
                if (kind == Kind.LONG && sport == Sport.RUNNING && perf.longestRunMin != null) {
                    // Sortie longue : pas plus de +10 % par semaine par rapport à la plus longue récente
                    minutes = minutes.coerceAtMost(maxOf(perf.longestRunMin * Math.pow(1.1, w.toDouble()), 30.0))
                }
                val total = (((minutes / 5).roundToInt()) * 5).coerceIn(20, 150)
                val built = session(kind, sport, total, zones, perf, p, beginner, w)
                workouts += CoachWorkout(
                    name = built.name + if (sport == Sport.RUNNING) " (course)" else " (elliptique)",
                    sport = sport.name,
                    week = w,
                    day = WeekPlanner.DAYS[days[i]],
                    purpose = built.purpose + if (deload) " Semaine allégée : récupère et assimile." else "",
                    steps = built.steps,
                )
            }
        }
        return CoachPlan(
            plan_name = "Plan perso ${weeks} semaines (sans IA)",
            summary = summary(p, perf, limit, n, weeks),
            weekly_structure = structure(n, p, limit),
            advice = advice(p, perf, zones),
            nutrition = nutrition(p, input.body, workouts.filter { it.week == 1 }),
            recovery = listOf(
                "Au moins un jour sans séance cardio par semaine${if (n >= 6) " (ou une récupération très douce)" else ""}.",
                "Toutes les 4 semaines, la semaine est allégée d'environ 25 % : c'est là que la forme progresse.",
                "Si la FC de repos du matin est plus haute que d'habitude de 7 bpm ou plus, remplace la séance du jour par une récupération.",
            ),
            progression = "Volume de départ à ${(start * 100).roundToInt()} % de ta cible (${targetWeekly} min/semaine), " +
                "+10 % par semaine jusqu'à 110 %, semaine allégée toutes les 4 semaines. Les séances de qualité arrivent " +
                (if (beginner) "à partir de la semaine 3." else "dès la première semaine."),
            warnings = warnings(p, limit, input.strength),
            workouts = workouts,
        )
    }

    /** Types de séances de la semaine (une séance longue en dernier, la qualité en premier). */
    fun weekKinds(n: Int, p: UserProfile, week: Int, deload: Boolean): List<Kind> {
        var quality = when (p.level) {
            Level.BEGINNER -> if (n >= 3 && week >= 3) 1 else 0
            Level.INTERMEDIATE -> if (n >= 2) 1 else 0
            Level.ADVANCED -> if (n >= 4) 2 else if (n >= 2) 1 else 0
        }
        if (deload) quality = minOf(quality, 1)
        val q1 = when (p.goal) {
            Goal.PERFORMANCE -> Kind.INTERVALS
            Goal.ENDURANCE -> if (week % 2 == 0) Kind.INTERVALS else Kind.TEMPO
            Goal.FITNESS -> if (week % 2 == 0) Kind.INTERVALS else Kind.TEMPO
            Goal.WEIGHT_LOSS, Goal.CARDIO_HEALTH -> Kind.TEMPO
        }
        val q2 = if (p.goal == Goal.PERFORMANCE) Kind.VO2 else if (q1 == Kind.TEMPO) Kind.INTERVALS else Kind.TEMPO
        val out = MutableList(n) { Kind.ENDURANCE }
        if (n >= 2) out[n - 1] = Kind.LONG
        if (quality >= 1) out[0] = q1
        if (quality >= 2) out[maxOf(1, n - 3)] = q2
        if (n >= 5 && out[1] == Kind.ENDURANCE) out[1] = Kind.RECOVERY
        return out
    }

    /** Jours de la semaine : on évite les séances intenses les jours de renfo en échangeant avec une endurance. */
    private fun placeDays(kinds: List<Kind>, days: List<Int>, strength: Set<Int>): List<Int> {
        val d = days.toMutableList()
        kinds.forEachIndexed { i, k ->
            if ((k == Kind.TEMPO || k == Kind.INTERVALS || k == Kind.VO2) && d[i] in strength) {
                val j = kinds.indices.firstOrNull { kinds[it] == Kind.ENDURANCE && d[it] !in strength }
                if (j != null) d[i] = d[j].also { d[j] = d[i] }
            }
        }
        return d
    }

    /** Course ou elliptique pour chaque séance. */
    private fun assignSports(kinds: List<Kind>, p: UserProfile, limit: Int?, beginner: Boolean, week: Int): List<Sport> {
        val n = kinds.size
        var runs = when (p.goal) {
            Goal.ENDURANCE, Goal.PERFORMANCE -> ceil(n * 0.6).toInt()
            else -> ceil(n * 0.5).toInt()
        }.coerceAtMost(4)
        if (beginner) runs = runs.coerceAtMost(if (week <= 2) 2 else 3)
        if (limit != null) runs = runs.coerceAtMost(limit)
        val out = MutableList(n) { Sport.ELLIPTICAL }
        if (runs == 0) return out
        // Course limitée : une sortie facile. Sinon : sortie longue, puis qualité, puis endurance.
        val priority = if (limit == 1) {
            listOf(Kind.ENDURANCE, Kind.LONG)
        } else {
            listOf(Kind.LONG, Kind.INTERVALS, Kind.TEMPO, Kind.VO2, Kind.ENDURANCE)
        }
        priority.forEach { k ->
            kinds.indices.filter { kinds[it] == k }.forEach { i ->
                if (runs > 0 && out[i] == Sport.ELLIPTICAL) {
                    out[i] = Sport.RUNNING
                    runs--
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ séances

    data class Built(val name: String, val purpose: String, val steps: List<CoachStep>)

    /** Étapes en durée (minutes) ; les étapes vides ou négatives sont ignorées. */
    private class StepsBuilder {
        val steps = mutableListOf<CoachStep>()
        fun zone(name: String, min: Double, z: Int) {
            if (min >= 0.5) steps += CoachStep(name, (min * 60).roundToInt(), "HR_ZONE", z.toDouble())
        }
        @Suppress("UNUSED_PARAMETER")
        fun pace(name: String, min: Double, secKm: Int, z: Int) {
            if (min >= 0.5) steps += CoachStep(name, (min * 60).roundToInt(), "PACE", secKm.toDouble())
        }
    }

    fun session(kind: Kind, sport: Sport, total: Int, zones: HrZones, perf: PerfSnapshot, p: UserProfile, beginner: Boolean, week: Int): Built {
        val run = sport == Sport.RUNNING
        val b = StepsBuilder()
        val pace = if (run) perf.runPaceSecKm else null
        // VMA validée : allures en % de VMA, sinon décalage par rapport à l'allure habituelle
        val vma = if (run) p.vmaKmh else null
        fun z(n: Int) = zones.range(n).let { "${it.first}–${it.last} bpm" }
        val name: String
        val purpose: String
        when (kind) {
            Kind.RECOVERY -> {
                name = "Récupération active"
                b.zone("Échauffement", 5.0, 1)
                b.zone("Récupération active", total - 5.0, 1)
                purpose = "Très facile, zone 1 (${z(1)}) : fait circuler le sang sans fatiguer."
            }
            Kind.ENDURANCE, Kind.LONG -> {
                name = if (kind == Kind.LONG) "Sortie longue" else "Endurance fondamentale"
                val warm = if (kind == Kind.LONG) 10.0 else 8.0
                b.zone("Échauffement", warm, 1)
                val body = total - warm - 5.0
                if (run && beginner && week <= 6) {
                    // Course-marche : blocs de course qui s'allongent chaque semaine
                    val runMin = (2 + week).coerceAtMost(8).toDouble()
                    val reps = (body / (runMin + 1)).toInt().coerceAtLeast(1)
                    repeat(reps) { i ->
                        b.zone("Course ${i + 1}/$reps", runMin, 2)
                        b.zone("Marche", 1.0, 1)
                    }
                    b.zone("Course facile", body - reps * (runMin + 1), 2)
                } else {
                    b.zone(if (kind == Kind.LONG) "Endurance longue" else "Endurance", body, 2)
                }
                b.zone("Retour au calme", 5.0, 1)
                purpose = (if (kind == Kind.LONG) "Séance longue de la semaine, " else "") +
                    "zone 2 (${z(2)}) : tu dois pouvoir parler en phrases complètes. C'est elle qui construit l'endurance" +
                    (if (p.goal == Goal.WEIGHT_LOSS) " et utilise le plus les graisses." else ".")
            }
            Kind.TEMPO -> {
                name = "Tempo"
                val block = if (beginner) 5.0 else if (p.level == Level.ADVANCED) 10.0 else 8.0
                b.zone("Échauffement", 10.0, 1)
                val avail = total - 15.0
                val reps = (avail / (block + 3)).toInt().coerceIn(1, 4)
                val tempoPace = vma?.let { Vma.paceAt(it, 0.87) } ?: pace?.let { (it - 25).coerceIn(180, 800) }
                repeat(reps) { i ->
                    if (tempoPace != null) b.pace("Tempo ${i + 1}/$reps", block, tempoPace, 3) else b.zone("Tempo ${i + 1}/$reps", block, 3)
                    b.zone("Récupération", 3.0, 2)
                }
                b.zone("Endurance", avail - reps * (block + 3), 2)
                b.zone("Retour au calme", 5.0, 1)
                purpose = "Blocs « confortablement difficiles » en zone 3 (${z(3)})" +
                    (tempoPace?.let { ", environ ${formatPace(it)}/km" } ?: "") + " : améliore le seuil et l'économie de course."
            }
            Kind.INTERVALS -> {
                name = "Fractionné"
                val work = if (p.level == Level.ADVANCED) 4.0 else 3.0
                b.zone("Échauffement", 6.0, 1)
                b.zone("Échauffement actif", 6.0, 2)
                val avail = total - 17.0
                val reps = (avail / (work + 2)).toInt().coerceIn(2, 8)
                val ivPace = vma?.let { Vma.paceAt(it, 0.93) } ?: pace?.let { (it - 50).coerceIn(170, 750) }
                repeat(reps) { i ->
                    if (ivPace != null) b.pace("Rapide ${i + 1}/$reps", work, ivPace, 4) else b.zone("Rapide ${i + 1}/$reps", work, 4)
                    b.zone("Récupération", 2.0, 2)
                }
                b.zone("Endurance", avail - reps * (work + 2), 2)
                b.zone("Retour au calme", 5.0, 1)
                purpose = "Répétitions en zone 4 (${z(4)})" + (ivPace?.let { ", environ ${formatPace(it)}/km" } ?: "") +
                    " : développe la capacité cardio. La récupération en zone 2 doit faire redescendre le cardio."
            }
            Kind.VO2 -> {
                name = "VMA courte"
                b.zone("Échauffement", 6.0, 1)
                b.zone("Échauffement actif", 6.0, 2)
                val avail = total - 17.0
                val reps = (avail / 3).toInt().coerceIn(4, 12)
                val vPace = vma?.let { Vma.paceAt(it, 1.0) } ?: pace?.let { (it - 75).coerceIn(160, 700) }
                repeat(reps) { i ->
                    if (vPace != null) b.pace("Vite ${i + 1}/$reps", 1.0, vPace, 5) else b.zone("Vite ${i + 1}/$reps", 1.0, 5)
                    b.zone("Récupération", 2.0, 1)
                }
                b.zone("Endurance", avail - reps * 3, 2)
                b.zone("Retour au calme", 5.0, 1)
                purpose = "1 min très vite en zone 5 (${z(5)}), 2 min de récupération : travaille la puissance maximale aérobie."
            }
        }
        return Built(name, purpose, b.steps)
    }

    // ------------------------------------------------------------------ textes

    private fun summary(p: UserProfile, perf: PerfSnapshot, limit: Int?, n: Int, weeks: Int): String = buildString {
        append("Plan de $weeks semaines, $n séances par semaine d'environ ${p.sessionMinutes} min, objectif « ${p.goal.label} », niveau ${p.level.label.lowercase()}. ")
        append("Construit par règles d'entraînement à partir de ton profil")
        val bits = mutableListOf<String>()
        perf.weeklyCardioMin?.let { bits += "$it min de cardio par semaine ces 4 dernières semaines" }
        perf.runPaceSecKm?.let { bits += "allure habituelle ${formatPace(it)}/km" }
        p.vmaKmh?.let { bits += "VMA ${Vma.fmt(it)} km/h" }
        perf.longestRunMin?.let { bits += "sortie la plus longue récente $it min" }
        if (bits.isNotEmpty()) append(" et de ton historique (${bits.joinToString(", ")})")
        append(". ")
        when (limit) {
            0 -> append("La course est mise de côté à cause de la douleur : tout se fait sur l'elliptique, sans impact.")
            1 -> append("Une seule sortie course facile par semaine pour limiter les chocs ; l'elliptique fait le reste.")
        }
    }

    private fun structure(n: Int, p: UserProfile, limit: Int?): String = buildString {
        append("Environ 80 % du temps en zones 1-2, le reste en séances de qualité. ")
        if (n >= 2) append("Une séance longue en fin de semaine. ")
        if (p.level == Level.BEGINNER) append("Course-marche les premières semaines. ")
        if (limit == null) append("Course et elliptique alternés pour répartir les impacts.")
    }

    private fun advice(p: UserProfile, perf: PerfSnapshot, zones: HrZones): List<String> = buildList {
        add("Tes zones : ${zones.describe()}" + if (zones.custom) " (importées de Strava)." else " (estimées à partir de la FC max ${p.estimatedMaxHr} bpm).")
        if (perf.runPaceSecKm == null && p.vmaKmh == null) add("Pas de sortie course récente : les consignes de course sont en zones cardio ; les allures seront calculées dès que tu auras couru.")
        add("En zone 2, si le cardio dérive au-dessus de la zone, ralentis ou marche : c'est normal en début de plan.")
        add("Régénère le plan toutes les 4 semaines : il se recale sur tes nouvelles performances et ton poids.")
    }

    private fun nutrition(p: UserProfile, body: BodyCache?, week1: List<CoachWorkout>): List<String> {
        val workouts = CoachParser.toGlobalWorkouts(CoachPlan(workouts = week1))
        return NutritionPlanner.build(p, body, workouts).summaryLines()
    }

    private fun warnings(p: UserProfile, limit: Int?, s: StrengthProfile?): List<String> = buildList {
        add("Plan généré sans IA, par règles générales : il ne remplace pas un avis médical.")
        if (limit != null && s != null && s.painAreas.isNotEmpty()) {
            add("Douleurs signalées (${s.painAreas.joinToString(", ")}, ${s.painLevel}/10) : arrête la séance si la douleur augmente pendant l'effort.")
        }
        if (p.age >= 45 || p.bmi >= 30) add("Si tu reprends après une longue pause, un bilan médical avant les séances intenses est conseillé.")
    }
}
