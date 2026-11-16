package com.mathieu.madtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Vma
import com.mathieu.madtrainer.data.formatPace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** VMA et VO2max estimées sur cette course, avec mise à jour du tableau d'allures. */
@Composable
fun VmaCard(rec: SessionRecord, vm: AppViewModel) {
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val est = remember(rec.id, profile.estimatedMaxHr, profile.restingHr) { vm.estimateVma(rec) }
    val saved = profile.vmaKmh
    if (est == null && saved == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("  VMA et VO2max", fontWeight = FontWeight.SemiBold)
            }
            if (est != null) {
                StatRow(
                    listOf(
                        "VMA estimée" to "${Vma.fmt(est.vmaKmh)} km/h",
                        "VO2max" to Vma.fmt(est.vo2max),
                        "Enregistrée" to (saved?.let { "${Vma.fmt(it)} km/h" } ?: "--"),
                    ),
                )
                Text(
                    "Méthode : ${est.method}." + if (!est.reliable) " Estimation indicative : une sortie avec cardio, ou un test (6 min à fond), sera plus précise." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (saved == null || abs(saved - est.vmaKmh) >= 0.1) {
                    FilledTonalButton(onClick = { vm.saveVma(est.vmaKmh, rec.startedAt) }) {
                        Text(if (saved == null) "Utiliser pour mes allures" else "Mettre à jour mes allures (${Vma.fmt(saved)} → ${Vma.fmt(est.vmaKmh)} km/h)")
                    }
                } else {
                    Text("Tableau d'allures à jour.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text("Pas d'estimation sur cette course (moins de 10 min ou de 1,5 km).", style = MaterialTheme.typography.bodySmall)
            }
            (saved ?: est?.vmaKmh)?.let { v ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                PaceTable(v, if (saved != null) profile.vmaDate else null)
            }
        }
    }
}

/** Allures d'entraînement calculées en % de VMA. */
@Composable
fun PaceTable(vmaKmh: Double, since: Long? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Mes allures (VMA ${Vma.fmt(vmaKmh)} km/h" +
                (since?.let { ", depuis le " + SimpleDateFormat("d MMM", Locale.FRANCE).format(Date(it)) } ?: "") + ")",
            style = MaterialTheme.typography.labelLarge,
        )
        Vma.paceTable(vmaKmh).forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${b.pctLow}–${b.pctHigh} % · ${b.use}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${formatPace(b.slowSecKm)} – ${formatPace(b.fastSecKm)}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
