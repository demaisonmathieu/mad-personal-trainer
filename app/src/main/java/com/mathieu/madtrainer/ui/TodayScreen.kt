package com.mathieu.madtrainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.coach.StrengthSession
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.Elevation
import com.mathieu.madtrainer.data.FormStatus
import com.mathieu.madtrainer.data.Hydration
import com.mathieu.madtrainer.data.HydrationStore
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.formatDistance
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.data.formatPace
import com.mathieu.madtrainer.health.HydrationReminder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Début (0 h) du jour [index] (0 = lundi) de la semaine qui commence à [monday] ; robuste aux changements d'heure. */
private fun dayStart(monday: Long, index: Int): Long =
    Calendar.getInstance().apply { timeInMillis = monday; add(Calendar.DAY_OF_YEAR, index) }.timeInMillis

/**
 * Accueil : la séance d'un jour de la semaine (aujourd'hui par défaut, un autre en touchant la frise) — prévue,
 * ou réellement faite avec ses chiffres — puis forme, eau, poids et volume récent.
 */
@Composable
fun TodayScreen(
    vm: AppViewModel,
    onStartSession: () -> Unit,
    onOpenStrength: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenSessionDetail: (String) -> Unit,
) {
    val plan by vm.repo.globalPlan.collectAsStateWithLifecycle()
    val workouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val strengthPlan by vm.repo.strengthPlan.collectAsStateWithLifecycle()
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val body by vm.repo.body.collectAsStateWithLifecycle()
    val sp by vm.repo.strengthProfile.collectAsStateWithLifecycle()
    val water by HydrationStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        HydrationStore.refresh()
        onPauseOrDispose { }
    }
    val trend = remember(sessions, body, profile) { vm.trendReport() }

    val now = System.currentTimeMillis()
    val monday = WeekPlanner.mondayOf(now)
    val todayIdx = (0..6).last { dayStart(monday, it) <= now }
    var selectedDay by rememberSaveable { mutableIntStateOf(todayIdx) }

    // Séances prévues cette semaine (plan course + elliptique, et renfo)
    val ref = plan?.createdAt ?: strengthPlan?.createdAt
    val planWorkouts = plan?.let { p -> workouts.filter { it.id in p.workoutIds } }.orEmpty()
    val maxWeek = maxOf(planWorkouts.maxOfOrNull { it.planWeek ?: 1 } ?: 1, strengthPlan?.plan?.sessions?.maxOfOrNull { it.week } ?: 1)
    val week = ref?.let { WeekPlanner.currentWeek(it, now, maxWeek) }
    val weekWorkouts = if (week == null) emptyList() else planWorkouts.filter { (it.planWeek ?: 1) == week }
    val weekStrength = if (week == null) emptyList() else strengthPlan?.plan?.sessions.orEmpty().filter { it.week == week }
    fun sessionsOn(i: Int) = sessions.filter { it.startedAt >= dayStart(monday, i) && it.startedAt < dayStart(monday, i + 1) }
    val plannedDays = (weekWorkouts.map { WeekPlanner.dayIndex(it.planDay) } + weekStrength.map { WeekPlanner.dayIndex(it.day) }).toSet()
    val doneDays = (0..6).filter { sessionsOn(it).isNotEmpty() }.toSet()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Eyebrow(SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(now)))
            Text(
                "Salut" + profile.firstName.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        // Frise de la semaine : toucher un jour affiche sa séance (prévue ou faite)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("L", "M", "M", "J", "V", "S", "D").forEachIndexed { i, d ->
                val isToday = i == todayIdx
                val isSelected = i == selectedDay
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier.weight(1f).clip(shape)
                        .background(if (isSelected) Night.AccentContainer else Night.Surface)
                        .border(1.dp, if (isSelected) Night.Accent else if (isToday) Night.AccentText.copy(alpha = 0.4f) else Night.Line, shape)
                        .clickable { selectedDay = i }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        d,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected || isToday) Night.AccentText else Night.Muted,
                    )
                    val dot = Modifier.size(10.dp).clip(CircleShape)
                    when {
                        i in doneDays -> Box(dot.background(Night.Good))
                        i in plannedDays -> Box(dot.border(2.dp, if (i < todayIdx) Night.Bad else Color(0xFF4FA3FF), CircleShape))
                        else -> Box(dot.border(2.dp, Night.Line, CircleShape))
                    }
                }
            }
        }

        DayCard(
            dayLabel = if (selectedDay == todayIdx) null
            else SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(dayStart(monday, selectedDay))),
            relative = selectedDay.compareTo(todayIdx),
            hasPlan = ref != null,
            done = sessionsOn(selectedDay),
            workout = weekWorkouts.firstOrNull { WeekPlanner.dayIndex(it.planDay) == selectedDay },
            strength = weekStrength.firstOrNull { WeekPlanner.dayIndex(it.day) == selectedDay },
            onStart = { w ->
                vm.setSport(w.sport)
                vm.launchWorkout(w)
                onStartSession()
            },
            onStartStrength = { s ->
                vm.startStrength(s, sp.painLevel)
                onOpenStrength()
            },
            onFreeSession = onStartSession,
            onOpenPlan = onOpenPlan,
            onOpenDetail = onOpenSessionDetail,
        )

        // Forme · eau · poids
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (formLabel, formColor) = when (trend.status) {
                FormStatus.OPTIMAL -> "Optimale" to Night.Good
                FormStatus.FRESH -> "Frais" to Color(0xFF4FA3FF)
                FormStatus.LOADED -> "Chargé" to Color(0xFFFFB020)
                FormStatus.OVERLOAD -> "Surcharge" to Night.Bad
                FormStatus.NOT_ENOUGH_DATA -> "—" to Night.Muted
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
            SmallTile("Poids", "%.1f".format(Locale.FRANCE, last?.weightKg ?: profile.weightKg), Night.Text, Modifier.weight(1f)) {
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

        // Volume de la semaine et des 8 dernières
        NightCard {
            val weeks = trend.weeks
            val thisWeek = sessions.filter { it.startedAt >= monday }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Volume", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "cette semaine : ${thisWeek.size} séance(s) · ${thisWeek.sumOf { it.durationSec } / 60} min",
                    style = MaterialTheme.typography.labelMedium,
                    color = Night.Muted,
                )
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
            Text("8 dernières semaines, en minutes", style = MaterialTheme.typography.labelSmall, color = Night.Muted)
        }
    }
}

