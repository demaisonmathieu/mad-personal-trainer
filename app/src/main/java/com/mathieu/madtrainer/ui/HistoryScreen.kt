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
import com.mathieu.madtrainer.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.FRANCE)

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
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
            SectionTitle("Historique", Modifier.padding(top = 8.dp))
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
                        Text("${s.avgPower} W moy.", style = MaterialTheme.typography.bodySmall)
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
        val samples = downsample(s.samples.map { it }, 400)
        ChartCard("Puissance (W)", samples.map { it.power?.toFloat() }, PowerColor)
        ChartCard("Cadence (spm)", samples.map { it.cadence?.toFloat() }, CadenceColor)
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
