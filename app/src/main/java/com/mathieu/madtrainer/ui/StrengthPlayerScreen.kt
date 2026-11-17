package com.mathieu.madtrainer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.StrengthPlayerState
import com.mathieu.madtrainer.coach.PlayerStep
import com.mathieu.madtrainer.coach.StrengthExercise
import com.mathieu.madtrainer.data.formatDuration

private val RestBg = Color(0xFF0B1A20)
private val RestColor = Night.Water

/**
 * Lecteur de renfo en plein écran, pensé pour un téléphone posé au sol à 1–2 m :
 * phase (travail / repos) lisible à la couleur, chiffre géant, un seul gros bouton d'action,
 * consignes repliées pour ne pas avoir à faire défiler pendant l'effort.
 */
@Composable
fun StrengthPlayerScreen(vm: AppViewModel) {
    val p by vm.player.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var confirmStop by remember { mutableStateOf(false) }
    // Retour : demande confirmation pendant la séance, ignoré sur le bilan (à enregistrer)
    BackHandler { if (!p.finished) confirmStop = true }

    val step = p.step
    val rest = step?.rest == true && !p.finished
    Box(
        Modifier.fillMaxSize().background(if (rest) RestBg else Night.Bg).statusBarsPadding().navigationBarsPadding(),
    ) {
        when {
            p.finished -> FinishView(p, vm)
            step != null -> PlayerView(p, step, vm, onStop = { confirmStop = true })
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Arrêter la séance ?") },
            text = { Text("La séance ne sera pas enregistrée comme faite.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    vm.finishStrength(null, "")
                }) { Text("Arrêter", color = Night.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continuer") } },
        )
    }
}

@Composable
private fun PlayerView(p: StrengthPlayerState, step: PlayerStep, vm: AppViewModel, onStop: () -> Unit) {
    val session = p.session ?: return
    val next = p.steps.getOrNull(p.index + 1)
    val phaseColor = if (step.rest) RestColor else Night.AccentText
    var showTips by remember(step.exerciseIndex) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // En-tête : séance, position, temps écoulé, fermer
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleSmall, color = Night.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Exercice ${step.exerciseIndex + 1}/${session.exercises.size} · ${formatDuration(p.elapsed)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Night.Muted,
                )
            }
            IconButton(onClick = onStop) { Icon(Icons.Filled.Close, "Arrêter la séance", tint = Night.Muted) }
        }
        ExerciseProgress(session.exercises.size, step.exerciseIndex, if (step.rest) RestColor else Night.Accent)

        // Phase et exercice
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (step.rest) "REPOS" else "SÉRIE ${step.set}/${step.totalSets}" + (step.side?.let { " · ${it.uppercase()}" } ?: ""),
                color = phaseColor,
                fontFamily = Display,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp,
            )
            Text(
                if (step.rest) next?.let { "Ensuite : ${it.label}" } ?: "Dernière récup" else step.exercise.name,
                style = if (step.rest) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                color = Night.Text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Zone centrale : illustration (ou consignes) qui prend la place disponible
        val shown: StrengthExercise? = if (step.rest) next?.takeIf { !it.rest }?.exercise else step.exercise
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                showTips && shown != null -> Tips(shown)
                shown != null -> ExerciseIllustration(vm.exerciseCatalog.find(shown), maxHeight = 600.dp)
            }
        }
        if (shown != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showTips = !showTips }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Info, null, Modifier.size(18.dp))
                    Text(if (showTips) "  Illustration" else "  Consignes", maxLines = 1)
                }
                YoutubeDemoButton(shown.name, Modifier.weight(1f))
            }
        }

        // Le chiffre géant : chrono avec anneau, ou nombre de répétitions
        BigCounter(p, step, phaseColor)

        // Commandes : un gros bouton d'action, précédent / pause / suivant autour
        Controls(p, step, vm)
    }
}

/** Une barre par exercice : faits, en cours, à venir. */
@Composable
private fun ExerciseProgress(count: Int, current: Int, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(
                    when {
                        i < current -> color.copy(alpha = 0.55f)
                        i == current -> color
                        else -> Night.Line
                    },
                ),
            )
        }
    }
}