/**
 * Carte d'un jour : séance(s) réellement faite(s) avec leurs chiffres, sinon séance prévue (à lancer, ou manquée),
 * sinon repos. [relative] < 0 : jour passé, 0 : aujourd'hui, > 0 : à venir.
 */
@Composable
private fun DayCard(
    dayLabel: String?,
    relative: Int,
    hasPlan: Boolean,
    done: List<SessionRecord>,
    workout: Workout?,
    strength: StrengthSession?,
    onStart: (Workout) -> Unit,
    onStartStrength: (StrengthSession) -> Unit,
    onFreeSession: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val title = dayLabel ?: "Séance du jour"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Night.Surface),
        border = BorderStroke(1.dp, if (done.isNotEmpty()) Color(0xFF1F3A2E) else Night.Line),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                // Séance(s) faite(s) : ce qui a vraiment été fait, avec les perfs
                done.isNotEmpty() -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(title, Night.AccentText, Modifier.weight(1f))
                        Pill("Faite", Night.Good, Color(0xFF13241C))
                    }
                    done.sortedBy { it.startedAt }.forEachIndexed { i, rec ->
                        if (i > 0) HorizontalDivider(color = Night.Line)
                        DoneSession(rec, workout, onOpenDetail)
                    }
                }
                workout != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("$title · ${workout.sport.label} · ${formatDuration(workout.totalSec)}", Night.AccentText, Modifier.weight(1f))
                        if (relative < 0) Pill("Non faite", Night.Bad, Color(0xFF3A1218))
                        if (relative > 0) Pill("Prévue", Color(0xFFBFDBFF), Color(0xFF13243A))
                    }
                    Text(workout.name, style = MaterialTheme.typography.headlineSmall)
                    WorkoutProfile(workout, Modifier.fillMaxWidth().height(32.dp))
                    workout.notes.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Night.Soft, maxLines = 4)
                    }
                    // Détail des étapes pour un autre jour que aujourd'hui (préparer la séance)
                    if (relative != 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            workout.steps.forEach { st ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(zoneColor(stepZone(st, workout.steps))))
                                    Text("  ${st.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(
                                        (st.distanceM?.let { formatDistance(it.toDouble()) } ?: formatDuration(st.durationSec)) + " · " + stepTargetText(st),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Night.Muted,
                                    )
                                }
                            }
                        }
                    }
                    StartButton(if (relative == 0) "Démarrer" else "La faire maintenant") { onStart(workout) }
                }
                strength != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("$title · Renfo · ${strength.duration_min} min", Night.AccentText, Modifier.weight(1f))
                        if (relative > 0) Pill("Prévue", Color(0xFFBFDBFF), Color(0xFF13243A))
                    }
                    Text(strength.name, style = MaterialTheme.typography.headlineSmall)
                    strength.focus.takeIf { it.isNotBlank() }?.let { Text(it, color = Night.Soft) }
                    if (relative != 0) {
                        strength.exercises.forEach { ex ->
                            Text("• ${ex.name}", style = MaterialTheme.typography.bodySmall, color = Night.Soft, maxLines = 1)
                        }
                    }
                    StartButton(if (relative == 0) "Démarrer" else "La faire maintenant") { onStartStrength(strength) }
                }
                !hasPlan -> {
                    Eyebrow("Pas encore de plan", Night.AccentText)
                    Text("Génère ton plan course + elliptique dans Plan › Coach, avec ou sans IA.", color = Night.Soft)
                    StartButton("Aller au plan", onOpenPlan)
                }
                else -> {
                    Eyebrow(title, Night.AccentText)
                    Text("Repos", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (relative > 0) "Rien de prévu ce jour-là." else "Rien de prévu. Tu peux faire une séance libre ou récupérer.",
                        color = Night.Soft,
                    )
                    if (relative == 0) OutlinedButton(onClick = onFreeSession, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Séance libre") }
                }
            }
        }
    }
}

