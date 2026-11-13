package com.mathieu.madtrainer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.formatPace
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutStep
import com.mathieu.madtrainer.data.formatDuration
import java.util.UUID

@Composable
fun WorkoutsScreen(vm: AppViewModel, onStart: () -> Unit) {
    val allWorkouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val sport = settings.sport
    val workouts = allWorkouts.filter { it.sport == sport }
    var editing by remember { mutableStateOf<Workout?>(null) }

    editing?.let { w ->
        WorkoutEditor(
            initial = w,
            onSave = {
                vm.saveWorkout(it)
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Mes programmes — ${sport.label}", Modifier.padding(top = 8.dp)) }
            items(workouts, key = { it.id }) { w ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(w.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${formatDuration(w.totalSec)} · ${w.steps.size} étapes" +
                                        (if (w.totalDistanceM > 0) " · ${"%.1f".format(w.totalDistanceM / 1000.0)} km en distance" else "") +
                                        (w.planWeek?.let { " · coach S$it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = {
                                vm.launchWorkout(w)
                                onStart()
                            }) { Icon(Icons.Filled.PlayArrow, "Lancer") }
                            IconButton(onClick = { editing = w }) { Icon(Icons.Filled.Edit, "Modifier") }
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Plus") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (w.sport == Sport.RUNNING) "Faire sur l'elliptique" else "Faire en course") },
                                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                        onClick = {
                                            menu = false
                                            vm.runOnOtherSport(w)
                                            onStart()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Dupliquer") },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                        onClick = {
                                            menu = false
                                            vm.saveWorkout(w.copy(id = UUID.randomUUID().toString(), name = w.name + " (copie)"))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Supprimer") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = {
                                            menu = false
                                            vm.deleteWorkout(w.id)
                                        },
                                    )
                                }
                            }
                        }
                        WorkoutProfile(w, Modifier.fillMaxWidth().height(40.dp).padding(top = 6.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = {
                editing = if (sport == Sport.RUNNING) {
                    Workout(
                        sport = Sport.RUNNING,
                        steps = listOf(
                            WorkoutStep("Échauffement", 600, StepMode.HR_ZONE, 2.0),
                            WorkoutStep("Bloc principal", 1200, StepMode.PACE, 360.0),
                            WorkoutStep("Retour au calme", 300, StepMode.FREE, 0.0),
                        ),
                    )
                } else {
                    Workout(
                        steps = listOf(
                            WorkoutStep("Échauffement", 300, StepMode.RESISTANCE, 3.0),
                            WorkoutStep("Effort", 600, StepMode.RESISTANCE, 8.0),
                            WorkoutStep("Retour au calme", 300, StepMode.RESISTANCE, 2.0),
                        ),
                    )
                }
            },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Nouveau") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutEditor(initial: Workout, onSave: (Workout) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var steps by remember { mutableStateOf(initial.steps) }
    var repeatCount by remember { mutableStateOf("4") }
    BackHandler { onCancel() }

    fun update(i: Int, s: WorkoutStep) {
        steps = steps.toMutableList().also { it[i] = s }
    }

    val run = initial.sport == Sport.RUNNING
    val preview = initial.copy(name = name, steps = steps)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Éditeur de séance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCancel) { Text("Annuler") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(preview) }, enabled = name.isNotBlank() && steps.isNotEmpty()) { Text("Enregistrer") }
            }
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Durée totale : ${formatDuration(preview.totalSec)}", fontWeight = FontWeight.SemiBold)
                    WorkoutProfile(preview, Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp))
                }
            }
        }
        itemsIndexed(steps) { i, s ->
            StepEditor(
                index = i,
                step = s,
                isFirst = i == 0,
                isLast = i == steps.lastIndex,
                running = run,
                onChange = { update(i, it) },
                onDelete = { steps = steps.toMutableList().also { l -> l.removeAt(i) } },
                onMove = { delta ->
                    val j = i + delta
                    if (j in steps.indices) {
                        steps = steps.toMutableList().also { l -> val t = l[i]; l[i] = l[j]; l[j] = t }
                    }
                },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                OutlinedButton(
                    onClick = {
                        steps = steps + if (run) WorkoutStep("Étape ${steps.size + 1}", 300, StepMode.HR_ZONE, 2.0)
                        else WorkoutStep("Étape ${steps.size + 1}", 120, StepMode.RESISTANCE, 5.0)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Text("  Ajouter une étape")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val n = repeatCount.toIntOrNull()?.coerceIn(1, 50) ?: 0
                            val block = buildList {
                                repeat(n) { k ->
                                    if (run) {
                                        add(WorkoutStep("400 m ${k + 1}", 120, StepMode.HR_ZONE, 4.0, distanceM = 400))
                                        add(WorkoutStep("Récup ${k + 1}", 90, StepMode.FREE, 0.0))
                                    } else {
                                        add(WorkoutStep("Effort ${k + 1}", 60, StepMode.RESISTANCE, 10.0))
                                        add(WorkoutStep("Récup ${k + 1}", 60, StepMode.RESISTANCE, 3.0))
                                    }
                                }
                            }
                            steps = steps + block
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Ajouter des intervalles ×") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = repeatCount,
                        onValueChange = { repeatCount = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepEditor(
    index: Int,
    step: WorkoutStep,
    isFirst: Boolean,
    isLast: Boolean,
    running: Boolean,
    onChange: (WorkoutStep) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = step.name,
                    onValueChange = { onChange(step.copy(name = it)) },
                    singleLine = true,
                    label = { Text("Nom de l'étape") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) { Icon(Icons.Filled.ArrowUpward, "Monter") }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) { Icon(Icons.Filled.ArrowDownward, "Descendre") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Supprimer") }
            }
            val modes = if (running) {
                listOf(StepMode.PACE to "Allure", StepMode.HR_ZONE to "Zone cardio", StepMode.FREE to "Libre")
            } else {
                listOf(StepMode.RESISTANCE to "Résistance", StepMode.POWER to "Watts", StepMode.HR_ZONE to "Zone", StepMode.FREE to "Libre")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { (m, label) ->
                    FilterChip(
                        selected = step.mode == m,
                        onClick = {
                            val t = if (step.mode == m) {
                                step.target
                            } else {
                                when (m) {
                                    StepMode.POWER -> 100.0
                                    StepMode.RESISTANCE -> 5.0
                                    StepMode.PACE -> 360.0
                                    StepMode.HR_ZONE -> 2.0
                                    StepMode.FREE -> 0.0
                                }
                            }
                            onChange(step.copy(mode = m, target = t))
                        },
                        label = { Text(label) },
                    )
                }
            }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(step.distanceM == null, { onChange(step.copy(distanceM = null)) }, label = { Text("Durée") })
                    FilterChip(step.distanceM != null, { onChange(step.copy(distanceM = step.distanceM ?: 1000)) }, label = { Text("Distance") })
                }
            }
            if (running && step.distanceM != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Mètres",
                        value = step.distanceM,
                        onValue = { d ->
                            val pace = if (step.mode == StepMode.PACE) step.target else 390.0
                            onChange(step.copy(distanceM = d.coerceAtLeast(50), durationSec = (d / 1000.0 * pace).toInt().coerceAtLeast(10)))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    RunTargetField(step, onChange, Modifier.weight(1f))
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Minutes",
                    value = step.durationSec / 60,
                    onValue = { onChange(step.copy(durationSec = it * 60 + step.durationSec % 60)) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Secondes",
                    value = step.durationSec % 60,
                    onValue = { onChange(step.copy(durationSec = (step.durationSec / 60) * 60 + it.coerceIn(0, 59))) },
                    modifier = Modifier.weight(1f),
                )
                if (running || step.mode == StepMode.HR_ZONE) {
                    RunTargetField(step, onChange, Modifier.weight(1f))
                } else if (step.mode != StepMode.FREE) {
                    NumberField(
                        label = if (step.mode == StepMode.POWER) "Watts" else "Niveau",
                        value = step.target.toInt(),
                        onValue = { onChange(step.copy(target = it.toDouble())) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Champ numérique entier : la valeur est conservée en texte pendant la saisie. */
@Composable
private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(4)
            text.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Consigne running : allure (m:ss) ou zone cardio (1-5). */
@Composable
private fun RunTargetField(step: WorkoutStep, onChange: (WorkoutStep) -> Unit, modifier: Modifier) {
    when (step.mode) {
        StepMode.PACE -> {
            var text by remember(step.target) { mutableStateOf(formatPace(step.target.toInt()).replace("'", ":").removeSuffix("\"")) }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() || it == ':' }.take(5)
                    val parts = text.split(':')
                    val sec = if (parts.size == 2) {
                        val m = parts[0].toIntOrNull()
                        val s = parts[1].toIntOrNull()
                        if (m != null && s != null && s < 60) m * 60 + s else null
                    } else null
                    sec?.takeIf { it in 150..900 }?.let { onChange(step.copy(target = it.toDouble())) }
                },
                label = { Text("Allure /km (m:ss)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = modifier,
            )
        }
        StepMode.HR_ZONE -> NumberField(
            label = "Zone (1-5)",
            value = step.target.toInt(),
            onValue = { onChange(step.copy(target = it.coerceIn(1, 5).toDouble())) },
            modifier = modifier,
        )
        else -> Spacer(modifier)
    }
}
