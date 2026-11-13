package com.mathieu.madtrainer.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Respect de la consigne d'une étape. */
data class StepAdherence(
    val index: Int,
    val name: String,
    val target: String,
    /** % du temps dans la cible (null si non mesurable : étape libre, pas de cardio…) */
    val inTargetPct: Int?,
    val avgHr: Int?,
    /** Valeur moyenne réalisée, en texte (« 152 W », « 5'42 /km », « niv. 8 ») */
    val achieved: String?,
)

/** Rapport de progression calculé en fin de séance (sans IA). */
data class ProgressReport(
    /** Points marquants, phrases courtes en français */
    val highlights: List<String>,
    /** Minutes passées dans chaque zone cardio 1..5 */
    val zoneMinutes: List<Int>,
    val steps: List<StepAdherence>,
    /** Dérive cardiaque (%) entre la 1re et la 2e moitié à effort comparable, si mesurable */
    val hrDriftPct: Double?,
)

object SessionReport {

    /** Séances comparables : même nom de programme si possible, sinon même sport et durée proche. */
    fun comparable(rec: SessionRecord, history: List<SessionRecord>): List<SessionRecord> {
        val before = history.filter { it.id != rec.id && it.sport == rec.sport && it.startedAt < rec.startedAt }
            .sortedByDescending { it.startedAt }
        val sameName = rec.workoutName?.let { n -> before.filter { it.workoutName == n } }.orEmpty()
        return sameName.ifEmpty {
            before.filter { abs(it.durationSec - rec.durationSec) <= maxOf(300, rec.durationSec / 4) }
        }.take(5)
    }

    /** Efficacité : watts par battement (elliptique) ou mètres par minute par battement (course). */
    fun efficiency(r: SessionRecord): Double? {
        val hr = r.avgHr?.takeIf { it > 60 } ?: return null
        return when (r.sport) {
            Sport.ELLIPTICAL -> r.avgPower.takeIf { it > 0 }?.let { it.toDouble() / hr }
            Sport.RUNNING -> r.avgPaceSecPerKm?.takeIf { it > 0 }?.let { (60_000.0 / it) / hr }
        }
    }

    /** Index d'étape de chaque échantillon (enregistré, sinon déduit des durées). */
    private fun stepIndexOf(s: Sample, steps: List<WorkoutStep>): Int? {
        s.step?.let { return it }
        if (steps.isEmpty()) return null
        var acc = 0
        steps.forEachIndexed { i, st ->
            acc += st.durationSec
            if (s.t <= acc) return i
        }
        return steps.lastIndex
    }

    fun stepAdherence(rec: SessionRecord, zones: HrZones): List<StepAdherence> {
        if (rec.steps.isEmpty()) return emptyList()
        val bySteps = rec.samples.groupBy { stepIndexOf(it, rec.steps) }
        return rec.steps.mapIndexed { i, step ->
            val ss = bySteps[i].orEmpty()
            // On laisse 45 s au cardio pour se stabiliser avant de juger une étape en zone
            val settled = if (ss.size > 90) ss.drop(45) else ss
            val hrs = ss.mapNotNull { it.hr }.filter { it > 0 }
            val pct: Int?
            val achieved: String?
            when (step.mode) {
                StepMode.HR_ZONE -> {
                    val r = zones.range(step.target.roundToInt())
                    val h = settled.mapNotNull { it.hr }.filter { it > 0 }
                    pct = if (h.isEmpty()) null else (100.0 * h.count { it in (r.first - 3)..(r.last + 3) } / h.size).roundToInt()
                    achieved = if (hrs.isEmpty()) null else "${hrs.average().roundToInt()} bpm"
                }
                StepMode.PACE -> {
                    val p = settled.mapNotNull { it.paceSecPerKm }
                    pct = if (p.isEmpty()) null else (100.0 * p.count { abs(it - step.target) <= 15 } / p.size).roundToInt()
                    achieved = if (p.isEmpty()) null else formatPace(p.average().roundToInt())
                }
                StepMode.POWER -> {
                    val p = settled.mapNotNull { it.power }.filter { it > 0 }
                    pct = if (p.isEmpty()) null else (100.0 * p.count { abs(it - step.target) <= step.target * 0.1 + 5 } / p.size).roundToInt()
                    achieved = if (p.isEmpty()) null else "${p.average().roundToInt()} W"
                }
                StepMode.RESISTANCE -> {
                    val r = ss.mapNotNull { it.resistance }
                    pct = if (r.isEmpty()) null else (100.0 * r.count { abs(it - step.target) < 1.0 } / r.size).roundToInt()
                    val p = ss.mapNotNull { it.power }.filter { it > 0 }
                    achieved = listOfNotNull(
                        r.takeIf { it.isNotEmpty() }?.let { "niv. ${it.average().roundToInt()}" },
                        p.takeIf { it.isNotEmpty() }?.let { "${it.average().roundToInt()} W" },
                    ).joinToString(", ").ifBlank { null }
                }
                StepMode.FREE -> {
                    pct = null
                    achieved = null
                }
            }
            StepAdherence(i, step.name, RunEngine.targetText(step, zones), pct, if (hrs.isEmpty()) null else hrs.average().roundToInt(), achieved)
        }
    }

