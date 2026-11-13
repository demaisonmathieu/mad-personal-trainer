package com.mathieu.madtrainer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        LiveMetrics(metrics, session, vm)

        if (session.running) {
            session.workout?.let { WorkoutProgress(session) }
            ControlPanel(vm, metrics, caps)
            RunningButtons(vm, session)
        } else {
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

@Composable
private fun WorkoutProgress(session: LiveSession) {
    val w = session.workout ?: return
    val p = session.position ?: return
    val step = w.steps.getOrNull(p.index) ?: return
    val next = w.steps.getOrNull(p.index + 1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(w.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        step.name.ifBlank { "Étape ${p.index + 1}" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stepTargetText(step), style = MaterialTheme.typography.bodyMedium)
                }
                Text(formatDuration(p.stepRemaining), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { if (step.durationSec > 0) p.stepElapsed.toFloat() / step.durationSec else 1f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            WorkoutProfile(w, Modifier.fillMaxWidth().height(48.dp), currentIndex = p.index, progressSec = session.elapsed)
            Text(
                "Étape ${p.index + 1}/${w.steps.size} · reste ${formatDuration(w.totalSec - session.elapsed)}" +
                    (next?.let { " · ensuite : ${it.name.ifBlank { "étape" }} (${stepTargetText(it)})" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
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
            Text("Contrôle manuel", fontWeight = FontWeight.SemiBold)
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
        FilledIconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, "Moins") }
        Spacer(Modifier.width(24.dp))
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(" $unit", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(24.dp))
        FilledIconButton(onClick = onPlus) { Icon(Icons.Filled.Add, "Plus") }
    }
}

@Composable
private fun RunningButtons(vm: AppViewModel, session: LiveSession) {
    var confirmStop by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = { vm.togglePause() }, modifier = Modifier.weight(1f)) {
            Icon(if (session.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
            Text(if (session.paused) " Reprendre" else " Pause")
        }
        if (session.workout != null) {
            OutlinedButton(onClick = { vm.skipStep() }) {
                Icon(Icons.Filled.SkipNext, "Étape suivante")
            }
        }
        Button(onClick = { confirmStop = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Stop, null)
            Text(" Terminer")
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
