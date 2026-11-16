package com.mathieu.madtrainer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.update.UpdateState

/** Bandeau affiché en haut de l'app quand une nouvelle version est disponible. */
@Composable
fun UpdateBanner(vm: AppViewModel) {
    val st by vm.update.collectAsStateWithLifecycle()
    val (title, progress) = when (val s = st) {
        is UpdateState.Available -> "Version ${s.info.versionName} disponible" to null
        is UpdateState.Downloading -> "Téléchargement de la ${s.info.versionName}…" to s.progress
        is UpdateState.ReadyToInstall -> "Version ${s.info.versionName} prête à installer" to null
        else -> return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "  $title",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (progress == null) {
                    TextButton(onClick = { vm.dismissUpdate() }) { Text("Plus tard") }
                    Button(onClick = { vm.downloadAndInstall() }) { Text("Installer") }
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (st is UpdateState.ReadyToInstall) {
                Text(
                    "Si Android ouvre ses réglages, autorise l'installation puis reviens appuyer sur Installer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Version installée, état de la recherche et bouton « Vérifier » (+ « Installer » si une version est prête). */
@Composable
fun AppUpdateCard(vm: AppViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    NightCard {
        Eyebrow("Mise à jour")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MAD Personal Trainer ${com.mathieu.madtrainer.update.Updater.currentVersionName}", fontWeight = FontWeight.SemiBold)
                Text(
                    when (val u = update) {
                        UpdateState.Checking -> "Recherche de mise à jour…"
                        UpdateState.UpToDate -> "À jour"
                        is UpdateState.Available -> "Version ${u.info.versionName} disponible"
                        is UpdateState.Downloading -> "Téléchargement… ${(u.progress * 100).toInt()} %"
                        is UpdateState.ReadyToInstall -> "Version ${u.info.versionName} prête à installer"
                        is UpdateState.Error -> "Erreur : ${u.message}"
                        UpdateState.Idle -> "Appuie sur Vérifier pour chercher une nouvelle version"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Night.Muted,
                )
            }
            when (update) {
                is UpdateState.Available, is UpdateState.ReadyToInstall ->
                    Button(onClick = { vm.downloadAndInstall() }) { Text("Installer") }
                is UpdateState.Downloading, UpdateState.Checking -> Unit
                else -> androidx.compose.material3.OutlinedButton(onClick = { vm.checkForUpdate() }) { Text("Vérifier") }
            }
        }
        (update as? UpdateState.Downloading)?.let { d ->
            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