@Composable
private fun BigCounter(p: StrengthPlayerState, step: PlayerStep, color: Color) {
    Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
        val remaining = p.remaining
        val total = step.seconds
        if (remaining != null && total != null && total > 0) {
            CircularProgressIndicator(
                progress = { remaining.coerceAtLeast(0) / total.toFloat() },
                modifier = Modifier.size(170.dp),
                color = color,
                trackColor = Night.Line,
                strokeWidth = 10.dp,
                strokeCap = StrokeCap.Round,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (remaining >= 60) formatDuration(remaining.coerceAtLeast(0)) else remaining.coerceAtLeast(0).toString(),
                    fontFamily = Display,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (remaining >= 60) 52.sp else 72.sp,
                    style = NumberStyle,
                    color = if (p.paused) Night.Muted else Night.Text,
                )
                Text(if (p.paused) "EN PAUSE" else "secondes", color = if (p.paused) Night.Bad else Night.Muted, fontWeight = FontWeight.SemiBold)
            }
        } else {
            // Répétitions : pas de chrono, on valide quand c'est fait
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${step.exercise.reps ?: 0}",
                    fontFamily = Display,
                    fontWeight = FontWeight.Bold,
                    fontSize = 110.sp,
                    lineHeight = 110.sp,
                    style = NumberStyle,
                    color = Night.Text,
                )
                Text(
                    "répétitions" + (step.side?.let { " · côté $it" } ?: ""),
                    color = Night.Muted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Controls(p: StrengthPlayerState, step: PlayerStep, vm: AppViewModel) {
    val reps = p.remaining == null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Action principale, grande et en bas (atteignable du pouce, visible de loin)
        Button(
            onClick = {
                when {
                    reps -> vm.nextStrengthStep(done = true)
                    step.rest -> vm.nextStrengthStep()
                    else -> vm.toggleStrengthPause()
                }
            },
            modifier = Modifier.fillMaxWidth().height(76.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    reps -> Night.Accent
                    step.rest -> RestColor
                    else -> Night.Surface2
                },
                contentColor = if (reps || step.rest) Color.Black else Night.Text,
            ),
        ) {
            Icon(
                when {
                    reps -> Icons.Filled.Check
                    step.rest -> Icons.Filled.SkipNext
                    p.paused -> Icons.Filled.PlayArrow
                    else -> Icons.Filled.Pause
                },
                null,
                Modifier.size(30.dp),
            )
            Text(
                when {
                    reps -> "  Série faite"
                    step.rest -> "  Passer le repos"
                    p.paused -> "  Reprendre"
                    else -> "  Pause"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton({ vm.previousStrengthStep() }, 56, Night.Surface2, "Précédent", enabled = p.index > 0) {
                Icon(Icons.Filled.SkipPrevious, null, tint = Night.Text)
            }
            if (step.rest) {
                OutlinedButton(onClick = { vm.extendStrengthStep(15) }, modifier = Modifier.height(56.dp)) { Text("+15 s", fontSize = 18.sp) }
            }
            // Pause disponible partout (sauf quand c'est déjà le gros bouton)
            if (reps || step.rest) {
                RoundButton({ vm.toggleStrengthPause() }, 56, Night.Surface2, if (p.paused) "Reprendre" else "Pause") {
                    Icon(if (p.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, tint = Night.Text)
                }
            }
            // Passer une étape de travail : la série n'est pas comptée comme faite
            if (!step.rest) {
                RoundButton({ vm.nextStrengthStep() }, 56, Night.Surface2, "Passer") {
                    Icon(Icons.Filled.SkipNext, null, tint = Night.Text)
                }
            }
        }
    }
}

/** Consignes de l'exercice, à la place de l'illustration (défilables si longues). */
@Composable
private fun Tips(e: StrengthExercise) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Night.Surface).border(1.dp, Night.Line, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (e.instructions.isNotBlank()) Text(e.instructions, style = MaterialTheme.typography.bodyLarge, color = Night.Text)
        if (e.cues.isNotBlank()) Tip("À surveiller", e.cues, Night.AccentText)
        if (e.easier.isNotBlank()) Tip("Plus facile", e.easier, Night.Good)
        if (e.harder.isNotBlank()) Tip("Plus dur", e.harder, Night.Soft)
        if (e.avoid_if.isNotBlank()) Tip("À éviter si", e.avoid_if, Night.Bad)
        Text(
            "Si un exercice réveille ou aggrave la douleur (> 3/10), passe à la version plus facile ou à l'exercice suivant.",
            style = MaterialTheme.typography.bodySmall,
            color = Night.Muted,
        )
    }
}

@Composable
private fun Tip(title: String, text: String, color: Color) {
    Row {
        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Night.Soft)
        }
    }
}

/** Fin de séance en plein écran : récap, douleur après, remarque, enregistrer. */
@Composable
private fun FinishView(p: StrengthPlayerState, vm: AppViewModel) {
    var after by remember { mutableFloatStateOf(p.painBefore.toFloat()) }
    var note by remember { mutableStateOf("") }
    val recap = p.recap
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Eyebrow("Séance terminée", Night.Good)
        Text(p.session?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Night.Text)
        NightCard {
            StrengthRecap(p.elapsed, recap.sumOf { it.setsDone }, recap.sumOf { it.sets }, recap)
        }
        NightCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Douleur maintenant : ${after.toInt()}/10", style = MaterialTheme.typography.titleMedium, color = Night.Text)
                Text("Avant la séance : ${p.painBefore}/10", style = MaterialTheme.typography.bodySmall, color = Night.Muted)
                Slider(value = after, onValueChange = { after = it }, valueRange = 0f..10f, steps = 9)
                if (after.toInt() >= p.painBefore + 2) {
                    Text(
                        "La douleur a augmenté : le prochain programme évitera les exercices en cause. Si elle persiste demain, demande un avis médical.",
                        color = Night.Bad,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider(color = Night.Line)
                OutlinedTextField(note, { note = it }, label = { Text("Remarque (exercice gênant…)") }, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { vm.finishStrength(after.toInt(), note) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Night.Accent, contentColor = Color.Black),
        ) { Text("Enregistrer la séance", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}
