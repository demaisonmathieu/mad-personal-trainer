package com.mathieu.madtrainer.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
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
import com.mathieu.madtrainer.coach.ExerciseCatalog
import com.mathieu.madtrainer.coach.StrengthExercise
import com.mathieu.madtrainer.coach.StrengthPlayer
import com.mathieu.madtrainer.coach.StrengthSession
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.data.formatDuration

/** Édition d'une séance de renfo : jour, exercices (ordre par glisser-déposer), séries, reps / durée, repos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrengthSessionEditor(initial: StrengthSession, catalog: ExerciseCatalog, onSave: (StrengthSession) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var day by remember { mutableStateOf(initial.day) }
    var exercises by remember { mutableStateOf(initial.exercises) }
    val reorder = rememberReorderState()
    BackHandler { onCancel() }
    val preview = initial.copy(name = name, day = day, exercises = exercises)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Séance de renfo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCancel) { Text("Annuler") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(preview) }, enabled = name.isNotBlank() && exercises.isNotEmpty()) { Text("Enregistrer") }
            }
        }
        item {
            OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Column {
                Text("Jour · durée estimée ${formatDuration(StrengthPlayer.estimatedSeconds(preview))}", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        itemsIndexed(exercises) { i, e ->
            ExerciseCard(
                index = i,
                e = e,
                count = exercises.size,
                reorder = reorder,
                catalog = catalog,
                onChange = { n -> exercises = exercises.toMutableList().also { it[i] = n } },
                onDelete = { exercises = exercises.toMutableList().also { it.removeAt(i) } },
                onSwap = { a, b -> exercises = exercises.swapped(a, b) },
            )
        }
        item {
            OutlinedButton(
                onClick = { exercises = exercises + StrengthExercise("Nouvel exercice", sets = 2, reps = 10, rest_sec = 45) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Icon(Icons.Filled.Add, null)
                Text("  Ajouter un exercice")
            }
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
    onChange: (StrengthExercise) -> Unit,
    onDelete: () -> Unit,
    onSwap: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().reorderItem(reorder, index)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DragHandle(reorder, index, count, onSwap)
                Text("${index + 1}.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(e.name, { onChange(e.copy(name = it)) }, singleLine = true, label = { Text("Exercice") }, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Supprimer") }
            }
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
