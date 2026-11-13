package com.mathieu.madtrainer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.CoachState
import com.mathieu.madtrainer.StrengthPlayerState
import com.mathieu.madtrainer.coach.PAIN_AREAS
import com.mathieu.madtrainer.coach.STRENGTH_EQUIPMENT
import com.mathieu.madtrainer.coach.StrengthPlayer
import com.mathieu.madtrainer.coach.StrengthProfile
import com.mathieu.madtrainer.coach.StrengthSession
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.run.RunTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Section « Renfo » de l'onglet Coach : douleurs → prompt IA → séances guidées → suivi de la douleur. */
@Composable
fun StrengthSection(vm: AppViewModel) {
    val player by vm.player.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { RunTracker.ensureTts() }
    if (player.session != null) {
        StrengthPlayerView(player, vm)
        return
    }
    val sp by vm.repo.strengthProfile.collectAsStateWithLifecycle()
    val saved by vm.repo.strengthPlan.collectAsStateWithLifecycle()
    val logs by vm.repo.painLogs.collectAsStateWithLifecycle()
    val state by vm.strengthCoach.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    var toStart by remember { mutableStateOf<StrengthSession?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Text(
                "Ces séances sont des suggestions générées par une IA, pas un avis médical. En cas de douleur forte, " +
                    "qui s'aggrave ou s'accompagne de perte de force, d'engourdissement ou de troubles urinaires, consulte un médecin.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        PainForm(sp) { vm.saveStrengthProfile(it) }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Générer mon programme de renfo", fontWeight = FontWeight.SemiBold)
                Text(
                    "Le prompt reprend tes douleurs, ton matériel, tes dernières séances cardio" +
                        (if (logs.isNotEmpty()) ", et l'évolution de ta douleur après chaque séance de renfo" else "") + ".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { vm.generateStrengthWithApi() },
                    enabled = settings.anthropicKey.isNotBlank() && state != CoachState.Generating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Text(if (settings.anthropicKey.isBlank()) "  Générer avec Claude (clé API requise)" else "  Générer avec Claude")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        copyToClipboard(context, vm.buildStrengthPrompt())
                        Toast.makeText(context, "Prompt copié : colle-le dans Claude", Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                        Text(" Copier le prompt")
                    }
                    OutlinedButton(onClick = { sharePrompt(context, vm.buildStrengthPrompt()) }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    }
                }
                when (val c = state) {
                    CoachState.Generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Préparation du programme (1 à 3 min)…")
                    }
                    is CoachState.Done -> Column {
                        Text("Programme importé : ${c.workouts} séances", fontWeight = FontWeight.SemiBold)
                        if (c.truncated) {
                            Text(
                                "Réponse coupée : seules les séances complètes ont été importées.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is CoachState.Error -> Text(c.message, color = MaterialTheme.colorScheme.error)
                    CoachState.Idle -> Unit
                }
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    placeholder = { Text("…ou colle ici la réponse de l'IA") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 180.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pasted = readClipboard(context) ?: pasted }) {
                        Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                        Text(" Coller")
                    }
                    Button(onClick = {
                        vm.importStrengthResponse(pasted)
                        pasted = ""
                    }, enabled = pasted.isNotBlank()) { Text("Importer") }
                }
            }
        }

        saved?.let { s ->
            val p = s.plan
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.plan_name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (p.summary.isNotBlank()) Text(p.summary)
                    if (p.red_flags.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Text("  Consulte rapidement si :", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                p.red_flags.forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer) }
                            }
                        }
                    }
                    if (p.safety.isNotEmpty()) {
                        SectionTitle("Règles de sécurité")
                        p.safety.forEach { Text("• $it") }
                    }
                    if (p.daily_habits.isNotEmpty()) {
                        SectionTitle("Au quotidien")
                        p.daily_habits.forEach { Text("• $it") }
                    }
                    if (p.progression.isNotBlank()) {
                        SectionTitle("Progression")
                        Text(p.progression)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SectionTitle("Séances")
                    p.sessions.groupBy { it.week }.toSortedMap().forEach { (week, list) ->
                        Text("Semaine $week", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        list.forEach { sess ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(sess.name)
                                    Text(
                                        listOfNotNull(
                                            sess.day.takeIf { it.isNotBlank() },
                                            "${sess.exercises.size} exercices",
                                            "~${formatDuration(StrengthPlayer.estimatedSeconds(sess))}",
                                            sess.focus.takeIf { it.isNotBlank() },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                IconButton(onClick = { toStart = sess }) { Icon(Icons.Filled.PlayArrow, "Lancer") }
                            }
                            SessionExercises(sess, vm)
                        }
                    }
                }
            }
        }

        if (logs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    SectionTitle("Suivi de la douleur")
                    val df = SimpleDateFormat("d MMM", Locale.FRANCE)
                    logs.sortedByDescending { it.date }.take(10).forEach { l ->
                        val trend = when {
                            l.painAfter < l.painBefore -> "↘"
                            l.painAfter > l.painBefore -> "↗"
                            else -> "→"
                        }
                        Text(
                            "${df.format(Date(l.date))} · ${l.sessionName} : ${l.painBefore} $trend ${l.painAfter}/10" +
                                if (l.note.isNotBlank()) " — ${l.note}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (l.painAfter > l.painBefore) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    toStart?.let { sess ->
        var before by remember(sess) { mutableFloatStateOf(sp.painLevel.toFloat()) }
        AlertDialog(
            onDismissRequest = { toStart = null },
            title = { Text(sess.name) },
            text = {
                Column {
                    Text("Douleur actuelle : ${before.toInt()}/10")
                    Slider(value = before, onValueChange = { before = it }, valueRange = 0f..10f, steps = 9)
                    if (before >= 7) {
                        Text(
                            "Douleur élevée : préfère le repos ou les versions les plus faciles, et demande un avis médical si elle persiste.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.startStrength(sess, before.toInt())
                    toStart = null
                }) { Text("Démarrer") }
            },
            dismissButton = { TextButton(onClick = { toStart = null }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PainForm(initial: StrengthProfile, onSave: (StrengthProfile) -> Unit) {
    var p by remember(initial) { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(initial.painAreas.isEmpty() && initial.painDetails.isBlank()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Douleurs et matériel", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Réduire" else "Modifier") }
            }
            if (!expanded) {
                Text(
                    (if (p.painAreas.isEmpty()) "Aucune douleur signalée" else p.painAreas.joinToString(", ") + " · ${p.painLevel}/10") +
                        " · ${p.sessionsPerWeek}×${p.sessionMinutes} min/sem.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
            Text("Zones douloureuses / problèmes", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PAIN_AREAS.forEach { a ->
                    FilterChip(
                        selected = a in p.painAreas,
                        onClick = { p = p.copy(painAreas = if (a in p.painAreas) p.painAreas - a else p.painAreas + a) },
                        label = { Text(a) },
                    )
                }
            }
            Text("Intensité actuelle : ${p.painLevel}/10", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = p.painLevel.toFloat(),
                onValueChange = { p = p.copy(painLevel = it.toInt()) },
                valueRange = 0f..10f,
                steps = 9,
            )
            OutlinedTextField(
                p.painDetails, { p = p.copy(painDetails = it) },
                label = { Text("Détails") },
                placeholder = { Text("ex. sciatique gauche depuis 3 semaines, pire assis et en se penchant, mieux en marchant, vu par le médecin…") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Matériel", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STRENGTH_EQUIPMENT.forEach { e ->
                    FilterChip(
                        selected = e in p.equipment,
                        onClick = { p = p.copy(equipment = if (e in p.equipment) p.equipment - e else p.equipment + e) },
                        label = { Text(e) },
                    )
                }
            }
            Text("Séances par semaine : ${p.sessionsPerWeek}", style = MaterialTheme.typography.labelLarge)
            Slider(p.sessionsPerWeek.toFloat(), { p = p.copy(sessionsPerWeek = it.toInt()) }, valueRange = 1f..6f, steps = 4)
            Text("Durée : ${p.sessionMinutes} min", style = MaterialTheme.typography.labelLarge)
            Slider(p.sessionMinutes.toFloat(), { p = p.copy(sessionMinutes = (it / 5).toInt() * 5) }, valueRange = 10f..60f, steps = 9)
            OutlinedTextField(
                p.goals, { p = p.copy(goals = it) },
                label = { Text("Objectifs du renfo") },
                placeholder = { Text("ex. soulager la sciatique, gainage pour la course, mobilité des hanches…") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                onSave(p)
                expanded = false
            }) { Text("Enregistrer") }
            }
        }
    }
}

@Composable
private fun StrengthPlayerView(p: StrengthPlayerState, vm: AppViewModel) {
    if (p.finished) {
        FinishDialog(p, vm)
        return
    }
    val step = p.step ?: return
    val next = p.steps.getOrNull(p.index + 1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(p.session?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { (p.index + 1f) / p.steps.size }, modifier = Modifier.fillMaxWidth())
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (step.rest) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(step.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (!step.rest) Text("Série ${step.set}/${step.totalSets}")
                Spacer(Modifier.height(8.dp))
                Text(
                    p.remaining?.let { formatDuration(it.coerceAtLeast(0)) } ?: "${step.exercise.reps ?: 0} répétitions",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (p.paused) Text("EN PAUSE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
        if (!step.rest) {
            val e = step.exercise
            ExerciseIllustration(vm.exerciseCatalog.find(e))
            YoutubeDemoButton(e.name, Modifier.align(Alignment.CenterHorizontally))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (e.instructions.isNotBlank()) Text(e.instructions)
                    if (e.cues.isNotBlank()) Text("À surveiller : ${e.cues}", style = MaterialTheme.typography.bodySmall)
                    if (e.easier.isNotBlank()) Text("Plus facile : ${e.easier}", style = MaterialTheme.typography.bodySmall)
                    if (e.harder.isNotBlank()) Text("Plus dur : ${e.harder}", style = MaterialTheme.typography.bodySmall)
                    if (e.avoid_if.isNotBlank()) Text("À éviter si : ${e.avoid_if}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            next?.let {
                Text("Ensuite : ${it.label}", style = MaterialTheme.typography.titleMedium)
                if (!it.rest) ExerciseIllustration(vm.exerciseCatalog.find(it.exercise), maxHeight = 160.dp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { vm.previousStrengthStep() }) { Icon(Icons.Filled.SkipPrevious, "Précédent") }
            FilledIconButton(onClick = { vm.toggleStrengthPause() }) {
                Icon(if (p.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Pause")
            }
            if (p.remaining == null) {
                Button(onClick = { vm.nextStrengthStep() }) {
                    Icon(Icons.Filled.Check, null)
                    Text(" Fait")
                }
            } else {
                FilledIconButton(onClick = { vm.nextStrengthStep() }) { Icon(Icons.Filled.SkipNext, "Suivant") }
            }
        }
        TextButton(onClick = { vm.finishStrength(null, "") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Arrêter la séance")
        }
        Text(
            "Si un exercice réveille ou aggrave la douleur (> 3/10), passe à la version plus facile ou à l'exercice suivant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FinishDialog(p: StrengthPlayerState, vm: AppViewModel) {
    var after by remember { mutableFloatStateOf(p.painBefore.toFloat()) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Séance terminée") },
        text = {
            Column {
                Text("Douleur maintenant : ${after.toInt()}/10 (avant : ${p.painBefore}/10)")
                Slider(value = after, onValueChange = { after = it }, valueRange = 0f..10f, steps = 9)
                OutlinedTextField(note, { note = it }, label = { Text("Remarque (exercice gênant…)") }, modifier = Modifier.fillMaxWidth())
                if (after.toInt() >= p.painBefore + 2) {
                    Text(
                        "La douleur a augmenté : le prochain programme évitera les exercices en cause. Si elle persiste demain, demande un avis médical.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.finishStrength(after.toInt(), note) }) { Text("Enregistrer") } },
    )
}

/** Liste dépliable des exercices d'une séance, avec illustration et lien vidéo. */
@Composable
private fun SessionExercises(sess: StrengthSession, vm: AppViewModel) {
    var open by remember(sess.name) { mutableStateOf(false) }
    val context = LocalContext.current
    TextButton(onClick = { open = !open }) { Text(if (open) "Masquer les exercices" else "Voir les exercices") }
    if (open) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sess.exercises.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExerciseIllustration(vm.exerciseCatalog.find(e), modifier = Modifier.weight(0.4f), maxHeight = 80.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(0.6f)) {
                        Text(e.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${e.sets} × " + (e.reps?.let { "$it reps" } ?: "${e.duration_sec ?: 0} s") +
                                (if (e.per_side) " / côté" else "") + " · repos ${e.rest_sec} s",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { openYoutubeDemo(context, e.name) }) { Text("Vidéo YouTube") }
                    }
                }
            }
        }
    }
}