/** Une séance faite : nom réel, heure, chiffres clés, comparaison avec le prévu, accès au détail. */
@Composable
private fun DoneSession(rec: SessionRecord, planned: Workout?, onOpenDetail: (String) -> Unit) {
    val matchesPlan = planned != null && rec.workoutName?.startsWith(planned.name) == true
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SportBadge(rec.sport)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(rec.workoutName ?: "Séance libre", style = MaterialTheme.typography.titleMedium)
                Text(
                    "à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(rec.startedAt)) +
                        (if (planned != null && !matchesPlan) " · prévu : ${planned.name}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Night.Muted,
                )
            }
        }
        val items = if (rec.sport == Sport.RUNNING) {
            listOf(
                "Distance" to "%.2f km".format(rec.distanceM / 1000.0).replace('.', ','),
                "Temps" to formatDuration(rec.durationSec),
                "Allure" to formatPace(rec.avgPaceSecPerKm),
                "FC moy." to (rec.avgHr?.toString() ?: "--"),
                "D+" to (Elevation.stats(rec.samples)?.let { "${it.gainM} m" } ?: "--"),
                "kcal" to rec.kcal.toString(),
            )
        } else {
            listOf(
                "Temps" to formatDuration(rec.durationSec),
                "Puiss. moy." to "${rec.avgPower} W",
                "Puiss. max" to "${rec.maxPower} W",
                "Cadence" to "%.0f".format(rec.avgCadence),
                "FC moy." to (rec.avgHr?.toString() ?: "--"),
                "kcal" to rec.kcal.toString(),
            )
        }
        SummaryGrid(items)
        // Durée réelle comparée à la durée prévue
        if (matchesPlan && planned!!.totalSec > 0) {
            val pct = (rec.durationSec * 100.0 / planned.totalSec).roundToInt()
            Text(
                "Durée prévue ${formatDuration(planned.totalSec)} · réalisée à $pct %",
                style = MaterialTheme.typography.bodySmall,
                color = if (pct >= 90) Night.Good else Night.Soft,
            )
        }
        TextButton(onClick = { onOpenDetail(rec.id) }, modifier = Modifier.align(Alignment.End)) { Text("Voir le détail ›") }
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
