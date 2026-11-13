package com.mathieu.madtrainer

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.unit.dp
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.run.RunTracker
import com.mathieu.madtrainer.ui.RunScreen
import com.mathieu.madtrainer.ui.WeekScreen
import androidx.compose.material.icons.filled.CalendarMonth
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mathieu.madtrainer.ble.ConnState
import com.mathieu.madtrainer.ui.CoachScreen
import com.mathieu.madtrainer.ui.DeviceScreen
import com.mathieu.madtrainer.ui.MadTheme
import com.mathieu.madtrainer.ui.HistoryScreen
import com.mathieu.madtrainer.ui.SessionScreen
import com.mathieu.madtrainer.ui.UpdateBanner
import com.mathieu.madtrainer.ui.WorkoutsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { vm.handleStravaRedirect(it) }
        enableEdgeToEdge()
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

private enum class Tab(val icon: ImageVector) {
    WEEK(Icons.Filled.CalendarMonth),
    DEVICE(Icons.Filled.Bluetooth),
    SESSION(Icons.Filled.FitnessCenter),
    COACH(Icons.Filled.AutoAwesome),
    WORKOUTS(Icons.Filled.EditNote);

    fun label(sport: Sport) = when (this) {
        WEEK -> "Semaine"
        DEVICE -> if (sport == Sport.RUNNING) "Capteurs" else "Vélo"
        SESSION -> if (sport == Sport.RUNNING) "Course" else "Séance"
        COACH -> "Coach"
        WORKOUTS -> "Programmes"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.WEEK) }
    val state by vm.bike.state.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val run by RunTracker.state.collectAsStateWithLifecycle()
    val sport = settings.sport
    var showHistory by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                UpdateBanner(vm)
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        Sport.entries.forEachIndexed { i, sp ->
                            SegmentedButton(
                                selected = sport == sp && !showHistory,
                                // On ne change pas de mode pendant une course ou une séance en cours
                                onClick = {
                                    showHistory = false
                                    if (!run.running && !vm.session.value.running) vm.setSport(sp)
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, Sport.entries.size),
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
                    // Historique commun aux deux sports : bouton dédié, hors barre du bas
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(
                            Icons.Filled.History,
                            "Historique",
                            tint = if (showHistory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && !showHistory,
                        onClick = {
                            tab = t
                            showHistory = false
                        },
                        icon = {
                            Icon(
                                when {
                                    t == Tab.DEVICE && sport == Sport.ELLIPTICAL && state == ConnState.CONNECTED -> Icons.Filled.BluetoothConnected
                                    t == Tab.DEVICE && sport == Sport.RUNNING -> Icons.Filled.Watch
                                    t == Tab.SESSION && sport == Sport.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
                                    else -> t.icon
                                },
                                contentDescription = t.label(sport),
                            )
                        },
                        label = { Text(t.label(sport), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showHistory) {
                BackHandler { showHistory = false }
                HistoryScreen(vm)
            } else when (tab) {
                Tab.WEEK -> WeekScreen(
                    vm,
                    onOpenSession = { tab = Tab.SESSION },
                    onOpenStrength = { tab = Tab.COACH },
                    onOpenCoach = { tab = Tab.COACH },
                )
                Tab.DEVICE -> DeviceScreen(vm, runningMode = sport == Sport.RUNNING)
                Tab.SESSION ->
                    if (sport == Sport.RUNNING) RunScreen(vm, onGoToSensors = { tab = Tab.DEVICE })
                    else SessionScreen(vm, onGoToDevice = { tab = Tab.DEVICE })
                Tab.COACH -> CoachScreen(vm, onStartWorkout = { tab = Tab.SESSION })
                Tab.WORKOUTS -> WorkoutsScreen(vm, onStart = { tab = Tab.SESSION })
            }
        }
    }
}