    /** Dérive cardiaque : hausse du cardio par unité d'effort entre la 1re et la 2e moitié (séances ≥ 20 min). */
    fun hrDrift(rec: SessionRecord): Double? {
        if (rec.durationSec < 20 * 60) return null
        val half = rec.samples.size / 2
        fun ratio(part: List<Sample>): Double? {
            val pairs = part.mapNotNull { s ->
                val hr = s.hr?.takeIf { it > 60 } ?: return@mapNotNull null
                val effort = when (rec.sport) {
                    Sport.ELLIPTICAL -> s.power?.takeIf { it > 0 }?.toDouble()
                    Sport.RUNNING -> s.paceSecPerKm?.takeIf { it > 0 }?.let { 60_000.0 / it }
                } ?: return@mapNotNull null
                hr / effort
            }
            return if (pairs.size < 60) null else pairs.average()
        }
        val a = ratio(rec.samples.take(half)) ?: return null
        val b = ratio(rec.samples.drop(half)) ?: return null
        return (b / a - 1) * 100
    }

    fun build(rec: SessionRecord, history: List<SessionRecord>, zones: HrZones): ProgressReport {
        val out = mutableListOf<String>()
        val zoneSec = IntArray(5)
        rec.samples.forEach { s -> s.hr?.takeIf { it > 0 }?.let { zoneSec[zones.zoneOf(it) - 1]++ } }
        val steps = stepAdherence(rec, zones)

        // Respect des consignes
        val measured = steps.filter { it.inTargetPct != null }
        if (measured.isNotEmpty()) {
            val weighted = measured.sumOf { (it.inTargetPct ?: 0) * (rec.steps.getOrNull(it.index)?.durationSec ?: 1) }.toDouble() /
                measured.sumOf { rec.steps.getOrNull(it.index)?.durationSec ?: 1 }
            out += "Consignes respectées ${weighted.roundToInt()} % du temps" +
                (measured.minByOrNull { it.inTargetPct ?: 100 }?.takeIf { (it.inTargetPct ?: 100) < 60 }?.let { " (le plus dur : « ${it.name} », ${it.inTargetPct} %)" } ?: "")
        }

        // Comparaison avec les séances comparables
        val prev = comparable(rec, history)
        prev.firstOrNull()?.let { p ->
            val df = SimpleDateFormat("d MMM", Locale.FRANCE)
            val label = if (p.workoutName == rec.workoutName && rec.workoutName != null) "la dernière fois (${df.format(Date(p.startedAt))})" else "une séance comparable du ${df.format(Date(p.startedAt))}"
            when (rec.sport) {
                Sport.ELLIPTICAL -> if (rec.avgPower > 0 && p.avgPower > 0) {
                    val d = rec.avgPower - p.avgPower
                    out += "Puissance moyenne ${rec.avgPower} W, ${signed(d)} W par rapport à $label"
                }
                Sport.RUNNING -> {
                    val a = rec.avgPaceSecPerKm
                    val b = p.avgPaceSecPerKm
                    if (a != null && b != null) {
                        val d = b - a
                        out += "Allure ${formatPace(a)} /km, " + (if (d >= 0) "$d s plus rapide" else "${-d} s plus lent") + " que $label"
                    }
                }
            }
            val ha = rec.avgHr
            val hb = p.avgHr
            if (ha != null && hb != null) out += "Cardio moyen $ha bpm (${signed(ha - hb)} bpm)"
        }
        val eff = efficiency(rec)
        val prevEff = prev.mapNotNull { efficiency(it) }
        if (eff != null && prevEff.size >= 2) {
            val d = (eff / prevEff.average() - 1) * 100
            out += when {
                d >= 3 -> "Efficacité cardio en hausse de ${d.roundToInt()} % : plus d'effort pour le même cardio, la forme progresse"
                d <= -3 -> "Efficacité cardio en baisse de ${(-d).roundToInt()} % : fatigue, chaleur ou manque de récupération ?"
                else -> "Efficacité cardio stable par rapport aux ${prevEff.size} dernières séances comparables"
            }
        }

        // Records
        val sameSport = history.filter { it.sport == rec.sport && it.id != rec.id }
        if (sameSport.isNotEmpty()) {
            if (rec.durationSec > sameSport.maxOf { it.durationSec }) out += "Record : ta plus longue séance de ${rec.sport.label.lowercase()}"
            if (rec.sport == Sport.RUNNING && rec.distanceM >= 3000) {
                val best = sameSport.filter { it.distanceM >= 3000 }.mapNotNull { it.avgPaceSecPerKm }.minOrNull()
                val mine = rec.avgPaceSecPerKm
                if (best != null && mine != null && mine < best) out += "Record : meilleure allure sur une sortie de 3 km et plus"
            }
            if (rec.sport == Sport.ELLIPTICAL && rec.durationSec >= 600) {
                val best = sameSport.filter { it.durationSec >= 600 }.maxOfOrNull { it.avgPower } ?: 0
                if (best > 0 && rec.avgPower > best) out += "Record : meilleure puissance moyenne sur 10 min et plus"
            }
        }

        // Dérive cardiaque
        val drift = hrDrift(rec)
        if (drift != null && drift >= 5) out += "Dérive cardiaque de ${drift.roundToInt()} % en 2e moitié : endurance à consolider (hydratation, allure de départ)"

        // Volume de la semaine
        val monday = WeekPlanner.mondayOf(rec.startedAt)
        val week = (history.filter { it.id != rec.id } + rec).filter { it.startedAt >= monday && it.startedAt < monday + WeekPlanner.WEEK_MS }
        val lastWeek = history.filter { it.startedAt >= monday - WeekPlanner.WEEK_MS && it.startedAt < monday }
        val wMin = week.sumOf { it.durationSec } / 60
        val lMin = lastWeek.sumOf { it.durationSec } / 60
        out += "Cette semaine : ${week.size} séance(s), $wMin min" + if (lMin > 0) " (semaine dernière : $lMin min)" else ""

        return ProgressReport(out, zoneSec.map { (it + 30) / 60 }, steps, drift)
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    /** Prompt d'analyse IA de la séance (réponse attendue en texte, pas de JSON). */
    fun prompt(
        rec: SessionRecord,
        report: ProgressReport,
        history: List<SessionRecord>,
        zones: HrZones,
        profileText: String,
        planText: String? = null,
    ): String = buildString {
        val df = SimpleDateFormat("EEEE d MMMM yyyy à HH:mm", Locale.FRANCE)
        appendLine("Tu es mon coach sportif. Analyse la séance que je viens de terminer et dis-moi où j'en suis, en français, de façon concrète et encourageante mais honnête.")
        appendLine()
        appendLine("## Mon profil")
        appendLine(profileText.trim())
        appendLine("- Zones cardio : " + (1..5).joinToString(", ") { z -> zones.range(z).let { "Z$z ${it.first}–${it.last}" } } + " bpm")
        appendLine()
        appendLine("## Séance du ${df.format(Date(rec.startedAt))}")
        appendLine("- Sport : ${rec.sport.label}, programme : ${rec.workoutName ?: "séance libre"}")
        appendLine("- Durée ${formatDuration(rec.durationSec)}" + when (rec.sport) {
            Sport.RUNNING -> ", distance ${"%.2f".format(Locale.FRANCE, rec.distanceM / 1000.0)} km, allure ${formatPace(rec.avgPaceSecPerKm)} /km"
            Sport.ELLIPTICAL -> ", puissance moy. ${rec.avgPower} W (max ${rec.maxPower}), cadence ${rec.avgCadence.roundToInt()}, ${rec.kcal} kcal"
        })
        appendLine("- Cardio moyen ${rec.avgHr ?: "?"} bpm, max ${rec.maxHr ?: rec.samples.mapNotNull { it.hr }.maxOrNull() ?: "?"} bpm")
        appendLine("- Temps par zone : " + report.zoneMinutes.mapIndexed { i, m -> "Z${i + 1} $m min" }.joinToString(", "))
        report.hrDriftPct?.let { appendLine("- Dérive cardiaque 2e moitié : ${"%.1f".format(Locale.FRANCE, it)} %") }
        if (report.steps.isNotEmpty()) {
            appendLine("- Étapes (consigne → réalisé) :")
            report.steps.forEach { s ->
                appendLine("  ${s.index + 1}. ${s.name} : ${s.target} → " + listOfNotNull(s.achieved, s.avgHr?.let { "cardio $it" }, s.inTargetPct?.let { "$it % dans la cible" }).joinToString(", ").ifBlank { "—" })
            }
        }
        appendLine()
        appendLine("## Ce que l'app a relevé")
        report.highlights.forEach { appendLine("- $it") }
        appendLine()
        val recent = history.filter { it.id != rec.id && it.startedAt < rec.startedAt }.sortedByDescending { it.startedAt }.take(10)
        if (recent.isNotEmpty()) {
            val d2 = SimpleDateFormat("dd/MM", Locale.FRANCE)
            appendLine("## Mes séances précédentes")
            recent.forEach { r ->
                appendLine(
                    "- ${d2.format(Date(r.startedAt))} ${r.sport.label} « ${r.workoutName ?: "libre"} » ${r.durationSec / 60} min, " +
                        (if (r.sport == Sport.RUNNING) "${"%.1f".format(Locale.FRANCE, r.distanceM / 1000.0)} km à ${formatPace(r.avgPaceSecPerKm)}" else "${r.avgPower} W") +
                        (r.avgHr?.let { ", FC $it" } ?: ""),
                )
            }
            appendLine()
        }
        if (!planText.isNullOrBlank()) {
            appendLine("## Mon plan en cours")
            appendLine(planText.trim())
            appendLine()
        }
        appendLine("## Ce que j'attends (200 mots maximum, sans tableau ni JSON)")
        appendLine("1. Bilan de la séance : ce qui était bien, ce qui l'était moins (respect des consignes, cardio).")
        appendLine("2. Ma progression par rapport aux séances précédentes (forme, endurance, efficacité cardio).")
        appendLine("3. Signaux d'alerte éventuels (fatigue, cardio trop haut, surcharge) — sans diagnostic médical.")
        appendLine("4. Un conseil concret pour la prochaine séance, et s'il faut alléger ou durcir le plan.")
    }
}
