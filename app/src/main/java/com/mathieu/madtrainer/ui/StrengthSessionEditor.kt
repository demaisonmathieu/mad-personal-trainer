package com.mathieu.madtrainer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mathieu.madtrainer.coach.CatalogExercise
import com.mathieu.madtrainer.coach.ExerciseCatalog
import com.mathieu.madtrainer.coach.ExerciseLibrary
import com.mathieu.madtrainer.coach.MuscleGroup
import com.mathieu.madtrainer.coach.SessionAdvice
import com.mathieu.madtrainer.coach.SessionAdvisor
import com.mathieu.madtrainer.coach.StrengthExercise
import com.mathieu.madtrainer.coach.StrengthPlayer
import com.mathieu.madtrainer.coach.StrengthProfile
import com.mathieu.madtrainer.coach.StrengthSession
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.data.formatDuration

/**
 * Constructeur / éditeur de séance de renfo : exercices tirés de la banque (recherche, filtres) ou libres,
 * ordre par glisser-déposer, séries, reps / durée, repos, et conseils d'association en direct.
 * [allowNoDay] : séance perso, jour récurrent (chaque semaine) ou « non planifiée ».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StrengthSessionEditor(
    initial: StrengthSession,
    catalog: ExerciseCatalog,
    profile: StrengthProfile,
    onSave: (StrengthSession) -> Unit,
    onCancel: () -> Unit,
    allowNoDay: Boolean = false,
) {
    var name by remember { mutableStateOf(initial.name) }
    var day by remember { mutableStateOf(initial.day) }
    var exercises by remember { mutableStateOf(initial.exercises) }
    var library by remember { mutableStateOf(initial.exercises.isEmpty()) }
    val reorder = rememberReorderState()
    val preview = initial.copy(name = name, day = day, exercises = exercises)
    val estimated = StrengthPlayer.estimatedSeconds(preview)
    val advice = remember(exercises, profile) { SessionAdvisor.analyze(preview, catalog, profile, estimated) }
    val flagged = advice.filter { it.level == SessionAdvice.Level.WARNING }.flatMap { it.exercises }.toSet()

    fun add(ex: CatalogExercise) {
        exercises = exercises + ExerciseLibrary.toSessionExercise(ex)
    }

    if (library) {
        ExerciseLibraryScreen(
            catalog = catalog,
            profile = profile,
            addedIds = exercises.mapNotNull { it.image_id?.takeIf { id -> id.isNotBlank() } }.toSet(),
            onAdd = { add(it) },
            onClose = { library = false },
        )
        return
    }
    BackHandler { onCancel() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ScreenHeader(
                if (initial.exercises.isEmpty() && initial.name.isBlank()) "Nouvelle séance" else "Séance de renfo",
                eyebrow = "${exercises.size} exercice(s) · ~${formatDuration(estimated)}",
            ) {
                Row {
                    OutlinedButton(onClick = onCancel) { Text("Annuler") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(preview) },
                        enabled = name.isNotBlank() && exercises.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Night.Accent, contentColor = Night.Bg),
                    ) { Text("Enregistrer") }
                }
            }
        }
        item {
            OutlinedTextField(name, { name = it }, label = { Text("Nom de la séance") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Column {
                Eyebrow(if (allowNoDay) "Dans ma semaine (chaque semaine)" else "Jour")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (allowNoDay) {
                        FilterChip(selected = WeekPlanner.dayIndex(day) < 0, onClick = { day = "" }, label = { Text("Non planifiée") })
                    }
                    WeekPlanner.DAYS.forEach { d ->
                        FilterChip(
                            selected = WeekPlanner.dayIndex(day) == WeekPlanner.dayIndex(d),
                            onClick = { day = d },
                            label = { Text(d.take(3)) },
                        )
                    }
                }
            }
        }
        item { MuscleSummary(exercises, catalog) }
        if (advice.isNotEmpty()) item { AdviceCard(advice, onAdd = { add(it) }) }
        itemsIndexed(exercises) { i, e ->
            ExerciseCard(
                index = i,
                e = e,
                count = exercises.size,
                reorder = reorder,
                catalog = catalog,
                flagged = i in flagged,
                onChange = { n -> exercises = exercises.toMutableList().also { it[i] = n } },
                onDelete = { exercises = exercises.toMutableList().also { it.removeAt(i) } },
                onSwap = { a, b -> exercises = exercises.swapped(a, b) },
            )
        }
        item {
            Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { library = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Night.Accent, contentColor = Night.Bg),
                ) {
                    Icon(Icons.Filled.Search, null)
                    Text("  Ajouter depuis la banque d'exercices")
                }
                OutlinedButton(
                    onClick = { exercises = exercises + StrengthExercise("Nouvel exercice", sets = 2, reps = 10, rest_sec = 45) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Text("  Exercice libre")
                }
            }
        }
    }
}

/** Séries par groupe musculaire travaillé (muscle principal des exercices reconnus). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MuscleSummary(exercises: List<StrengthExercise>, catalog: ExerciseCatalog) {
    val sets = exercises.mapNotNull { e -> catalog.find(e)?.takeIf { it.c != "stretching" }?.let { MuscleGroup.of(it.m) to e.sets } }
        .filter { it.first != null }
        .groupBy({ it.first!! }, { it.second })
        .mapValues { it.value.sum() }
    if (sets.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Séries par groupe")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MuscleGroup.entries.filter { it in sets }.forEach { g -> Pill("${g.label} · ${sets[g]}") }
        }
    }
}

/** Conseils du coach sur la séance, avec ajout direct des exercices suggérés. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdviceCard(advice: List<SessionAdvice>, onAdd: (CatalogExercise) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val sorted = advice.sortedBy { it.level.ordinal }
    val shown = if (expanded) sorted else sorted.take(2)
    NightCard {
        Eyebrow("Conseils du coach · ${advice.size}")
        shown.forEach { a ->
            val (icon, tint) = when (a.level) {
                SessionAdvice.Level.WARNING -> Icons.Filled.Warning to Night.Bad
                SessionAdvice.Level.TIP -> Icons.Filled.Lightbulb to Night.AccentText
                SessionAdvice.Level.GOOD -> Icons.Filled.CheckCircle to Night.Good
            }
            Row {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp, end = 8.dp).size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.title, fontWeight = FontWeight.SemiBold)
                    Text(a.text, style = MaterialTheme.typography.bodySmall, color = Night.Soft)
                    if (a.suggestions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            a.suggestions.forEach { ex ->
                                AssistChip(
                                    onClick = { onAdd(ex) },
                                    label = { Text(ex.label, maxLines = 1) },
                                    leadingIcon = { Icon(Icons.Filled.Add, "Ajouter", Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (advice.size > 2) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Réduire" else "Voir les ${advice.size} conseils") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseCard(
    index: Int,
    e: StrengthExercise,
    count: Int,
    reorder: ReorderState,
    catalog: ExerciseCatalog,
    flagged: Boolean,
    onChange: (StrengthExercise) -> Unit,
    onDelete: () -> Unit,
    onSwap: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().reorderItem(reorder, index),
        border = if (flagged) BorderStroke(1.dp, Night.Bad) else null,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DragHandle(reorder, index, count, onSwap)
                Text("${index + 1}.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(e.name, { onChange(e.copy(name = it)) }, singleLine = true, label = { Text("Exercice") }, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Supprimer") }
            }
            if (flagged) Text("À surveiller avec tes douleurs (voir les conseils)", style = MaterialTheme.typography.bodySmall, color = Night.Bad)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(e.reps != null, { onChange(e.copy(reps = e.reps ?: 10, duration_sec = null)) }, label = { Text("Répétitions") })
                FilterChip(e.reps == null, { onChange(e.copy(duration_sec = e.duration_sec ?: 30, reps = null)) }, label = { Text("Durée") })
                Checkbox(e.per_side, { onChange(e.copy(per_side = it)) })
                Text("chaque côté", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Séries", e.sets, { onChange(e.copy(sets = it.coerceIn(1, 10))) }, Modifier.weight(1f))
                if (e.reps != null) {
                    NumberField("Reps", e.reps, { onChange(e.copy(reps = it.coerceIn(1, 100))) }, Modifier.weight(1f))
                } else {
                    NumberField("Secondes", e.duration_sec ?: 30, { onChange(e.copy(duration_sec = it.coerceIn(5, 600))) }, Modifier.weight(1f))
                }
                NumberField("Repos (s)", e.rest_sec, { onChange(e.copy(rest_sec = it.coerceIn(0, 300))) }, Modifier.weight(1f))
            }
            // Illustration (banque d'images) + vidéo YouTube
            var picking by remember { mutableStateOf(false) }
            val illus = catalog.find(e)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseIllustration(illus, modifier = Modifier.weight(1f).clickable { picking = true }, maxHeight = 110.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(illus?.n ?: "Aucune illustration", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { picking = true }) { Text("Changer l'image") }
                    TextButton(onClick = { openYoutubeDemo(context, e.name) }) { Text("Vidéo YouTube") }
                }
            }
            if (picking) {
                ExerciseImagePicker(
                    catalog = catalog,
                    initialQuery = e.name,
                    onPick = { picked ->
                        onChange(e.copy(image_id = picked?.id ?: ""))
                        picking = false
                    },
                    onDismiss = { picking = false },
                )
            }
            OutlinedTextField(
                e.instructions, { onChange(e.copy(instructions = it)) },
                label = { Text("Consignes") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
