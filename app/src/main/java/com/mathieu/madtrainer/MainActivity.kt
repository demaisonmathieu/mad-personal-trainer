package com.mathieu.madtrainer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mathieu.madtrainer.ble.ConnState
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.run.RunTracker
import com.mathieu.madtrainer.ui.BodyScreen
import com.mathieu.madtrainer.ui.CoachScreen
import com.mathieu.madtrainer.ui.DeviceScreen
import com.mathieu.madtrainer.ui.HistoryScreen
import com.mathieu.madtrainer.ui.MadTheme
import com.mathieu.madtrainer.ui.Night
import com.mathieu.madtrainer.ui.RunScreen
import com.mathieu.madtrainer.ui.SessionScreen
import com.mathieu.madtrainer.ui.TodayScreen
import com.mathieu.madtrainer.ui.UpdateBanner
import com.mathieu.madtrainer.ui.WebScreen
import com.mathieu.madtrainer.ui.WeekScreen
import com.mathieu.madtrainer.ui.WorkoutsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { vm.handleStravaRedirect(it) }
        // Thème toujours sombre : icônes claires dans les barres système, quel que soit le réglage du téléphone
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MadTheme { AppRoot(vm) }
        }
    }

    // Retour de l'autorisation Strava (madtrainer://localhost/strava?code=…)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { vm.handleStravaRedirect(it) }
    }
}

/** Les 4 onglets : accueil, entraînement en cours, préparation, suivi personnel. */
private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Aujourd'hui", Icons.Filled.Home),
    TRAIN("Entraîner", Icons.Filled.PlayCircle),
    PLAN("Plan", Icons.Filled.CalendarMonth),
    ME("Moi", Icons.Filled.Person),
}

private enum class PlanSection(val label: String) { WEEK("Semaine"), WORKOUTS("Programmes"), COACH("Coach") }

