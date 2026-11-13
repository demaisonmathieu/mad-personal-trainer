package com.mathieu.madtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.coach.StrengthPlayer
import com.mathieu.madtrainer.coach.StrengthSession
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une ligne de la semaine : séance cardio du plan commun ou séance de renfo. */
private data class WeekItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val minutes: Int,
    val sport: Sport?, // null = renfo
    val workout: Workout? = null,
    val strength: StrengthSession? = null,
    val autoDone: Boolean,
)

/**
 * « Ma semaine » : plan commun (course + elliptique) et renfo, jour par jour,
 * avec lancement direct dans le bon mode et bilan de charge.
 */
@Composable
fun WeekScreen(
    vm: AppViewModel,
    onOpenSession: () -> Unit,
    onOpenStrength: () -> Unit,
    onOpenCoach: () -> Unit,
) {
    val plan by vm.repo.globalPlan.collectAsStateWithLifecycle()
    val workouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val strengthPlan by vm.repo.strengthPlan.collectAsStateWithLifecycle()
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val painLogs by vm.repo.painLogs.collectAsStateWithLifecycle()
    val done by vm.repo.done.collectAsStateWithLifecycle()
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val sp by vm.repo.strengthProfile.collectAsStateWithLifecycle()

    val now = System.currentTimeMillis()
    val ref = plan?.createdAt ?: strengthPlan?.createdAt
    val planWorkouts = plan?.let { p -> workouts.filter { it.id in p.workoutIds } }.orEmpty()
    val maxWeek = maxOf(
        planWorkouts.maxOfOrNull { it.planWeek ?: 1 } ?: 1,
        strengthPlan?.plan?.sessions?.maxOfOrNull { it.week } ?: 1,
    )
    var week by remember(ref) { mutableIntStateOf(ref?.let { WeekPlanner.currentWeek(it, now, maxWeek) } ?: 1) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ma semaine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (ref == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pas encore de plan.", fontWeight = FontWeight.SemiBold)
                    Text("Génère ton plan commun course + elliptique (et ton renfo) dans l'onglet Coach : il apparaîtra ici, jour par jour.")
                    Button(onClick = onOpenCoach) { Text("Aller au coach") }
                }
            }
            ThisWeekLoad(vm, sessions, profile.estimatedMaxHr)
        } else {

        val start = WeekPlanner.weekStart(ref, week)
        val end = start + WeekPlanner.WEEK_MS
        val df = SimpleDateFormat("d MMM", Locale.FRANCE)

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { week = (week - 1).coerceAtLeast(1) }, enabled = week > 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Semaine précédente")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Semaine $week / $maxWeek", fontWeight = FontWeight.SemiBold)
                Text("du ${df.format(Date(start))} au ${df.format(Date(end - 1))}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { week = (week + 1).coerceAtMost(maxWeek) }, enabled = week < maxWeek) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Semaine suivante")
            }
        }

        val sessionsInWeek = sessions.filter { it.startedAt in start until end }
        val logsInWeek = painLogs.filter { it.date in start until end }
        val items = buildList {
            planWorkouts.filter { (it.planWeek ?: 1) == week }.forEach { w ->
                add(
                    WeekItem(
                        key = "w:${w.id}",
                        title = w.name,
                        subtitle = listOfNotNull(w.sport.label, formatDuration(w.totalSec), w.notes.takeIf { it.isNotBlank() }).joinToString(" · "),
                        icon = sportIcon(w.sport),
                        minutes = w.totalSec / 60,
                        sport = w.sport,
                        workout = w,
                        autoDone = sessionsInWeek.any { s -> s.workoutName?.let { n -> n == w.name || n.startsWith(w.name) } == true },
                    ) to (w.planDay ?: ""),
                )
            }
            strengthPlan?.plan?.sessions?.filter { it.week == week }?.forEachIndexed { i, s ->
                add(
                    WeekItem(
                        key = "r:${strengthPlan?.createdAt}:$week:$i:${s.name}",
                        title = s.name,
                        subtitle = listOfNotNull("Renfo", "${s.exercises.size} exercices", s.focus.takeIf { it.isNotBlank() }).joinToString(" · "),
                        icon = Icons.Filled.SelfImprovement,
                        minutes = StrengthPlayer.estimatedSeconds(s) / 60,
                        sport = null,
                        strength = s,
                        autoDone = logsInWeek.any { it.sessionName == s.name },
                    ) to s.day,
                )
            }
        }

        // Bilan prévu / fait
        val all = items.map { it.first }
        val isDone: (WeekItem) -> Boolean = { it.autoDone || it.key in done }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                StatRow(
                    listOf(
                        "Faites" to "${all.count(isDone)}/${all.size}",
                        "Course" to "${all.filter { it.sport == Sport.RUNNING }.sumOf { it.minutes }} min",
                        "Elliptique" to "${all.filter { it.sport == Sport.ELLIPTICAL }.sumOf { it.minutes }} min",
                        "Renfo" to "${all.filter { it.sport == null }.sumOf { it.minutes }} min",
                    ),
                )
            }
        }

        val byDay = items.groupBy { WeekPlanner.dayIndex(it.second) }
        (WeekPlanner.DAYS.indices.toList() + listOf(-1)).forEach { d ->
            val dayItems = byDay[d].orEmpty().map { it.first }
            if (!(d == -1 && dayItems.isEmpty())) {
            val dayDate = if (d >= 0) start + d * 24L * 3600 * 1000 else null
            val isToday = dayDate != null && now in dayDate until dayDate + 24L * 3600 * 1000
            Text(
                (if (d >= 0) WeekPlanner.DAYS[d] + " " + df.format(Date(dayDate!!)) else "À placer dans la semaine") + if (isToday) " · aujourd'hui" else "",
                fontWeight = FontWeight.SemiBold,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (dayItems.isEmpty()) {
                Text("Repos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            dayItems.forEach {
                val doneNow = isDone(it)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = doneNow, onCheckedChange = { c -> vm.setDone(it.key, c) }, enabled = !it.autoDone)
                        Icon(it.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(it.title, textDecoration = if (doneNow) TextDecoration.LineThrough else null)
                            Text(it.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        val w = it.workout
                        if (w != null) {
                            IconButton(onClick = {
                                vm.runOnOtherSport(w)
                                onOpenSession()
                            }) { Icon(Icons.Filled.SwapHoriz, if (w.sport == Sport.RUNNING) "Faire sur l'elliptique" else "Faire en course") }
                        }
                        IconButton(onClick = {
                            if (w != null) {
                                vm.setSport(w.sport)
                                vm.launchWorkout(w)
                                onOpenSession()
                            } else {
                                it.strength?.let { s -> vm.startStrength(s, sp.painLevel) }
                                onOpenStrength()
                            }
                        }) { Icon(Icons.Filled.PlayArrow, "Lancer") }
                    }
                }
            }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        ThisWeekLoad(vm, sessions, profile.estimatedMaxHr)
        Spacer(Modifier.height(16.dp))
        }
    }
}

