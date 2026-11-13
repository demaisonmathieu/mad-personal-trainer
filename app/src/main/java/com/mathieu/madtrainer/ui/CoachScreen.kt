package com.mathieu.madtrainer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.CoachState
import com.mathieu.madtrainer.coach.Goal
import com.mathieu.madtrainer.coach.Level
import com.mathieu.madtrainer.coach.SavedPlan
import com.mathieu.madtrainer.coach.Sex
import com.mathieu.madtrainer.coach.UserProfile
import com.mathieu.madtrainer.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CoachScreen(vm: AppViewModel, onStartWorkout: () -> Unit) {
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val plan by vm.repo.plan.collectAsStateWithLifecycle()
    val coach by vm.coach.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingProfile by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var showAiSettings by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mon coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ---------------------------------------------------------------- profil
        if (editingProfile) {
            ProfileEditor(profile, onSave = {
                vm.saveProfile(it)
                editingProfile = false
            }, onCancel = { editingProfile = false })
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Profil", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingProfile = true }) { Text("Modifier") }
                    }
                    Text(
                        "${profile.age} ans · ${fmtNum(profile.weightKg)} kg · ${profile.heightCm} cm · IMC ${"%.1f".format(profile.bmi)}",
                    )
                    Text("${profile.goal.label} · ${profile.level.label}")
                    Text(
                        "${profile.sessionsPerWeek} séances/sem. de ${profile.sessionMinutes} min, sur ${profile.horizonWeeks} semaines",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- génération
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Générer mon programme", fontWeight = FontWeight.SemiBold)
                Text(
                    "Le prompt contient ton profil, tes dernières séances et les capacités de ta machine. " +
                        "La réponse de l'IA crée automatiquement les séances dans Programmes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { vm.generateWithApi() },
                    enabled = settings.anthropicKey.isNotBlank() && coach != CoachState.Generating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Text(if (settings.anthropicKey.isBlank()) "  Générer avec Claude (clé API requise)" else "  Générer avec Claude")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        copyToClipboard(context, vm.buildCoachPrompt())
                        Toast.makeText(context, "Prompt copié : colle-le dans Claude", Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                        Text(" Copier le prompt")
                    }
                    OutlinedButton(onClick = { sharePrompt(context, vm.buildCoachPrompt()) }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    }
                }
                when (val c = coach) {
                    CoachState.Generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Claude prépare ton programme (1 à 3 min)…")
                    }
                    is CoachState.Done -> Text("Plan importé : ${c.workouts} séances ajoutées dans Programmes", fontWeight = FontWeight.SemiBold)
                    is CoachState.Error -> Text(c.message, color = MaterialTheme.colorScheme.error)
                    CoachState.Idle -> Unit
                }
                TextButton(onClick = { showAiSettings = !showAiSettings }) {
                    Text(if (showAiSettings) "Masquer les réglages IA" else "Réglages IA (clé API, modèle)")
                }
                if (showAiSettings) {
                    OutlinedTextField(
                        value = settings.anthropicKey,
                        onValueChange = { v -> vm.settingsStore.update { it.copy(anthropicKey = v.trim()) } },
                        label = { Text("Clé API Anthropic (sk-ant-…)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.anthropicModel,
                        onValueChange = { v -> vm.settingsStore.update { it.copy(anthropicModel = v.trim()) } },
                        label = { Text("Modèle") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Clé à créer sur console.anthropic.com (facturation à l'usage, quelques centimes par plan). Elle reste stockée sur ce téléphone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- import manuel
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Importer une réponse", fontWeight = FontWeight.SemiBold)
                Text(
                    "Après avoir collé le prompt dans Claude (ou une autre IA), copie toute sa réponse et colle-la ici.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    placeholder = { Text("Réponse de l'IA…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pasted = readClipboard(context) ?: pasted }) {
                        Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                        Text(" Coller")
                    }
                    Button(onClick = {
                        vm.importCoachResponse(pasted)
                        pasted = ""
                    }, enabled = pasted.isNotBlank()) { Text("Importer le plan") }
                }
            }
        }

        // ---------------------------------------------------------------- plan actuel
        plan?.let { PlanView(it, vm, onStartWorkout) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlanView(saved: SavedPlan, vm: AppViewModel, onStartWorkout: () -> Unit) {
    val p = saved.plan
    val workouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val planWorkouts = workouts.filter { it.id in saved.workoutIds }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.plan_name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Créé le " + SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(saved.createdAt)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (p.summary.isNotBlank()) Text(p.summary)
            if (p.weekly_structure.isNotBlank()) {
                SectionTitle("Organisation de la semaine")
                Text(p.weekly_structure)
            }
            Bullets("Conseils d'entraînement", p.advice)
            Bullets("Nutrition & hydratation", p.nutrition)
            Bullets("Récupération", p.recovery)
            if (p.progression.isNotBlank()) {
                SectionTitle("Et après ?")
                Text(p.progression)
            }
            if (p.warnings.isNotEmpty()) {
                SectionTitle("Signaux d'alerte")
                p.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Séances du programme")
            planWorkouts.groupBy { it.planWeek ?: 0 }.toSortedMap().forEach { (week, list) ->
                Text(if (week > 0) "Semaine $week" else "Séances", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                list.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name)
                            Text(
                                listOfNotNull(w.planDay?.takeIf { it.isNotBlank() }, formatDuration(w.totalSec), w.notes.takeIf { it.isNotBlank() })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = {
                            vm.startSession(w)
                            onStartWorkout()
                        }) { Icon(Icons.Filled.PlayArrow, "Lancer") }
                    }
                }
            }
            if (planWorkouts.isEmpty()) Text("Les séances de ce plan ont été supprimées de Programmes.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    if (items.isEmpty()) return
    SectionTitle(title)
    items.forEach { Text("• $it") }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(initial: UserProfile, onSave: (UserProfile) -> Unit, onCancel: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    var age by remember { mutableStateOf(initial.age.toString()) }
    var weight by remember { mutableStateOf(fmtNum(initial.weightKg)) }
    var height by remember { mutableStateOf(initial.heightCm.toString()) }
    var target by remember { mutableStateOf(initial.targetWeightKg?.let { fmtNum(it) } ?: "") }
    var resting by remember { mutableStateOf(initial.restingHr?.toString() ?: "") }
    var maxHr by remember { mutableStateOf(initial.maxHr?.toString() ?: "") }
    var perWeek by remember { mutableStateOf(initial.sessionsPerWeek.toString()) }
    var minutes by remember { mutableStateOf(initial.sessionMinutes.toString()) }
    var weeks by remember { mutableStateOf(initial.horizonWeeks.toString()) }

    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mon profil", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(p.firstName, { p = p.copy(firstName = it) }, label = { Text("Prénom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Sexe", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sex.entries.forEach { s -> FilterChip(p.sex == s, { p = p.copy(sex = s) }, label = { Text(s.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Âge", age, { age = it }, Modifier.weight(1f))
                NumField("Poids (kg)", weight, { weight = it }, Modifier.weight(1f), decimal = true)
                NumField("Taille (cm)", height, { height = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Poids visé", target, { target = it }, Modifier.weight(1f), decimal = true)
                NumField("FC repos", resting, { resting = it }, Modifier.weight(1f))
                NumField("FC max", maxHr, { maxHr = it }, Modifier.weight(1f))
            }
            Text("Objectif", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Goal.entries.forEach { g -> FilterChip(p.goal == g, { p = p.copy(goal = g) }, label = { Text(g.label) }) }
            }
            Text("Niveau", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Level.entries.forEach { l -> FilterChip(p.level == l, { p = p.copy(level = l) }, label = { Text(l.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Séances/sem.", perWeek, { perWeek = it }, Modifier.weight(1f))
                NumField("Minutes", minutes, { minutes = it }, Modifier.weight(1f))
                NumField("Semaines", weeks, { weeks = it }, Modifier.weight(1f))
            }
            OutlinedTextField(
                p.preferences, { p = p.copy(preferences = it) },
                label = { Text("Préférences") },
                placeholder = { Text("ex. j'aime les intervalles, séances le matin, pas plus de 45 min…") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                p.constraints, { p = p.copy(constraints = it) },
                label = { Text("Contraintes / limites") },
                placeholder = { Text("ex. genou fragile, reprise après une pause…") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Annuler") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = {
                    onSave(
                        p.copy(
                            age = age.toIntOrNull()?.coerceIn(10, 100) ?: p.age,
                            weightKg = num(weight)?.coerceIn(30.0, 250.0) ?: p.weightKg,
                            heightCm = height.toIntOrNull()?.coerceIn(100, 230) ?: p.heightCm,
                            targetWeightKg = num(target)?.coerceIn(30.0, 250.0),
                            restingHr = resting.toIntOrNull()?.coerceIn(30, 120),
                            maxHr = maxHr.toIntOrNull()?.coerceIn(100, 230),
                            sessionsPerWeek = perWeek.toIntOrNull()?.coerceIn(1, 7) ?: p.sessionsPerWeek,
                            sessionMinutes = minutes.toIntOrNull()?.coerceIn(10, 180) ?: p.sessionMinutes,
                            horizonWeeks = weeks.toIntOrNull()?.coerceIn(1, 16) ?: p.horizonWeeks,
                        ),
                    )
                }) { Text("Enregistrer") }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(6)) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Prompt coach", text))
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun sharePrompt(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Envoyer le prompt à…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
