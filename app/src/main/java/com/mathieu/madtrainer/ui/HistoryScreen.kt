package com.mathieu.madtrainer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.data.formatPace
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.FRANCE)

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val allSessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val sessions = allSessions.filter { it.sport == settings.sport }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = sessions.firstOrNull { it.id == selectedId }

    if (selected != null) {
        SessionDetail(selected, onBack = { selectedId = null }, onDelete = {
            vm.deleteSession(selected.id)
            selectedId = null
        })
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
          Column {
            SectionTitle("Historique — ${settings.sport.label}", Modifier.padding(top = 8.dp))
            if (sessions.isNotEmpty()) {
                val totalSec = sessions.sumOf { it.durationSec }
                val totalKm = sessions.sumOf { it.distanceM } / 1000.0
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        StatRow(
                            listOf(
                                "Séances" to sessions.size.toString(),
                                "Temps total" to formatDuration(totalSec),
                                "Distance" to "%.1f km".format(totalKm),
                                "kcal" to sessions.sumOf { it.kcal }.toString(),
                            ),
                        )
                    }
                }
            } else {
                Text("Aucune séance enregistrée pour l'instant.", style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
        items(sessions, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().clickable { selectedId = s.id }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dateFmt.format(Date(s.startedAt)), fontWeight = FontWeight.SemiBold)
                        Text(s.workoutName ?: "Séance libre", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatDuration(s.durationSec), fontWeight = FontWeight.Bold)
                        Text(
                            if (s.sport == Sport.RUNNING) "%.2f km · %s/km".format(s.distanceM / 1000.0, formatPace(s.avgPaceSecPerKm))
                            else "${s.avgPower} W moy.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetail(s: SessionRecord, onBack: () -> Unit, onDelete: () -> Unit) {
    BackHandler { onBack() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Retour") }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.workoutName ?: "Séance libre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(dateFmt.format(Date(s.startedAt)), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Supprimer") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatRow(
                    listOf(
                        "Durée" to formatDuration(s.durationSec),
                        "Distance" to "%.2f km".format(s.distanceM / 1000.0),
                        "kcal" to s.kcal.toString(),
                    ),
                )
                if (s.sport == Sport.RUNNING) {
                    StatRow(
                        listOf(
                            "Allure moy." to "${formatPace(s.avgPaceSecPerKm)}/km",
                            "FC moy." to (s.avgHr?.toString() ?: "--"),
                            "FC max" to (s.maxHr?.toString() ?: "--"),
                        ),
                    )
                } else {
                    StatRow(
                        listOf(
                            "Puiss. moy." to "${s.avgPower} W",
                            "Puiss. max" to "${s.maxPower} W",
                            "Cadence" to "%.0f".format(s.avgCadence),
                            "Cardio" to (s.avgHr?.toString() ?: "--"),
                        ),
                    )
                }
            }
        }
        val samples = downsample(s.samples.map { it }, 400)
        if (s.sport == Sport.RUNNING) {
            ChartCard("Vitesse (km/h)", samples.map { it.paceSecPerKm?.let { p -> 3600f / p } }, CadenceColor)
            if (samples.any { it.lat != null }) RouteCard(samples)
            // Temps au kilomètre
            val splits = kmSplits(s)
            if (splits.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Temps au kilomètre", style = MaterialTheme.typography.labelLarge)
                        splits.forEachIndexed { i, sec -> Text("Km ${i + 1} : ${formatPace(sec)}") }
                    }
                }
            }
        } else {
            ChartCard("Puissance (W)", samples.map { it.power?.toFloat() }, PowerColor)
            ChartCard("Cadence (spm)", samples.map { it.cadence?.toFloat() }, CadenceColor)
        }
        if (samples.any { it.resistance != null }) {
            ChartCard("Résistance", samples.map { it.resistance?.toFloat() }, ResistanceColor)
        }
        if (samples.any { (it.hr ?: 0) > 0 }) {
            ChartCard("Cardio (bpm)", samples.map { it.hr?.toFloat() }, HrColor)
        }
    }
}

private fun <T> downsample(list: List<T>, max: Int): List<T> {
    if (list.size <= max) return list
    val step = list.size.toDouble() / max
    return List(max) { i -> list[(i * step).toInt()] }
}

/** Temps (s) de chaque kilomètre complet, à partir des échantillons (1/s). */
private fun kmSplits(s: SessionRecord): List<Int> {
    val out = mutableListOf<Int>()
    var nextKm = 1000
    var lastT = 0
    for (p in s.samples) {
        val d = p.distanceM ?: continue
        if (d >= nextKm) {
            out += p.t - lastT
            lastT = p.t
            nextKm += 1000
        }
    }
    return out
}

/** Tracé du parcours GPS (projection simple, sans fond de carte). */
@Composable
private fun RouteCard(samples: List<Sample>) {
    val pts = samples.mapNotNull { s -> if (s.lat != null && s.lon != null) s.lat to s.lon else null }
    if (pts.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Parcours", style = MaterialTheme.typography.labelLarge)
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f).padding(top = 8.dp)) {
                val minLat = pts.minOf { it.first }
                val maxLat = pts.maxOf { it.first }
                val minLon = pts.minOf { it.second }
                val maxLon = pts.maxOf { it.second }
                val cosLat = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2))
                val w = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-6)
                val h = (maxLat - minLat).coerceAtLeast(1e-6)
                val scale = minOf(size.width / w, size.height / h).toFloat() * 0.92f
                val ox = (size.width - (w * scale).toFloat()) / 2
                val oy = (size.height - (h * scale).toFloat()) / 2
                fun map(p: Pair<Double, Double>) = Offset(
                    ox + ((p.second - minLon) * cosLat * scale).toFloat(),
                    oy + ((maxLat - p.first) * scale).toFloat(),
                )
                val path = Path()
                pts.forEachIndexed { i, p ->
                    val o = map(p)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, color, style = Stroke(width = 6f))
                drawCircle(androidx.compose.ui.graphics.Color(0xFF2E9E4F), 10f, map(pts.first()))
                drawCircle(androidx.compose.ui.graphics.Color(0xFFE5395B), 10f, map(pts.last()))
            }
        }
    }
}
