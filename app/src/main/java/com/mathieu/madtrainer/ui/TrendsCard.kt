package com.mathieu.madtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.coach.AdjustAction
import com.mathieu.madtrainer.coach.PlanAdjustment
import com.mathieu.madtrainer.data.FormStatus
import com.mathieu.madtrainer.data.TrendReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun statusColor(s: FormStatus): Color = when (s) {
    FormStatus.NOT_ENOUGH_DATA -> Color(0xFF9E9E9E)
    FormStatus.FRESH -> Color(0xFF64B5F6)
    FormStatus.OPTIMAL -> Color(0xFF81C784)
    FormStatus.LOADED -> Color(0xFFFFB74D)
    FormStatus.OVERLOAD -> Color(0xFFE57373)
}

/** Tendances sur 8 semaines : charge, état de forme, efficacité, FC de repos. */
@Composable
fun TrendsCard(t: TrendReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Insights, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("  Tendances (8 semaines)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(t.status.label, color = statusColor(t.status), fontWeight = FontWeight.Bold)
            }
            Text(t.status.advice, style = MaterialTheme.typography.bodySmall)
            if (t.status != FormStatus.NOT_ENOUGH_DATA) {
                StatRow(
                    listOf(
                        "Fatigue (7 j)" to t.atl.roundToInt().toString(),
                        "Forme de fond (42 j)" to t.ctl.roundToInt().toString(),
                        "Fraîcheur" to (if (t.tsb >= 0) "+" else "") + t.tsb.roundToInt(),
                    ),
                )
            }
            WeeklyBars(t)
            t.highlights.forEach { h ->
                val icon = when {
                    "+" in h && ("efficacité" in h || "puissance" in h) -> Icons.AutoMirrored.Filled.TrendingUp
                    h.contains("efficacité −") || h.contains("battement −") -> Icons.AutoMirrored.Filled.TrendingDown
                    else -> Icons.AutoMirrored.Filled.TrendingFlat
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  $h", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Charge = minutes × zone cardio moyenne (1 à 5). Fatigue et forme de fond sont des moyennes sur 7 et 42 jours.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Minutes par semaine (barres), semaine en cours à droite. */
@Composable
private fun WeeklyBars(t: TrendReport) {
    val color = MaterialTheme.colorScheme.primary
    val current = MaterialTheme.colorScheme.tertiary
    val max = (t.weeks.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(30)
    val df = remember { SimpleDateFormat("d/MM", Locale.FRANCE) }
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp)) {
            val n = t.weeks.size
            val slot = size.width / n
            t.weeks.forEachIndexed { i, w ->
                val h = size.height * w.minutes / max
                drawRect(
                    if (i == n - 1) current else color,
                    topLeft = Offset(i * slot + slot * 0.15f, size.height - h),
                    size = Size(slot * 0.7f, h),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            t.weeks.forEach { w ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${w.minutes}", style = MaterialTheme.typography.labelSmall)
                    Text(df.format(Date(w.weekStart)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Proposition d'ajustement du plan, appliquée sur confirmation (une fois par semaine). */
@Composable
fun PlanAdjustCard(vm: AppViewModel, adj: PlanAdjustment, adjustedThisWeek: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    val color = when (adj.action) {
        AdjustAction.REDUCE -> MaterialTheme.colorScheme.errorContainer
        AdjustAction.INCREASE -> MaterialTheme.colorScheme.primaryContainer
        AdjustAction.KEEP -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ajustement du plan : ${adj.action.label.lowercase()}", fontWeight = FontWeight.SemiBold)
            adj.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            when {
                adj.action == AdjustAction.KEEP -> Unit
                adjustedThisWeek -> Text("Plan déjà ajusté cette semaine.", style = MaterialTheme.typography.labelMedium)
                else -> {
                    Text(
                        if (adj.action == AdjustAction.REDUCE) "Séances à venir (reste de la semaine et semaine suivante) : −20 % de durée d'effort, efforts les plus intenses abaissés d'une zone."
                        else "Séances à venir (reste de la semaine et semaine suivante) : +10 % de durée d'effort.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Button(onClick = { confirm = true }) { Text("Appliquer") }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(adj.action.label) },
            text = { Text("Les séances à venir du plan seront modifiées. Tu peux toujours les retoucher une par une dans « Ma semaine ».") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.applyPlanAdjustment(adj.action)
                }) { Text("Appliquer") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}
