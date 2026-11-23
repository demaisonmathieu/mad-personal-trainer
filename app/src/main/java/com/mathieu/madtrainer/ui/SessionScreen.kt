package com.mathieu.madtrainer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.mathieu.madtrainer.data.SessionExtension
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.LiveSession
import com.mathieu.madtrainer.ble.Capabilities
import com.mathieu.madtrainer.ble.ConnState
import com.mathieu.madtrainer.ble.MachineData
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.formatDuration

@Composable
fun SessionScreen(vm: AppViewModel, onGoToDevice: () -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    val state by vm.bike.state.collectAsStateWithLifecycle()
    val metrics by vm.bike.metrics.collectAsStateWithLifecycle()
    val caps by vm.bike.caps.collectAsStateWithLifecycle()
    val workouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val summary by vm.lastFinished.collectAsStateWithLifecycle()

    // Écran allumé pendant la séance
    val view = LocalView.current
    DisposableEffect(session.running) {
        view.keepScreenOn = session.running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        // Vidéo fixée en haut : elle reste visible quand on fait défiler les chiffres
        WebMiniPanel(vm, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicWidget(vm)
            if (state != ConnState.CONNECTED) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().clickable { onGoToDevice() },
                ) {
                    Text(
                        "Vélo non connecté — appuie ici pour te connecter. Tu peux quand même lancer une séance.",
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (state == ConnState.CONNECTED && !caps.hasFtms && !caps.legacyDomyos) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().clickable { onGoToDevice() },
                ) {
                    Text(
                        "L'appareil connecté n'est pas la console du vélo : déconnecte-le et choisis « Domyos-EL… » dans l'onglet Vélo.",
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            summary?.let {
                SummaryCard(it) { vm.dismissSummary() }
                SessionReportCard(it, vm)
            }

            if (session.running) {
                LiveElliptical(vm, session, metrics)
                ControlPanel(vm, metrics, caps)
                RunningButtons(vm, session)
                SecondaryMetrics(metrics, session)
            } else {
                LiveMetrics(metrics, session, vm)
                SectionTitle("Démarrer")
                Button(onClick = { vm.startSession(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text("  Séance libre")
                }
                workouts.filter { it.sport == com.mathieu.madtrainer.data.Sport.ELLIPTICAL }.forEach { w ->
                    Card(Modifier.fillMaxWidth().clickable { vm.startSession(w) }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(formatDuration(w.totalSec), style = MaterialTheme.typography.labelLarge)
                            }
                            WorkoutProfile(w, Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveMetrics(m: MachineData, session: LiveSession, vm: AppViewModel) {
    val tileMod = Modifier
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Puissance", m.powerW?.toString() ?: "--", "W", Icons.Filled.Bolt, PowerColor, tileMod.weight(1f), big = true)
            MetricTile("Cadence", m.cadence?.let { "%.0f".format(it) } ?: "--", "spm", Icons.Filled.Speed, CadenceColor, tileMod.weight(1f), big = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Temps", formatDuration(if (session.running) session.elapsed else m.elapsedSec ?: 0), "", Icons.Filled.Timer, MaterialTheme.colorScheme.primary, tileMod.weight(1f))
            MetricTile("Résistance", m.resistanceRaw?.let { fmtNum(vm.bike.rawToLevel(it)) } ?: "--", "", Icons.Filled.Tune, ResistanceColor, tileMod.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Vitesse", m.speedKmh?.let { "%.1f".format(it) } ?: "--", "km/h", Icons.Filled.Speed, CadenceColor, tileMod.weight(1f))
            MetricTile("Distance", m.distanceM?.let { "%.2f".format(it / 1000.0) } ?: "--", "km", Icons.Filled.Straighten, MaterialTheme.colorScheme.secondary, tileMod.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Calories", m.totalKcal?.toString() ?: "--", "kcal", Icons.Filled.LocalFireDepartment, PowerColor, tileMod.weight(1f))
            MetricTile("Cardio", m.heartRate?.toString() ?: "--", "bpm", Icons.Filled.Favorite, HrColor, tileMod.weight(1f))
        }
        if (session.running && session.samples.size > 2) {
            ChartCard("Puissance (W)", session.samples.takeLast(300).map { it.power?.toFloat() }, PowerColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlPanel(vm: AppViewModel, m: MachineData, caps: Capabilities) {
    var mode by remember { mutableIntStateOf(0) } // 0 = résistance, 1 = puissance
    var level by remember { mutableDoubleStateOf(m.resistanceRaw?.let { vm.bike.rawToLevel(it) } ?: 5.0) }
    var watts by remember { mutableIntStateOf(m.powerW?.takeIf { it > 0 } ?: 100) }
    val resRange = caps.resistanceRange
    val minLevel = resRange?.let { vm.bike.rawToLevel(it.min) } ?: 1.0
    val maxLevel = resRange?.let { vm.bike.rawToLevel(it.max) }?.takeIf { it > minLevel } ?: 32.0

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("RÉSISTANCE", style = MaterialTheme.typography.labelMedium, color = Night.Muted)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                SegmentedButton(selected = mode == 0, onClick = { mode = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("Résistance")
                }
                SegmentedButton(selected = mode == 1, onClick = { mode = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Puissance (ERG)")
                }
            }
            if (mode == 0) {
                if (caps.canResistance == false) Text("La console n'annonce pas ce mode, essai quand même.", style = MaterialTheme.typography.bodySmall)
                Stepper(
                    value = fmtNum(level),
                    unit = "niveau",
                    onMinus = {
                        level = (level - 1).coerceIn(minLevel, maxLevel)
                        vm.bike.setResistanceLevel(level)
                    },
                    onPlus = {
                        level = (level + 1).coerceIn(minLevel, maxLevel)
                        vm.bike.setResistanceLevel(level)
                    },
                )
            } else {
                if (caps.canPower == false) Text("La console n'annonce pas le mode ERG, essai quand même.", style = MaterialTheme.typography.bodySmall)
                Stepper(
                    value = watts.toString(),
                    unit = "W",
                    onMinus = {
                        watts = (watts - 10).coerceAtLeast(10)
                        vm.bike.setTargetPower(watts)
                    },
                    onPlus = {
                        watts = (watts + 10).coerceAtMost(1000)
                        vm.bike.setTargetPower(watts)
                    },
                )
            }
        }
    }
}

@Composable
private fun Stepper(value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        FilledIconButton(onClick = onMinus, modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Night.Surface2, contentColor = Night.Text)) {
            Icon(Icons.Filled.Remove, "Moins")
        }
        Spacer(Modifier.width(24.dp))
        Text(value, fontFamily = Display, fontSize = 40.sp, fontWeight = FontWeight.Bold, style = NumberStyle)
        Text(" $unit", style = MaterialTheme.typography.bodyMedium, color = Night.Muted)
        Spacer(Modifier.width(24.dp))
        FilledIconButton(onClick = onPlus, modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Night.Surface2, contentColor = Night.Text)) {
            Icon(Icons.Filled.Add, "Plus")
        }
    }
}

@Composable
private fun RunningButtons(vm: AppViewModel, session: LiveSession) {
    var confirmStop by remember { mutableStateOf(false) }
    val current = session.position?.let { session.workout?.steps?.getOrNull(it.index) }
    val canExtend = session.workout != null && !SessionExtension.isFreeExtension(current)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // « Je me sens bien » : +5 min (inactif pendant la prolongation libre)
        RoundButton(onClick = { vm.extendStep() }, size = 64, container = Night.Surface, enabled = canExtend, label = "Ajouter 5 minutes") {
            Text("+5'", fontWeight = FontWeight.SemiBold, color = if (canExtend) Night.Text else Night.Muted)
        }
        RoundButton(onClick = { vm.togglePause() }, size = 92, container = Night.Accent, label = if (session.paused) "Reprendre" else "Pause") {
            Icon(if (session.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(38.dp), tint = Night.Bg)
        }
        RoundButton(onClick = { confirmStop = true }, size = 64, container = Color(0xFF1E1215), label = "Terminer") {
            Icon(Icons.Filled.Stop, null, tint = Color(0xFFFF6B81))
        }
    }
    if (session.workout != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { vm.skipStep() }) {
                Icon(Icons.Filled.SkipNext, null)
                Text(" Étape suivante")
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Terminer la séance ?") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; vm.stopSession(save = true) }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false; vm.stopSession(save = false) }) { Text("Abandonner") }
            },
        )
    }
}

/** Séance en cours : chrono, frise du programme, puissance en très gros, cardio en couleur de zone, cadence. */
@Composable
private fun LiveElliptical(vm: AppViewModel, session: LiveSession, m: MachineData) {
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val w = session.workout
    val p = session.position
    val step = if (w != null && p != null) w.steps.getOrNull(p.index) else null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((w?.name ?: "Séance libre"), style = MaterialTheme.typography.titleLarge)
                if (session.paused) Text("EN PAUSE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
            Text(formatDuration(session.elapsed), fontFamily = Display, fontSize = 34.sp, fontWeight = FontWeight.Bold, style = NumberStyle)
        }
        if (w != null && p != null && step != null) {
            val free = SessionExtension.isFreeExtension(step)
            if (!free) WorkoutProfile(w, Modifier.fillMaxWidth().height(40.dp), currentIndex = p.index, progressSec = session.elapsed)
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Night.AccentContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, Night.Accent),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ÉTAPE ${p.index + 1} / ${w.steps.size}", style = MaterialTheme.typography.labelMedium, color = Night.AccentText, fontWeight = FontWeight.SemiBold)
                        Text(step.name.ifBlank { "Étape ${p.index + 1}" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (free) "Programme terminé : Terminer quand tu veux." else stepTargetText(step) +
                                (w.steps.getOrNull(p.index + 1)?.let { " · ensuite ${it.name.ifBlank { "étape" }}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = Night.Soft,
                        )
                    }
                    Text(
                        if (free) "+" + formatDuration(p.stepElapsed) else formatDuration(p.stepRemaining),
                        fontFamily = Display, fontSize = 32.sp, fontWeight = FontWeight.Bold, style = NumberStyle,
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Night.Surface)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PUISSANCE", style = MaterialTheme.typography.labelMedium, color = Night.Muted)
                Text(m.powerW?.toString() ?: "--", fontFamily = Display, fontSize = 84.sp, fontWeight = FontWeight.Bold, color = PowerColor, style = NumberStyle)
                Text(
                    "watts" + (step?.takeIf { it.mode == com.mathieu.madtrainer.data.StepMode.POWER }?.let { " · cible ${it.target.toInt()}" } ?: ""),
                    color = Night.Soft,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { HeartRateCard(m.heartRate?.takeIf { it > 0 }, profile.hrZones) }
            MetricTile("Cadence", m.cadence?.let { "%.0f".format(it) } ?: "--", "spm", Icons.Filled.Speed, CadenceColor, Modifier.weight(1f))
        }
    }
}

/** Valeurs secondaires pendant la séance (vitesse, distance, calories, résistance) et courbe de puissance. */
@Composable
private fun SecondaryMetrics(m: MachineData, session: LiveSession) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Night.Surface)) {
        Row(Modifier.padding(14.dp)) {
            StatRow(
                listOf(
                    "km/h" to (m.speedKmh?.let { "%.1f".format(it) } ?: "--"),
                    "km" to (m.distanceM?.let { "%.2f".format(it / 1000.0) } ?: "--"),
                    "kcal" to (m.totalKcal?.toString() ?: "--"),
                ),
            )
        }
    }
    if (session.samples.size > 2) {
        ChartCard("Puissance (W)", session.samples.takeLast(300).map { it.power?.toFloat() }, PowerColor)
    }
}

@Composable
private fun SummaryCard(rec: SessionRecord, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Séance enregistrée", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatRow(
                listOf(
                    "Durée" to formatDuration(rec.durationSec),
                    "Moy." to "${rec.avgPower} W",
                    "Max" to "${rec.maxPower} W",
                    "kcal" to rec.kcal.toString(),
                ),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("OK") }
        }
    }
}