/** Charge réelle de la semaine calendaire en cours (tous sports) : temps par sport et par zone cardio. */
@Composable
private fun ThisWeekLoad(vm: AppViewModel, sessions: List<com.mathieu.madtrainer.data.SessionRecord>, maxHr: Int) {
    val monday = WeekPlanner.mondayOf(System.currentTimeMillis())
    val thisWeek = sessions.filter { it.startedAt >= monday }
    val logs by vm.repo.painLogs.collectAsStateWithLifecycle()
    val renfo = logs.count { it.date >= monday }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Réalisé cette semaine", fontWeight = FontWeight.SemiBold)
            StatRow(
                listOf(
                    "Course" to "${thisWeek.filter { it.sport == Sport.RUNNING }.sumOf { it.durationSec } / 60} min",
                    "Km" to "%.1f".format(thisWeek.filter { it.sport == Sport.RUNNING }.sumOf { it.distanceM } / 1000.0),
                    "Elliptique" to "${thisWeek.filter { it.sport == Sport.ELLIPTICAL }.sumOf { it.durationSec } / 60} min",
                    "Renfo" to "$renfo",
                ),
            )
            val zones = WeekPlanner.minutesPerZone(thisWeek, maxHr)
            if (zones.sum() > 0) {
                Text("Temps par zone cardio (tous sports)", style = MaterialTheme.typography.labelLarge)
                StatRow((1..5).map { "Z$it" to "${zones[it - 1]}′" })
                val easy = zones[0] + zones[1]
                val pct = easy * 100 / zones.sum()
                Text(
                    "$pct % en zone 1-2" + if (pct < 70) " : pense à garder la majorité du volume en endurance facile." else " : bonne répartition.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
