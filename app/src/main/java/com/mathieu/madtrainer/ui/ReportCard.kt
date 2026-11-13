package com.mathieu.madtrainer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.ReportAiState
import com.mathieu.madtrainer.data.SessionRecord

/** Rapport de progression d'une séance : points marquants, zones, consignes, et analyse par l'IA. */
@Composable
fun SessionReportCard(record: SessionRecord, vm: AppViewModel, modifier: Modifier = Modifier) {
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val ai by vm.reportAi.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Version à jour (l'analyse IA est enregistrée après coup)
    val rec = sessions.firstOrNull { it.id == record.id } ?: record
    val report = remember(rec.id, rec.samples.size, sessions.size) { vm.progressReport(rec) }

    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rapport de progression", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            report.highlights.forEach { Text("• $it") }

            if (report.zoneMinutes.any { it > 0 }) {
                SectionTitle("Temps par zone cardio")
                val total = report.zoneMinutes.sum().coerceAtLeast(1)
                report.zoneMinutes.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Z${i + 1}", modifier = Modifier.padding(end = 8.dp), fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { m.toFloat() / total }, modifier = Modifier.weight(1f))
                        Text("  $m min", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (report.steps.any { it.inTargetPct != null || it.achieved != null }) {
                SectionTitle("Consignes")
                report.steps.forEach { s ->
                    Column {
                        Text("${s.index + 1}. ${s.name}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Visé : ${s.target}" +
                                (s.achieved?.let { " · réalisé : $it" } ?: "") +
                                (s.inTargetPct?.let { " · $it % dans la cible" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if ((s.inTargetPct ?: 100) < 50) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Analyse du coach IA", fontWeight = FontWeight.SemiBold)
            }
            val working = ai is ReportAiState.Working && (ai as ReportAiState.Working).sessionId == rec.id
            val aiReport = rec.aiReport
            if (aiReport != null) {
                SelectionContainer { Text(aiReport) }
            } else {
                Text(
                    "L'IA compare cette séance à ton historique et à ton plan, puis te conseille pour la suite.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            (ai as? ReportAiState.Error)?.takeIf { it.sessionId == rec.id }?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("  Analyse en cours…")
                }
            } else {
                if (settings.anthropicKey.isNotBlank()) {
                    Button(onClick = { vm.generateReport(rec) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (aiReport == null) "Analyser ma séance" else "Relancer l'analyse")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        copyToClipboard(context, vm.buildReportPrompt(rec))
                        Toast.makeText(context, "Prompt copié : colle-le dans Claude", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                        Text(" Prompt")
                    }
                    TextButton(onClick = { sharePrompt(context, vm.buildReportPrompt(rec)) }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    }
                    TextButton(onClick = {
                        val txt = readClipboard(context)
                        if (txt.isNullOrBlank()) {
                            Toast.makeText(context, "Presse-papiers vide", Toast.LENGTH_SHORT).show()
                        } else {
                            vm.saveReport(rec, txt)
                        }
                    }) {
                        Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                        Text(" Coller la réponse")
                    }
                }
            }
        }
    }
}
