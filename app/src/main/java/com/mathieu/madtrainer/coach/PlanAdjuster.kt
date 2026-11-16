package com.mathieu.madtrainer.coach

import com.mathieu.madtrainer.data.FormStatus
import com.mathieu.madtrainer.data.HrZones
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.SessionReport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.TrendReport
import com.mathieu.madtrainer.data.Workout
import kotlin.math.roundToInt

enum class AdjustAction(val label: String) {
    REDUCE("Alléger la suite du plan"),
    KEEP("Garder le plan tel quel"),
    INCREASE("Durcir légèrement la suite du plan"),
}

data class PlanAdjustment(val action: AdjustAction, val reasons: List<String>)

/**
 * Ajuste le plan d'après les séances réellement faites (sans IA) :
 * respect des consignes, dérive cardiaque, charge aiguë / chronique, FC de repos, séances manquées.
 */
object PlanAdjuster {
    private const val DAY = 86_400_000L
    const val REDUCE_FACTOR = 0.8
    const val INCREASE_FACTOR = 1.1

    /** Signaux tirés des séances récentes (14 jours). */
    data class Signals(
        val sessions: Int,
        /** % moyen du temps dans la cible sur les étapes d'effort (zone ≥ 3 ou allure). */
        val workAdherence: Int?,
        /** Séances où le cardio a dépassé la cible des étapes d'effort (trop dur). */
        val tooHard: Int,
        /** Séances où le cardio est resté sous la cible des étapes d'effort (trop facile). */
        val tooEasy: Int,
        /** Séances (parmi les 3 dernières) avec une dérive cardiaque ≥ 8 %. */
        val drifting: Int,
    )

    fun signals(sessions: List<SessionRecord>, zones: HrZones, now: Long): Signals {
        val recent = sessions.filter { it.startedAt in (now - 14 * DAY)..now }.sortedByDescending { it.startedAt }
        var tooHard = 0
        var tooEasy = 0
        val pcts = mutableListOf<Int>()
        recent.forEach { rec ->
            val adh = SessionReport.stepAdherence(rec, zones)
            var above = 0
            var below = 0
            adh.forEach { a ->
                val step = rec.steps.getOrNull(a.index) ?: return@forEach
                val work = (step.mode == StepMode.HR_ZONE && step.target >= 3) || step.mode == StepMode.PACE || step.mode == StepMode.POWER
                if (!work) return@forEach
                a.inTargetPct?.let { pcts += it }
                if (step.mode == StepMode.HR_ZONE && a.avgHr != null) {
                    val r = zones.range(step.target.roundToInt())
                    if (a.avgHr > r.last + 3) above++
                    if (a.avgHr < r.first - 3) below++
                }
            }
            if (above > 0 && above >= below) tooHard++
            if (below > 0 && below > above) tooEasy++
        }
        val drifting = recent.take(3).count { (SessionReport.hrDrift(it) ?: 0.0) >= 8.0 }
        return Signals(recent.size, pcts.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), tooHard, tooEasy, drifting)
    }

    /**
     * @param plannedLastWeek séances prévues la semaine dernière (null si pas de plan à cette date)
     * @param doneLastWeek séances faites la semaine dernière
     */
    fun decide(trend: TrendReport, s: Signals, plannedLastWeek: Int?, doneLastWeek: Int): PlanAdjustment {
        val reduce = mutableListOf<String>()
        if (trend.status == FormStatus.OVERLOAD) reduce += "Charge des 7 derniers jours bien au-dessus de ton habitude (ratio ${"%.1f".format(trend.acwr ?: 0.0)})"
        trend.restingHrDelta?.takeIf { it >= 7 }?.let { reduce += "FC de repos +$it bpm : ton corps récupère mal" }
        if (s.drifting >= 2) reduce += "Dérive cardiaque forte sur ${s.drifting} des 3 dernières séances"
        if (s.tooHard >= 2) reduce += "Cardio au-dessus de la cible sur les efforts de ${s.tooHard} séances : intensités trop ambitieuses"
        if (reduce.isNotEmpty()) return PlanAdjustment(AdjustAction.REDUCE, reduce)

        if (plannedLastWeek != null && plannedLastWeek > 0 && doneLastWeek * 2 < plannedLastWeek) {
            return PlanAdjustment(
                AdjustAction.KEEP,
                listOf("$doneLastWeek séance(s) sur $plannedLastWeek la semaine dernière : on consolide avant d'augmenter. Si le rythme est trop dense, reporte le plan d'une semaine."),
            )
        }

        val increase = mutableListOf<String>()
        val adherenceOk = (s.workAdherence ?: 0) >= 80
        val formOk = trend.status == FormStatus.OPTIMAL || trend.status == FormStatus.FRESH
        val restOk = (trend.restingHrDelta ?: 0) <= 2
        if (s.sessions >= 3 && formOk && restOk && s.drifting == 0 && s.tooHard == 0 && (adherenceOk || s.tooEasy >= 2)) {
            if (adherenceOk) increase += "Consignes d'effort tenues ${s.workAdherence} % du temps sur ${s.sessions} séances"
            if (s.tooEasy >= 2) increase += "Cardio sous la cible des efforts sur ${s.tooEasy} séances : tu as de la marge"
            increase += "État de forme : ${trend.status.label.lowercase()}, sans dérive cardiaque"
            trend.runEfficiencyPct?.takeIf { it >= 2 }?.let { increase += "Efficacité en course en hausse de ${it.roundToInt()} %" }
            return PlanAdjustment(AdjustAction.INCREASE, increase)
        }

        val keep = buildList {
            if (s.sessions < 3) add("Pas assez de séances ces 14 derniers jours (${s.sessions}) pour juger")
            else add("Signaux équilibrés : ${trend.status.label.lowercase()}" + (s.workAdherence?.let { ", consignes tenues $it % du temps" } ?: ""))
        }
        return PlanAdjustment(AdjustAction.KEEP, keep)
    }

    private fun isEasyEdge(name: String): Boolean = name.lowercase().let { "chauff" in it || "calme" in it }

    /** Applique l'ajustement à une séance : durée du corps de séance et intensité des efforts. */
    fun apply(w: Workout, action: AdjustAction): Workout {
        if (action == AdjustAction.KEEP) return w
        val f = if (action == AdjustAction.REDUCE) REDUCE_FACTOR else INCREASE_FACTOR
        val steps = w.steps.map { s ->
            if (isEasyEdge(s.name)) return@map s
            var step = s.copy(
                durationSec = (s.durationSec * f).roundToInt().coerceAtLeast(10),
                distanceM = s.distanceM?.let { (it * f).roundToInt() },
            )
            if (action == AdjustAction.REDUCE) {
                step = when (s.mode) {
                    StepMode.HR_ZONE -> if (s.target >= 4) step.copy(target = s.target - 1) else step
                    StepMode.PACE -> step.copy(target = s.target + 15)
                    StepMode.POWER -> step.copy(target = (s.target * 0.9).roundToInt().toDouble())
                    StepMode.RESISTANCE -> step.copy(target = (s.target - 1).coerceAtLeast(1.0))
                    StepMode.FREE -> step
                }
            }
            step
        }
        val tag = if (action == AdjustAction.REDUCE) "Allégée d'après tes dernières séances." else "Renforcée d'après tes dernières séances."
        val notes = w.notes.replace("Allégée d'après tes dernières séances.", "").replace("Renforcée d'après tes dernières séances.", "").trim()
        return w.copy(steps = steps, notes = (notes + " " + tag).trim())
    }
}
