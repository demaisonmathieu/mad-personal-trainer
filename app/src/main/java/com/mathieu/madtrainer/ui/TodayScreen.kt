package com.mathieu.madtrainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.FormStatus
import com.mathieu.madtrainer.data.Hydration
import com.mathieu.madtrainer.data.HydrationStore
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.health.HydrationReminder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DAY = 86_400_000L

/** Accueil : séance du jour, forme, eau, poids, semaine et volume récent. */
@Composable
fun TodayScreen(vm: AppViewModel, onStartSession: () -> Unit, onOpenStrength: () -> Unit, onOpenPlan: () -> Unit) {
    val plan by vm.repo.globalPlan.collectAsStateWithLifecycle()
    val workouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val strengthPlan by vm.repo.strengthPlan.collectAsStateWithLifecycle()
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val body by vm.repo.body.collectAsStateWithLifecycle()
    val sp by vm.repo.strengthProfile.collectAsStateWithLifecycle()
    val water by HydrationStore.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LifecycleResumeEffect(Unit) {
        HydrationStore.refresh()
        onPauseOrDispose { }
    }
    val trend = remember(sessions, body, profile) { vm.trendReport() }

    val now = System.currentTimeMillis()
    val monday = WeekPlanner.mondayOf(now)
    val todayIdx = ((now - monday) / DAY).toInt().coerceIn(0, 6)

    // Séances prévues cette semaine (plan course + elliptique, et renfo), par jour
    val ref = plan?.createdAt ?: strengthPlan?.createdAt
    val planWorkouts = plan?.let { p -> workouts.filter { it.id in p.workoutIds } }.orEmpty()
    val maxWeek = maxOf(planWorkouts.maxOfOrNull { it.planWeek ?: 1 } ?: 1, strengthPlan?.plan?.sessions?.maxOfOrNull { it.week } ?: 1)
    val week = ref?.let { WeekPlanner.currentWeek(it, now, maxWeek) }
    val weekWorkouts = if (week == null) emptyList() else planWorkouts.filter { (it.planWeek ?: 1) == week }
    val weekStrength = if (week == null) emptyList() else strengthPlan?.plan?.sessions.orEmpty().filter { it.week == week }
    val plannedDays = (weekWorkouts.map { WeekPlanner.dayIndex(it.planDay) } + weekStrength.map { WeekPlanner.dayIndex(it.day) }).toSet()
    val doneDays = sessions.filter { it.startedAt >= monday }.map { ((it.startedAt - monday) / DAY).toInt() }.toSet()
    val sessionsToday = sessions.filter { it.startedAt >= monday + todayIdx * DAY }
    val todayWorkout = weekWorkouts.firstOrNull { WeekPlanner.dayIndex(it.planDay) == todayIdx }
    val todayDone = todayWorkout != null && sessionsToday.any { s -> s.workoutName?.startsWith(todayWorkout.name) == true }
    val todayStrength = weekStrength.firstOrNull { WeekPlanner.dayIndex(it.day) == todayIdx }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text(
                SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(now)).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.sp,
                color = Night.Muted,
            )
            Text(
                "Salut" + profile.firstName.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        // Séance du jour
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Night.Surface),
            border = BorderStroke(1.dp, Night.Line),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    todayWorkout != null -> {
                        Eyebrow(
                            if (todayDone) "Séance du jour · faite" else "Séance du jour",
                            "${todayWorkout.sport.label} · ${formatDuration(todayWorkout.totalSec)}",
                        )
                        Text(todayWorkout.name, style = MaterialTheme.typography.headlineSmall)
                        WorkoutProfile(todayWorkout, Modifier.fillMaxWidth().height(32.dp))
                        todayWorkout.notes.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Night.Soft, maxLines = 3)
                        }
                        StartButton(if (todayDone) "Refaire" else "Démarrer") { start(vm, todayWorkout, onStartSession) }
                    }
                    todayStrength != null -> {
                        Eyebrow("Séance du jour", "Renfo · ${todayStrength.duration_min} min")
                        Text(todayStrength.name, style = MaterialTheme.typography.headlineSmall)
                        todayStrength.focus.takeIf { it.isNotBlank() }?.let { Text(it, color = Night.Soft) }
                        StartButton("Démarrer") {
                            vm.startStrength(todayStrength, sp.painLevel)
                            onOpenStrength()
                        }
                    }
                    ref == null -> {
                        Eyebrow("Pas encore de plan", "")
                        Text("Génère ton plan course + elliptique dans Plan › Coach, avec ou sans IA.", color = Night.Soft)
                        StartButton("Aller au plan", onOpenPlan)
                    }
                    else -> {
                        Eyebrow("Jour de repos", "")
                        Text("Rien de prévu aujourd'hui. Tu peux faire une séance libre ou récupérer.", color = Night.Soft)
                        OutlinedButton(onClick = onStartSession, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Séance libre") }
                    }
                }
            }
        }

        // Forme · eau · poids
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val formColor = when (trend.status) {
                FormStatus.OPTIMAL -> Night.Good
                FormStatus.FRESH -> Color(0xFF4FA3FF)
                FormStatus.LOADED -> Color(0xFFFFB020)
                FormStatus.OVERLOAD -> Night.Bad
                FormStatus.NOT_ENOUGH_DATA -> Night.Muted
            }
            val formLabel = when (trend.status) {
                FormStatus.OPTIMAL -> "Optimale"
                FormStatus.FRESH -> "Frais"
                FormStatus.LOADED -> "Chargé"
                FormStatus.OVERLOAD -> "Surcharge"
                FormStatus.NOT_ENOUGH_DATA -> "—"
            }
            SmallTile("Forme", formLabel, formColor, Modifier.weight(1f)) {
                trend.acwr?.let { Text("ratio %.2f".format(Locale.FRANCE, it), style = MaterialTheme.typography.labelSmall, color = Night.Muted) }
            }
            SmallTile("Eau", Hydration.liters(water.drunkMl), Night.Text, Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { (water.drunkMl.toFloat() / water.targetMl).coerceIn(0f, 1f) },
                    color = Night.Water,
                    trackColor = Night.Line,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
            val last = BodyStats.latest(body.measures)
            val wTrend = BodyStats.trend(body.measures, 14, now) { it.weightKg }
            SmallTile("Poids", last?.weightKg?.let { "%.1f".format(Locale.FRANCE, it) } ?: "%.1f".format(Locale.FRANCE, profile.weightKg), Night.Text, Modifier.weight(1f)) {
                wTrend?.let {
                    val d = it.delta
                    Text(
                        (if (d >= 0) "+" else "−") + "%.1f kg".format(Locale.FRANCE, abs(d)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (d <= 0) Night.Good else Night.Muted,
                    )
                }
            }
        }

        // Rappel d'eau quand on est en retard
        val behind = water.expectedNow() - water.drunkMl
        if (behind >= Hydration.REMIND_BEHIND_ML && water.drunkMl < water.targetMl) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF12202A))
                    .border(1.dp, Color(0xFF1F3A4A), RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("En retard de ${Hydration.liters(behind)} sur ta journée", Modifier.weight(1f), color = Color(0xFFCFE9F7), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        HydrationStore.add(Hydration.GLASS_ML)
                        HydrationReminder.cancel(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Night.Water, contentColor = Night.Bg),
                ) { Text("+${Hydration.GLASS_ML} ml", fontWeight = FontWeight.SemiBold) }
            }
        }

        // Semaine
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Cette semaine", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val thisWeek = sessions.filter { it.startedAt >= monday }
            Text(
                "${thisWeek.size} séance(s) · ${thisWeek.sumOf { it.durationSec } / 60} min",
                style = MaterialTheme.typography.bodySmall,
                color = Night.Muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("L", "M", "M", "J", "V", "S", "D").forEachIndexed { i, d ->
                val isToday = i == todayIdx
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (isToday) Night.AccentContainer else Night.Surface)
                        .then(if (isToday) Modifier.border(1.dp, Night.Accent, RoundedCornerShape(12.dp)) else Modifier)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(d, style = MaterialTheme.typography.labelMedium, color = if (isToday) Night.AccentText else Night.Muted)
                    val dot = Modifier.size(10.dp).clip(CircleShape)
                    when {
                        i in doneDays -> Box(dot.background(Night.Good))
                        i in plannedDays -> Box(dot.border(2.dp, if (isToday) Night.Accent else Color(0xFF4FA3FF), CircleShape))
                        else -> Box(dot.border(2.dp, Night.Line, CircleShape))
                    }
                }
            }
        }

        // Volume sur 8 semaines
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Night.Surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val weeks = trend.weeks
                val prev = weeks.dropLast(1).takeLast(4).map { it.minutes }.filter { it > 0 }
                Row {
                    Text("Volume 8 semaines", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (prev.isNotEmpty()) {
                        val pct = ((weeks.last().minutes / prev.average() - 1) * 100).roundToInt()
                        Text("${if (pct >= 0) "+" else ""}$pct % vs moy.", style = MaterialTheme.typography.labelMedium, color = Night.Muted)
                    }
                }
                val max = (weeks.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(30)
                val bar = Color(0xFF2E3540)
                Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                    val slot = size.width / weeks.size.coerceAtLeast(1)
                    weeks.forEachIndexed { i, w ->
                        val h = (size.height * w.minutes / max).coerceAtLeast(3f)
                        drawRoundRect(
                            if (i == weeks.size - 1) Night.Accent else bar,
                            topLeft = Offset(i * slot + slot * 0.12f, size.height - h),
                            size = Size(slot * 0.76f, h),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

private fun start(vm: AppViewModel, w: Workout, onStartSession: () -> Unit) {
    vm.setSport(w.sport)
    vm.launchWorkout(w)
    onStartSession()
}

@Composable
private fun Eyebrow(label: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, color = Night.AccentText, fontWeight = FontWeight.SemiBold)
        if (detail.isNotBlank()) Text("  ·  $detail", style = MaterialTheme.typography.labelMedium, color = Night.Muted)
    }
}

@Composable
private fun StartButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, null)
        Text("  $label", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallTile(label: String, value: String, color: Color, modifier: Modifier, extra: @Composable () -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Night.Surface), border = BorderStroke(1.dp, Night.Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Night.Muted)
            Text(value, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = color, maxLines = 1)
            extra()
        }
    }
}
