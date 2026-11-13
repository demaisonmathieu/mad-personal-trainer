package com.mathieu.madtrainer.ui

import android.Manifest
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.LogExporter
import com.mathieu.madtrainer.ble.ConnState

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: AppViewModel) {
    val bike = vm.bike
    val state by bike.state.collectAsStateWithLifecycle()
    val scanning by bike.scanning.collectAsStateWithLifecycle()
    val results by bike.scanResults.collectAsStateWithLifecycle()
    val name by bike.deviceName.collectAsStateWithLifecycle()
    val caps by bike.caps.collectAsStateWithLifecycle()
    val log by bike.log.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            permError = null
            pendingAction?.invoke()
        } else {
            permError = "Les autorisations Bluetooth sont nécessaires pour trouver le vélo."
        }
        pendingAction = null
    }

    fun withPermissions(action: () -> Unit) {
        if (!bike.isBluetoothEnabled) {
            permError = "Active le Bluetooth du téléphone."
            return
        }
        pendingAction = action
        launcher.launch(blePermissions())
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state == ConnState.CONNECTED) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                            contentDescription = null,
                            tint = if (state == ConnState.CONNECTED) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (state) {
                                    ConnState.DISCONNECTED -> "Non connecté"
                                    ConnState.CONNECTING -> "Connexion…"
                                    ConnState.DISCOVERING -> "Découverte des services…"
                                    ConnState.CONNECTED -> name ?: "Connecté"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state == ConnState.CONNECTED) {
                                Text(
                                    when {
                                        caps.hasFtms -> caps.machineType +
                                            if (caps.controlGranted) " · contrôle actif" else " · lecture seule"
                                        caps.legacyDomyos -> "Domyos (protocole propriétaire) · contrôle actif"
                                        else -> "Pas de service FTMS"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (state == ConnState.CONNECTING || state == ConnState.DISCOVERING) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state == ConnState.DISCONNECTED) {
                            Button(onClick = { withPermissions { bike.startScan() } }, enabled = !scanning) {
                                Icon(Icons.Filled.Search, null)
                                Text(if (scanning) "  Recherche…" else "  Rechercher")
                            }
                            val last = settings.lastAddress
                            if (last != null) {
                                OutlinedButton(onClick = { withPermissions { bike.connect(last, settings.lastName) } }) {
                                    Text(settings.lastName ?: "Dernier appareil", maxLines = 1)
                                }
                            }
                        } else {
                            OutlinedButton(onClick = { bike.disconnect() }) { Text("Déconnecter") }
                            if (state == ConnState.CONNECTED && caps.hasFtms && !caps.controlGranted) {
                                Button(onClick = { bike.requestControl() }) { Text("Prendre le contrôle") }
                            }
                        }
                    }
                    permError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }

        if (state == ConnState.DISCONNECTED && results.isNotEmpty()) {
            item { SectionTitle("Appareils trouvés") }
            items(results, key = { it.address }) { r ->
                Card(
                    Modifier.fillMaxWidth().clickable { withPermissions { bike.connect(r.address, r.name) } },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold)
                            Text(r.address, style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.ftms) {
                            Text(
                                "FTMS  ",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Text("${r.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Column {
            SectionTitle("Réglages console")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Échelle de résistance", fontWeight = FontWeight.SemiBold)
                    Text(
                        "La norme FTMS code la résistance ×10. Si le niveau affiché est 10× trop grand " +
                            "ou trop petit par rapport à la console, change ce réglage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        listOf(1, 10).forEach { sc ->
                            FilterChip(
                                selected = settings.resistanceScale == sc,
                                onClick = { vm.settingsStore.update { it.copy(resistanceScale = sc) } },
                                label = { Text("×$sc") },
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Résistance sur 2 octets", fontWeight = FontWeight.SemiBold)
                            Text(
                                "À activer si la console répond « paramètre invalide » aux changements de résistance.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = settings.wideResistance,
                            onCheckedChange = { v -> vm.settingsStore.update { it.copy(wideResistance = v) } },
                        )
                    }
                    caps.resistanceRange?.let {
                        Text(
                            "Plage annoncée : %s à %s".format(
                                fmtNum(bike.rawToLevel(it.min)), fmtNum(bike.rawToLevel(it.max)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            }
        }

        item {
            Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Journal Bluetooth", Modifier.weight(1f))
                Button(onClick = { LogExporter.share(context, bike.buildReport()) }) {
                    Icon(Icons.Filled.Share, null)
                    Text("  Envoyer")
                }
            }
            Text(
                "Choisis Drive (compte Scalizer) : Claude pourra lire le journal directement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp)) {
                        if (log.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
                        log.takeLast(60).reversed().forEach {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            }
        }
    }
}
