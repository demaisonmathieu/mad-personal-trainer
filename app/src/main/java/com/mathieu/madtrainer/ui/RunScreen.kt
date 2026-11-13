package com.mathieu.madtrainer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.ble.ConnState
import com.mathieu.madtrainer.data.RunEngine
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.formatDistance
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.data.formatPace
import com.mathieu.madtrainer.run.GpsStatus
import com.mathieu.madtrainer.run.RunState
import com.mathieu.madtrainer.run.RunTracker

private fun runPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun RunScreen(vm: AppViewModel, onGoToSensors: () -> Unit) {
    val context = LocalContext.current
    val run by RunTracker.state.collectAsStateWithLifecycle()
    val pending by vm.pendingRun.collectAsStateWithLifecycle()
    val workouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val hrState by vm.hr.state.collectAsStateWithLifecycle()
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val maxHr = profile.estimatedMaxHr

    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    var permError by remember { mutableStateOf<String?>(null) }
    var startAfterPermission by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permError = null
            RunTracker.warmUpGps()
            if (startAfterPermission) RunTracker.start(vm.pendingRun.value)
        } else {
            RunTracker.warmUpGps() // met l'état « Autorisation requise »
            permError = "La localisation précise est nécessaire pour mesurer la distance et l'allure."
        }
        startAfterPermission = false
    }

    // GPS allumé tant que l'écran Course est affiché (pour avoir un point avant le départ).
    // L'autorisation est demandée dès l'arrivée sur l'écran.
    DisposableEffect(Unit) {
        if (hasLocation()) RunTracker.warmUpGps() else launcher.launch(runPermissions())
        onDispose { if (!RunTracker.state.value.running) RunTracker.stopGps() }
    }
    // Si la localisation était coupée ou refusée, on revérifie régulièrement (retour des réglages)
    LaunchedEffect(run.gpsStatus) {
        while (run.gpsStatus == GpsStatus.DISABLED || run.gpsStatus == GpsStatus.NO_PERMISSION) {
            delay(3000)
            if (hasLocation()) RunTracker.warmUpGps()
        }
    }
    val view = LocalView.current
    DisposableEffect(run.running) {
        view.keepScreenOn = run.running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(run.finished) {
        if (run.finished) vm.selectRunWorkout(null)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusRow(run, hrState == ConnState.CONNECTED, onGoToSensors)
        GpsHelp(run.gpsStatus, onAskPermission = { launcher.launch(runPermissions()) })
        permError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when {
            run.running -> RunningView(run, maxHr)
            run.finished -> {
                sessions.firstOrNull { it.sport == Sport.RUNNING }?.takeIf { System.currentTimeMillis() - it.startedAt < 6 * 3600_000 }
                    ?.let { RunSummary(it) }
                Button(onClick = { RunTracker.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Nouvelle course") }
            }
            else -> {
                val runWorkouts = workouts.filter { it.sport == Sport.RUNNING }
                pending?.let { w ->
                    SelectedWorkout(w, maxHr, onClear = { vm.selectRunWorkout(null) })
                }
                Button(
                    onClick = {
                        if (hasLocation()) {
                            RunTracker.start(pending)
                        } else {
                            startAfterPermission = true
                            launcher.launch(runPermissions())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(if (pending == null) "  Démarrer une course libre" else "  Démarrer « ${pending?.name} »")
                }

                if (pending == null && runWorkouts.isNotEmpty()) {
                    SectionTitle("Ou choisis une séance guidée")
                    runWorkouts.forEach { w ->
                        Card(Modifier.fillMaxWidth().clickable { vm.selectRunWorkout(w) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(w.name, fontWeight = FontWeight.SemiBold)
                                Text(workoutSummary(w), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun workoutSummary(w: Workout): String {
    val dist = w.totalDistanceM
    return "${w.steps.size} étapes · ~${formatDuration(w.totalSec)}" + if (dist > 0) " · ${formatDistance(dist.toDouble())} en distance" else ""
}

@Composable
private fun StatusRow(run: RunState, hrConnected: Boolean, onGoToSensors: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (color, label) = when (run.gpsStatus) {
            GpsStatus.OK -> Color(0xFF2E9E4F) to "GPS OK"
            GpsStatus.WEAK -> Color(0xFFE69B00) to "GPS faible"
            GpsStatus.SEARCHING -> Color(0xFFE69B00) to "Recherche GPS…"
            GpsStatus.DISABLED -> MaterialTheme.colorScheme.error to "Localisation coupée"
            GpsStatus.NO_PERMISSION -> MaterialTheme.colorScheme.error to "Autorisation requise"
        }
        Icon(if (run.gpsStatus == GpsStatus.OK) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, null, tint = color)
        Text(
            label + (run.gpsAccuracy?.takeIf { run.gpsStatus == GpsStatus.OK || run.gpsStatus == GpsStatus.WEAK }?.let { " (±${it.toInt()} m)" } ?: ""),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.clickable { onGoToSensors() }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, null, tint = if (hrConnected) HrColor else MaterialTheme.colorScheme.outline)
            Text(if (hrConnected) " Montre" else " Pas de cardio", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SelectedWorkout(w: Workout, maxHr: Int, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Changer") }
            }
            if (w.notes.isNotBlank()) Text(w.notes, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            w.steps.forEachIndexed { i, s ->
                Text(
                    "${i + 1}. ${s.name} — ${RunEngine.stepLengthText(s)}, ${RunEngine.targetText(s, maxHr)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RunningView(run: RunState, maxHr: Int) {
    var confirmStop by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Chrono géant
        Text(
            formatDuration(run.elapsed),
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (run.paused) {
            Text("EN PAUSE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Distance", "%.2f".format(run.distanceM / 1000), "km", Icons.Filled.Straighten, MaterialTheme.colorScheme.primary, Modifier.weight(1f), big = true)
            MetricTile("Allure", formatPace(run.currentPace), "/km", Icons.Filled.Speed, CadenceColor, Modifier.weight(1f), big = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Allure moy.", formatPace(run.avgPace), "/km", Icons.Filled.Timer, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            MetricTile(
                "Cardio" + (run.hr?.let { " · Z${RunEngine.zoneOf(it, maxHr)}" } ?: ""),
                run.hr?.toString() ?: "--", "bpm", Icons.Filled.Favorite, HrColor, Modifier.weight(1f),
            )
        }

        val w = run.workout
        val step = run.step
        if (w != null && step != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Étape ${run.stepIndex + 1}/${w.steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(step.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(RunEngine.targetText(step, maxHr))
                        }
                        Text(
                            RunEngine.stepRemainingText(step, run.stepElapsed, run.stepDistance),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    val progress = step.distanceM?.let { (run.stepDistance / it).toFloat() }
                        ?: (run.stepElapsed.toFloat() / step.durationSec.coerceAtLeast(1))
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    w.steps.getOrNull(run.stepIndex + 1)?.let { n ->
                        Text(
                            "Ensuite : ${n.name} — ${RunEngine.stepLengthText(n)}, ${RunEngine.targetText(n, maxHr)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        if (run.samples.size > 5) {
            ChartCard("Allure (min/km, plus haut = plus rapide)", run.samples.takeLast(600).map { s -> s.paceSecPerKm?.let { 1000f / it } }, CadenceColor)
            if (run.samples.any { it.hr != null }) {
                ChartCard("Cardio (bpm)", run.samples.takeLast(600).map { it.hr?.toFloat() }, HrColor)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { RunTracker.togglePause() }, modifier = Modifier.weight(1f)) {
                Icon(if (run.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                Text(if (run.paused) " Reprendre" else " Pause")
            }
            if (w != null) {
                OutlinedButton(onClick = { RunTracker.skipStep() }) { Icon(Icons.Filled.SkipNext, "Étape suivante") }
            }
            Button(onClick = { confirmStop = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Stop, null)
                Text(" Terminer")
            }
        }
        Text(
            "Tu peux éteindre l'écran : le suivi et les annonces vocales continuent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Terminer la course ?") },
            confirmButton = { TextButton(onClick = { confirmStop = false; RunTracker.stop(save = true) }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { confirmStop = false; RunTracker.stop(save = false) }) { Text("Abandonner") } },
        )
    }
}

@Composable
private fun RunSummary(rec: SessionRecord) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Course enregistrée", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatRow(
                listOf(
                    "Distance" to "%.2f km".format(rec.distanceM / 1000.0),
                    "Temps" to formatDuration(rec.durationSec),
                    "Allure" to formatPace(rec.avgPaceSecPerKm),
                ),
            )
            StatRow(
                listOf(
                    "FC moy." to (rec.avgHr?.toString() ?: "--"),
                    "FC max" to (rec.maxHr?.toString() ?: "--"),
                    "kcal" to rec.kcal.toString(),
                ),
            )
        }
    }
}

/** Explique quoi faire selon l'état du GPS. */
@Composable
private fun GpsHelp(status: GpsStatus, onAskPermission: () -> Unit) {
    val context = LocalContext.current
    when (status) {
        GpsStatus.NO_PERMISSION -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("L'app a besoin de la localisation précise pour mesurer distance et allure.", color = MaterialTheme.colorScheme.onErrorContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAskPermission) { Text("Autoriser") }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("Réglages de l'app") }
                }
            }
        }
        GpsStatus.DISABLED -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("La localisation du téléphone est désactivée.", color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Activer la localisation") }
            }
        }
        GpsStatus.SEARCHING, GpsStatus.WEAK -> Text(
            "Le GPS a besoin du ciel : sors dehors et attends quelques secondes le point vert (±25 m ou mieux) avant de démarrer. " +
                "À l'intérieur, il reste en orange.",
            style = MaterialTheme.typography.bodySmall,
        )
        GpsStatus.OK -> Unit
    }
}
