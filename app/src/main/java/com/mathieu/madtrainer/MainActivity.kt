package com.mathieu.madtrainer

import android.content.Intent
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

private enum class Tab(val label: String, val icon: ImageVector) {
    DEVICE("Vélo", Icons.Filled.Bluetooth),
    SESSION("Séance", Icons.Filled.FitnessCenter),
    COACH("Coach", Icons.Filled.AutoAwesome),
    WORKOUTS("Programmes", Icons.Filled.EditNote),
    HISTORY("Historique", Icons.Filled.History),
}

@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.DEVICE) }
    val state by vm.bike.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) { UpdateBanner(vm) }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                if (t == Tab.DEVICE && state == ConnState.CONNECTED) Icons.Filled.BluetoothConnected else t.icon,
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.DEVICE -> DeviceScreen(vm)
                Tab.SESSION -> SessionScreen(vm, onGoToDevice = { tab = Tab.DEVICE })
                Tab.COACH -> CoachScreen(vm, onStartWorkout = { tab = Tab.SESSION })
                Tab.WORKOUTS -> WorkoutsScreen(vm, onStart = { tab = Tab.SESSION })
                Tab.HISTORY -> HistoryScreen(vm)
            }
        }
    }
}
