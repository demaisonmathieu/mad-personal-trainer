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
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.health.sourceLabel
import com.mathieu.madtrainer.data.Vma
import com.mathieu.madtrainer.data.formatPace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** VMA et VO2max estimées sur cette course, avec mise à jour du tableau d'allures. */
@Composable
fun VmaCard(rec: SessionRecord, vm: AppViewModel) {
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val sessions by vm.repo.sessions.collectAsStateWithLifecycle()
    val single = remember(rec.id, profile.estimatedMaxHr, profile.restingHr) { vm.estimateVma(rec) }
    val est = remember(rec.id, sessions.size, profile.estimatedMaxHr, profile.restingHr) { vm.recentVma(rec) }
    val saved = profile.vmaKmh
    val body by vm.repo.body.collectAsStateWithLifecycle()
    // La montre calcule sa VO2max après la course : on accepte une mesure jusqu'au lendemain
    val watch = remember(body.vo2max, rec.startedAt) { BodyStats.watchVo2max(body.vo2max, rec.startedAt + 24L * 3600 * 1000) }
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
                        "Cette course" to (single?.let { "${Vma.fmt(it.vmaKmh)} km/h" } ?: "--"),
                        (if (est.runs > 1) "VMA (${est.runs} courses)" else "VMA estimée") to "${Vma.fmt(est.vmaKmh)} km/h",
                        "VO2max" to Vma.fmt(est.vo2max),
                    ),
                )
                Text(
                    "Méthode : ${est.method}." +
                        (if (est.runs > 1) single?.let { " Sur cette course seule : ${it.method}." } ?: "" else "") +
                        if (!est.reliable) " Estimation indicative : quelques sorties avec cardio, ou un test (6 min à fond), la préciseront." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (watch != null) {
                    val gap = ((est.vo2max / watch.value - 1) * 100).roundToInt()
                    val df = SimpleDateFormat("d MMM", Locale.FRANCE)
                    Text(
                        "Montre (${sourceLabel(watch.source)}, ${df.format(Date(watch.time))}) : VO2max ${Vma.fmt(watch.value)}, " +
                            "soit une VMA d'environ ${Vma.fmt(watch.value / 3.5)} km/h. " +
                            when {
                                abs(gap) <= 5 -> "L'app est en accord avec la montre."
                                else -> "L'app est ${if (gap > 0) "au-dessus" else "en dessous"} de ${abs(gap)} %. " +
                                    "Vérifie que ta FC max (${profile.estimatedMaxHr} bpm${if (profile.maxHr == null) ", estimée d'après ton âge" else ""}) " +
                                    "est la même que dans ${sourceLabel(watch.source)} : c'est la première source d'écart."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
