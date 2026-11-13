package com.mathieu.madtrainer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    val day: String,
    val workout: Workout? = null,
    val strength: StrengthSession? = null,
    /** Position de la séance de renfo dans le plan (pour la modifier) */
    val strengthIndex: Int? = null,
    val autoDone: Boolean,
)

/** Glisser-déposer d'une séance vers un autre jour. */
private class DayDrag {
    var draggedKey by mutableStateOf<String?>(null)
    var offsetY by mutableFloatStateOf(0f)
    var startCenterY by mutableFloatStateOf(0f)
    /** Haut / bas (dans la fenêtre) de chaque bloc jour 0..6 */
    val dayBounds = mutableStateMapOf<Int, Pair<Float, Float>>()

    fun hoveredDay(): Int? {
        if (draggedKey == null) return null
        val y = startCenterY + offsetY
        return dayBounds.entries.firstOrNull { (_, b) -> y in b.first..b.second }?.key
    }
}

/**
 * « Ma semaine » : plan commun (course + elliptique) et renfo, jour par jour.
 * Appui long + glisser une séance pour la changer de jour, toucher pour la modifier,
 * boutons pour décaler tout le plan d'une semaine.
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

    var editingWorkout by remember { mutableStateOf<Workout?>(null) }
    var editingStrength by remember { mutableStateOf<Pair<Int, StrengthSession>?>(null) }
    var confirmShift by remember { mutableStateOf<Int?>(null) }

    // Éditeurs plein écran
    editingWorkout?.let { w ->
        WorkoutEditor(w, onSave = {
            vm.saveWorkout(it)
            editingWorkout = null
        }, onCancel = { editingWorkout = null })
        return
    }
    editingStrength?.let { (idx, s) ->
        StrengthSessionEditor(s, vm.exerciseCatalog, onSave = {
            vm.updateStrengthSession(idx, it)
            editingStrength = null
        }, onCancel = { editingStrength = null })
        return
    }

    val now = System.currentTimeMillis()
    val ref = plan?.createdAt ?: strengthPlan?.createdAt
    val planWorkouts = plan?.let { p -> workouts.filter { it.id in p.workoutIds } }.orEmpty()
    val maxWeek = maxOf(
        planWorkouts.maxOfOrNull { it.planWeek ?: 1 } ?: 1,
        strengthPlan?.plan?.sessions?.maxOfOrNull { it.week } ?: 1,
    )
    var week by remember(ref) { mutableIntStateOf(ref?.let { WeekPlanner.currentWeek(it, now, maxWeek) } ?: 1) }
    val drag = remember { DayDrag() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = drag.draggedKey == null).padding(16.dp),
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
            // Décaler tout le plan (ex. semaine chargée, vacances, blessure)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { confirmShift = -1 }, modifier = Modifier.weight(1f)) { Text("Avancer d'1 sem.", maxLines = 1) }
                OutlinedButton(onClick = { confirmShift = 1 }, modifier = Modifier.weight(1f)) { Text("Reporter d'1 sem.", maxLines = 1) }
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
                            day = w.planDay ?: "",
                            workout = w,
                            autoDone = sessionsInWeek.any { s -> s.workoutName?.let { n -> n == w.name || n.startsWith(w.name) } == true },
                        ),
                    )
                }
                strengthPlan?.plan?.sessions?.forEachIndexed { idx, s ->
                    if (s.week == week) {
                        add(
                            WeekItem(
                                key = "r:$idx:${s.name}",
                                title = s.name,
                                subtitle = listOfNotNull("Renfo", "${s.exercises.size} exercices", s.focus.takeIf { it.isNotBlank() }).joinToString(" · "),
                                icon = Icons.Filled.SelfImprovement,
                                minutes = StrengthPlayer.estimatedSeconds(s) / 60,
                                sport = null,
                                day = s.day,
                                strength = s,
                                strengthIndex = idx,
                                autoDone = logsInWeek.any { it.sessionName == s.name },
                            ),
                        )
                    }
                }
            }

            val isDone: (WeekItem) -> Boolean = { it.autoDone || it.key in done }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    StatRow(
                        listOf(
                            "Faites" to "${items.count(isDone)}/${items.size}",
                            "Course" to "${items.filter { it.sport == Sport.RUNNING }.sumOf { it.minutes }} min",
                            "Elliptique" to "${items.filter { it.sport == Sport.ELLIPTICAL }.sumOf { it.minutes }} min",
                            "Renfo" to "${items.filter { it.sport == null }.sumOf { it.minutes }} min",
                        ),
                    )
                }
            }
            Text(
                "Appui long sur une séance puis glisse-la vers un autre jour. Touche-la pour la modifier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Déplacement d'une séance vers un jour (et éventuellement une autre semaine)
            val moveTo: (WeekItem, Int, Int) -> Unit = { item, dayIdx, targetWeek ->
                val dayName = WeekPlanner.DAYS[dayIdx]
                item.workout?.let { vm.moveWorkout(it, dayName, targetWeek) }
                val si = item.strengthIndex
                val st = item.strength
                if (si != null && st != null) vm.updateStrengthSession(si, st.copy(day = dayName, week = targetWeek))
            }

            val byDay = items.groupBy { WeekPlanner.dayIndex(it.day) }
            val hovered = drag.hoveredDay()
            (WeekPlanner.DAYS.indices.toList() + listOf(-1)).forEach { d ->
                val dayItems = byDay[d].orEmpty()
                if (!(d == -1 && dayItems.isEmpty())) {
                    val dayDate = if (d >= 0) start + d * 24L * 3600 * 1000 else null
                    val isToday = dayDate != null && now in dayDate until dayDate + 24L * 3600 * 1000
                    val highlight = hovered == d
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { c ->
                                if (d >= 0) {
                                    val b = c.boundsInRoot()
                                    drag.dayBounds[d] = b.top to b.bottom
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (d >= 0) WeekPlanner.DAYS[d] + " " + df.format(Date(dayDate!!)) else "À placer dans la semaine") +
                                    (if (isToday) " · aujourd'hui" else "") + (if (highlight) "  ← déposer ici" else ""),
                                fontWeight = FontWeight.SemiBold,
                                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        if (dayItems.isEmpty()) {
                            Text(
                                "Repos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            )
                        }
                        dayItems.forEach { item ->
                            WeekCard(
                                item = item,
                                done = isDone(item),
                                drag = drag,
                                week = week,
                                maxWeek = maxWeek,
                                onToggleDone = { c -> vm.setDone(item.key, c) },
                                onEdit = {
                                    item.workout?.let { editingWorkout = it }
                                    val si = item.strengthIndex
                                    val st = item.strength
                                    if (si != null && st != null) editingStrength = si to st
                                },
                                onPlay = {
                                    val w = item.workout
                                    if (w != null) {
                                        vm.setSport(w.sport)
                                        vm.launchWorkout(w)
                                        onOpenSession()
                                    } else {
                                        item.strength?.let { s -> vm.startStrength(s, sp.painLevel) }
                                        onOpenStrength()
                                    }
                                },
                                onSwapSport = { w ->
                                    vm.runOnOtherSport(w)
                                    onOpenSession()
                                },
                                onMove = { dayIdx, targetWeek -> moveTo(item, dayIdx, targetWeek) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            ThisWeekLoad(vm, sessions, profile.estimatedMaxHr)
            Spacer(Modifier.height(16.dp))
        }
    }

    confirmShift?.let { delta ->
        AlertDialog(
            onDismissRequest = { confirmShift = null },
            title = { Text(if (delta > 0) "Reporter le plan d'une semaine ?" else "Avancer le plan d'une semaine ?") },
            text = {
                Text(
                    if (delta > 0) "Toutes les séances (cardio et renfo) glissent d'une semaine plus tard : la semaine en cours redevient la précédente du plan."
                    else "Toutes les séances avancent d'une semaine.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.shiftPlans(delta)
                    // On reste sur la même semaine du calendrier
                    week = (week - delta).coerceIn(1, maxWeek)
                    confirmShift = null
                }) { Text("Confirmer") }
            },
            dismissButton = { TextButton(onClick = { confirmShift = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun WeekCard(
    item: WeekItem,
    done: Boolean,
    drag: DayDrag,
    week: Int,
    maxWeek: Int,
    onToggleDone: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
    onSwapSport: (Workout) -> Unit,
    onMove: (day: Int, week: Int) -> Unit,
) {
    val dragging = drag.draggedKey == item.key
    var centerY by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val move by rememberUpdatedState(onMove)
    val currentWeek by rememberUpdatedState(week)

    Card(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { c -> if (!dragging) centerY = c.boundsInRoot().center.y }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) {
                    translationY = drag.offsetY
                    scaleX = 1.03f
                    scaleY = 1.03f
                    shadowElevation = 16f
                }
            }
            .pointerInput(item.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        drag.draggedKey = item.key
                        drag.offsetY = 0f
                        drag.startCenterY = centerY
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.offsetY += amount.y
                    },
                    onDragEnd = {
                        val target = drag.hoveredDay()
                        drag.draggedKey = null
                        drag.offsetY = 0f
                        if (target != null) move(target, currentWeek)
                    },
                    onDragCancel = {
                        drag.draggedKey = null
                        drag.offsetY = 0f
                    },
                )
            }
            .clickable { onEdit() },
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = done, onCheckedChange = onToggleDone, enabled = !item.autoDone)
            Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(item.title, textDecoration = if (done) TextDecoration.LineThrough else null)
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, "Lancer") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Plus") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Modifier") }, onClick = { menu = false; onEdit() })
                    item.workout?.let { w ->
                        DropdownMenuItem(
                            text = { Text(if (w.sport == Sport.RUNNING) "Faire sur l'elliptique" else "Faire en course") },
                            leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                            onClick = { menu = false; onSwapSport(w) },
                        )
                    }
                    HorizontalDivider()
                    WeekPlanner.DAYS.forEachIndexed { i, d ->
                        DropdownMenuItem(text = { Text("Déplacer au $d") }, onClick = { menu = false; onMove(i, week) })
                    }
                    if (week < maxWeek) {
                        DropdownMenuItem(text = { Text("Reporter à la semaine suivante") }, onClick = {
                            menu = false
                            onMove(WeekPlanner.dayIndex(item.day).coerceAtLeast(0), week + 1)
                        })
                    }
                    if (week > 1) {
                        DropdownMenuItem(text = { Text("Avancer à la semaine précédente") }, onClick = {
                            menu = false
                            onMove(WeekPlanner.dayIndex(item.day).coerceAtLeast(0), week - 1)
                        })
                    }
                }
            }
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
