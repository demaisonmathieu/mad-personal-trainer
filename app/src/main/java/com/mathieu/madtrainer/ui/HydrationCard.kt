package com.mathieu.madtrainer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.data.Hydration
import com.mathieu.madtrainer.data.HydrationStore
import com.mathieu.madtrainer.health.HydrationReminder
import java.time.LocalDate

private val WaterColor = Color(0xFF4FC3F7)

/** Compteur d'eau du jour, rythme attendu et réglage des rappels. */
@Composable
fun HydrationCard() {
    val context = LocalContext.current
    val s by HydrationStore.state.collectAsStateWithLifecycle()
    // Retour dans l'appli : nouveau jour, verre ajouté depuis la notification…
    LifecycleResumeEffect(Unit) {
        HydrationStore.refresh()
        onPauseOrDispose { }
    }
    var settings by remember { mutableStateOf(false) }
    var canNotify by remember { mutableStateOf(HydrationReminder.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }

    val target = s.targetMl
    val expected = s.expectedNow()
    val behind = expected - s.drunkMl
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WaterDrop, null, Modifier.size(18.dp), tint = WaterColor)
                Text("  Hydratation", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${Hydration.liters(s.drunkMl)} / ${Hydration.liters(target)}", fontWeight = FontWeight.Bold)
                IconButton(onClick = { settings = !settings }) { Icon(Icons.Filled.Settings, "Réglages des rappels") }
            }
            LinearProgressIndicator(
                progress = { (s.drunkMl.toFloat() / target).coerceIn(0f, 1f) },
                color = WaterColor,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when {
                    s.drunkMl >= target -> "Objectif atteint, bravo !"
                    behind >= Hydration.REMIND_BEHIND_ML -> "En retard : tu devrais en être à ${Hydration.liters(expected)} à cette heure."
                    else -> "Dans les temps (rythme attendu : ${Hydration.liters(expected)})."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (behind >= Hydration.REMIND_BEHIND_ML && s.drunkMl < target) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { HydrationStore.add(Hydration.GLASS_ML); HydrationReminder.cancel(context) }) { Text("+${Hydration.GLASS_ML} ml") }
                FilledTonalButton(onClick = { HydrationStore.add(Hydration.BOTTLE_ML); HydrationReminder.cancel(context) }) { Text("+${Hydration.BOTTLE_ML} ml") }
                if (s.drinks.isNotEmpty()) {
                    IconButton(onClick = { HydrationStore.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Annuler le dernier ajout") }
                }
            }
            if (s.reminders && !canNotify) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notifications bloquées : pas de rappel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Autoriser") }
                }
            }
            if (settings) {
                Text(
                    "Objectif : 35 ml par kg (${"%.0f".format(s.weightKg)} kg) + 500 ml par heure de sport (${s.exerciseMin} min aujourd'hui), " +
                        "à répartir entre le réveil et une heure avant le coucher.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rappels si je suis en retard", Modifier.weight(1f))
                    Switch(checked = s.reminders, onCheckedChange = { on ->
                        HydrationStore.setReminders(on)
                        if (on && !canNotify && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                HourStepper("Réveil", s.wakeHour) { HydrationStore.setHours(it, s.sleepHour) }
                HourStepper("Coucher", s.sleepHour) { HydrationStore.setHours(s.wakeHour, it) }
                val today = LocalDate.now().toEpochDay()
                val week = (1..7).mapNotNull { s.history[today - it] }
                if (week.isNotEmpty()) {
                    Text(
                        "7 derniers jours : ${Hydration.liters(week.sum() / week.size)} en moyenne sur ${week.size} jour(s) renseigné(s).",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange(hour - 1) }) { Text("−") }
        Text("$hour h", fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onChange(hour + 1) }) { Text("+") }
    }
}
