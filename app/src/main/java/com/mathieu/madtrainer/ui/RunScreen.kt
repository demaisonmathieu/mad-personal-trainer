package com.mathieu.madtrainer.ui

import com.mathieu.madtrainer.data.HrZones
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mathieu.madtrainer.data.StepMode
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
import com.mathieu.madtrainer.data.SessionExtension
import androidx.compose.material.icons.filled.MoreTime
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
    val maxHr = profile.hrZones

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
        MusicWidget(vm)
        WebMiniPanel(vm)
        GpsHelp(run.gpsStatus, onAskPermission = { launcher.launch(runPermissions()) })
        permError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when {
            run.running -> RunningView(run, maxHr)
            run.finished -> {
                sessions.firstOrNull { it.sport == Sport.RUNNING }?.takeIf { System.currentTimeMillis() - it.startedAt < 6 * 3600_000 }
                    ?.let {
                        RouteMapCard(it.samples)
                        RunSummary(it)
                        ElevationCard(it)
                        KmSplitsCard(it, maxHr)
                        VmaCard(it, vm)
                        SessionReportCard(it, vm)
                    }
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
                profile.vmaKmh?.let { v ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) { PaceTable(v, profile.vmaDate) }
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
private fun SelectedWorkout(w: Workout, maxHr: HrZones, onClear: () -> Unit) {
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
private fun RunningView(run: RunState, maxHr: HrZones) {
    var confirmStop by remember { mutableStateOf(false) }
    val w = run.workout
    val step = run.step
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Chrono géant
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatDuration(run.elapsed),
                fontFamily = Display,
                fontSize = 76.sp,
                fontWeight = FontWeight.Bold,
                style = NumberStyle,
            )
            Text(
                if (run.paused) "EN PAUSE" else "temps total",
                color = if (run.paused) MaterialTheme.colorScheme.error else Night.Muted,
                fontWeight = if (run.paused) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val g = if (step != null && step.mode != StepMode.FREE) RunEngine.guidance(step, run.currentPace, run.hr, maxHr) else null
            BigNumber(
                "Allure", formatPace(run.currentPace), "/km",
                when (g) {
                    RunEngine.Guidance.NONE -> "dans la cible" to Night.Good
                    RunEngine.Guidance.FASTER -> "accélère" to Color(0xFFFFB020)
                    RunEngine.Guidance.SLOWER -> "ralentis" to Color(0xFFFFB020)
                    null -> "" to Night.Muted
                },
                Modifier.weight(1f),
            )
            BigNumber(
                "Distance", "%.2f".format(run.distanceM / 1000).replace('.', ','), "km",
                "moy. ${formatPace(run.avgPace)}" to Night.Muted,
                Modifier.weight(1f),
            )
        }
        HeartRateCard(run.hr, maxHr)

        if (w != null && step != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Night.AccentContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, Night.Accent),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ÉTAPE ${run.stepIndex + 1} / ${w.steps.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Night.AccentText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(step.name, style = MaterialTheme.typography.titleLarge)
                            Text(RunEngine.targetText(step, maxHr), color = Night.Soft)
                        }
                        Text(
                            // Prolongation libre : temps écoulé au lieu d'un compte à rebours
                            if (SessionExtension.isFreeExtension(step)) "+" + formatDuration(run.stepElapsed)
                            else RunEngine.stepRemainingText(step, run.stepElapsed, run.stepDistance),
                            fontFamily = Display,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            style = NumberStyle,
                        )
                    }
                    if (!SessionExtension.isFreeExtension(step)) {
                        val progress = step.distanceM?.let { (run.stepDistance / it).toFloat() }
                            ?: (run.stepElapsed.toFloat() / step.durationSec.coerceAtLeast(1))
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            color = Night.Accent,
                            trackColor = Color(0xFF3D2A1A),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    w.steps.getOrNull(run.stepIndex + 1)?.let { n ->
                        Text(
                            "Ensuite : ${n.name} · ${RunEngine.stepLengthText(n)}, ${RunEngine.targetText(n, maxHr)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Night.Soft,
                        )
                    }
                }
            }
        }

        // Commandes : gros boutons ronds, utilisables en courant
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val canExtend = w != null && !SessionExtension.isFreeExtension(step)
            RoundButton(
                onClick = { RunTracker.extendStep() },
                size = 64,
                container = Night.Surface,
                enabled = canExtend,
                label = "Ajouter 5 minutes",
            ) { Text("+5'", fontWeight = FontWeight.SemiBold, color = if (canExtend) Night.Text else Night.Muted) }
            RoundButton(
                onClick = { RunTracker.togglePause() },
                size = 92,
                container = Night.Accent,
                label = if (run.paused) "Reprendre" else "Pause",
            ) { Icon(if (run.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(38.dp), tint = Night.Bg) }
            RoundButton(
                onClick = { confirmStop = true },
                size = 64,
                container = Color(0xFF1E1215),
                label = "Terminer",
            ) { Icon(Icons.Filled.Stop, null, tint = Color(0xFFFF6B81)) }
        }
        if (w != null) {
            TextButton(onClick = { RunTracker.skipStep() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.SkipNext, null)
                Text(" Étape suivante")
            }
        }

        if (run.samples.size > 5) {
            ChartCard("Vitesse (km/h)", run.samples.takeLast(600).map { s -> s.paceSecPerKm?.let { 3600f / it } }, CadenceColor)
            if (run.samples.any { it.hr != null }) {
                ChartCard("Cardio (bpm)", run.samples.takeLast(600).map { it.hr?.toFloat() }, HrColor)
            }
        }
        Text(
            "Tu peux éteindre l'écran : le suivi et les annonces vocales continuent.",
            style = MaterialTheme.typography.bodySmall,
            color = Night.Muted,
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

/** Grand chiffre de course avec une ligne d'état colorée. */
@Composable
private fun BigNumber(label: String, value: String, unit: String, hint: Pair<String, Color>, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Night.Surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Night.Muted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontFamily = Display, fontSize = 40.sp, fontWeight = FontWeight.Bold, style = NumberStyle)
                Text(" $unit", color = Night.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (hint.first.isNotBlank()) Text(hint.first, style = MaterialTheme.typography.labelMedium, color = hint.second)
        }
    }
}