private enum class MeSection(val label: String) { HISTORY("Historique"), BODY("Corps"), SCREEN("Écran") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var planSection by rememberSaveable { mutableStateOf(PlanSection.WEEK) }
    var meSection by rememberSaveable { mutableStateOf(MeSection.HISTORY) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    val state by vm.bike.state.collectAsStateWithLifecycle()
    val hrState by vm.hr.state.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val run by RunTracker.state.collectAsStateWithLifecycle()
    val sport = settings.sport

    fun openSession() {
        tab = Tab.TRAIN
        showDevices = false
    }

    fun openPlan(section: PlanSection) {
        tab = Tab.PLAN
        planSection = section
    }

    Scaffold(
        containerColor = Night.Bg,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                UpdateBanner(vm)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        Sport.entries.forEachIndexed { i, sp ->
                            SegmentedButton(
                                selected = sport == sp,
                                // On ne change pas de mode pendant une course ou une séance en cours
                                onClick = { if (!run.running && !vm.session.value.running) vm.setSport(sp) },
                                shape = SegmentedButtonDefaults.itemShape(i, Sport.entries.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = Night.AccentContainer,
                                    activeContentColor = Night.AccentText,
                                    activeBorderColor = Night.Accent,
                                    inactiveContainerColor = Night.Bg,
                                    inactiveContentColor = Night.Muted,
                                    inactiveBorderColor = Night.Line,
                                ),
                                icon = {},
                            ) {
                                // Icône et texte côte à côte (sinon ils se superposent)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (sp == Sport.RUNNING) Icons.AutoMirrored.Filled.DirectionsRun else Icons.Filled.FitnessCenter,
                                        null,
                                        Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(sp.label, maxLines = 1)
                                }
                            }
                        }
                    }
                    // Coach vocal : annonces, préavis 30 s, alertes cardio
                    Box {
                        var voiceMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { voiceMenu = true }) {
                            Icon(
                                if (settings.voice) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                "Coach vocal",
                                tint = if (settings.voice) Night.Accent else Night.Muted,
                            )
                        }
                        DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Coach vocal (étapes, préavis 30 s)") },
                                trailingIcon = { Checkbox(checked = settings.voice, onCheckedChange = null) },
                                onClick = { vm.setVoice(!settings.voice) },
                            )
                            DropdownMenuItem(
                                text = { Text("Alerte cardio trop élevé") },
                                trailingIcon = { Checkbox(checked = settings.hrAlerts, onCheckedChange = null) },
                                onClick = { vm.setHrAlerts(!settings.hrAlerts) },
                            )
                            DropdownMenuItem(
                                text = { Text("Continuer en libre après la fin du programme") },
                                trailingIcon = { Checkbox(checked = settings.continueAfterEnd, onCheckedChange = null) },
                                onClick = { vm.settingsStore.update { it.copy(continueAfterEnd = !settings.continueAfterEnd) } },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Night.Surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Night.Accent,
                            selectedTextColor = Night.AccentText,
                            indicatorColor = Night.AccentContainer,
                            unselectedIconColor = Night.Muted,
                            unselectedTextColor = Night.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.TODAY -> TodayScreen(
                    vm,
                    onStartSession = { openSession() },
                    onOpenStrength = { openPlan(PlanSection.COACH) },
                    onOpenPlan = { openPlan(PlanSection.COACH) },
                )

                Tab.TRAIN -> if (showDevices) {
                    BackHandler { showDevices = false }
                    Column {
                        OutlinedButton(onClick = { showDevices = false }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("← Retour à la séance")
                        }
                        DeviceScreen(vm, runningMode = sport == Sport.RUNNING)
                    }
                } else {
                    Column {
                        // Capteurs : bandeau d'accès rapide au lieu d'un onglet dédié
                        val connected = if (sport == Sport.RUNNING) hrState == ConnState.CONNECTED else state == ConnState.CONNECTED
                        OutlinedButton(
                            onClick = { showDevices = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                when {
                                    sport == Sport.RUNNING -> Icons.Filled.Watch
                                    connected -> Icons.Filled.BluetoothConnected
                                    else -> Icons.Filled.Bluetooth
                                },
                                null,
                                Modifier.size(18.dp),
                                tint = if (connected) Night.Good else Night.Muted,
                            )
                            Text(
                                "  " + when {
                                    sport == Sport.RUNNING && connected -> "Cardio connecté"
                                    sport == Sport.RUNNING -> "Capteurs : montre / cardio"
                                    connected -> "Vélo connecté"
                                    else -> "Connecter le vélo"
                                },
                                color = if (connected) Night.Good else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            if (sport == Sport.RUNNING) RunScreen(vm, onGoToSensors = { showDevices = true })
                            else SessionScreen(vm, onGoToDevice = { showDevices = true })
                        }
                    }
                }

                Tab.PLAN -> Column {
                    SectionTabs(PlanSection.entries, planSection, { it.label }) { planSection = it }
                    Box(Modifier.weight(1f)) {
                        when (planSection) {
                            PlanSection.WEEK -> WeekScreen(
                                vm,
                                onOpenSession = { openSession() },
                                onOpenStrength = { planSection = PlanSection.COACH },
                                onOpenCoach = { planSection = PlanSection.COACH },
                            )
                            PlanSection.WORKOUTS -> WorkoutsScreen(vm, onStart = { openSession() })
                            PlanSection.COACH -> CoachScreen(vm, onStartWorkout = { openSession() })
                        }
                    }
                }

                Tab.ME -> Column {
                    SectionTabs(MeSection.entries, meSection, { it.label }) { meSection = it }
                    Box(Modifier.weight(1f)) {
                        when (meSection) {
                            MeSection.HISTORY -> HistoryScreen(vm)
                            MeSection.BODY -> BodyScreen(vm)
                            MeSection.SCREEN -> WebScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

/** Sous-sections d'un onglet (Plan, Moi). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SectionTabs(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        items.forEachIndexed { i, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(i, items.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Night.Surface2,
                    activeContentColor = Night.Text,
                    activeBorderColor = Night.Line,
                    inactiveContainerColor = Night.Bg,
                    inactiveContentColor = Night.Muted,
                    inactiveBorderColor = Night.Line,
                ),
                icon = {},
            ) { Text(label(item), maxLines = 1) }
        }
    }
}