/** Cardio en couleur de zone, avec la jauge des 5 zones. */
@Composable
fun HeartRateCard(hr: Int?, zones: HrZones) {
    val zone = hr?.let { zones.zoneOf(it) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Night.Surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("CARDIO", style = MaterialTheme.typography.labelMedium, color = Night.Muted, modifier = Modifier.weight(1f))
                Text(
                    hr?.toString() ?: "--",
                    fontFamily = Display,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = zone?.let { zoneColor(it) } ?: Night.Text,
                    style = NumberStyle,
                )
                Text(" bpm" + (zone?.let { " · Z$it" } ?: ""), color = Night.Soft, modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..5).forEach { z ->
                    val c = zoneColor(z)
                    Box(
                        Modifier.weight(1f).height(if (z == zone) 12.dp else 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (z == zone) c else c.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }
}

/** Bouton rond (commandes de séance). */
@Composable
fun RoundButton(
    onClick: () -> Unit,
    size: Int,
    container: Color,
    label: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size.dp).semantics { contentDescription = label },
        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            disabledContainerColor = container.copy(alpha = 0.5f),
        ),
    ) { content() }
}

@Composable
private fun RunSummary(rec: SessionRecord) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Night.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("COURSE ENREGISTRÉE", style = MaterialTheme.typography.labelMedium, color = Night.AccentText, fontWeight = FontWeight.SemiBold)
                Text(rec.workoutName ?: "Course libre", style = MaterialTheme.typography.headlineSmall)
            }
            val elev = com.mathieu.madtrainer.data.Elevation.stats(rec.samples)
            SummaryGrid(
                listOf(
                    "Distance" to "%.2f km".format(rec.distanceM / 1000.0).replace('.', ','),
                    "Temps" to formatDuration(rec.durationSec),
                    "Allure" to formatPace(rec.avgPaceSecPerKm),
                    "FC moy." to (rec.avgHr?.toString() ?: "--"),
                    "D+" to (elev?.let { "${it.gainM} m" } ?: "--"),
                    "kcal" to rec.kcal.toString(),
                ),
            )
        }
    }
}

/** Grille de chiffres clés, 3 par ligne. */
@Composable
fun SummaryGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(3).forEach { row ->
            Row {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = Night.Muted)
                        Text(value, fontFamily = Display, fontSize = 22.sp, fontWeight = FontWeight.Bold, style = NumberStyle, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
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
