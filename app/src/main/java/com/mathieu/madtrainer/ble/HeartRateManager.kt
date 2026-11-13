package com.mathieu.madtrainer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.mathieu.madtrainer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Cardiofréquencemètre Bluetooth standard (service 0x180D) : ceinture, ou montre en mode
 * « diffusion de la fréquence cardiaque » (Amazfit Balance, Garmin, Polar, Coros…).
 * Connexion indépendante de celle de la console.
 */
@SuppressLint("MissingPermission")
class HeartRateManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val onBpm: (Int?) -> Unit,
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _scanResults = MutableStateFlow<List<ScanItem>>(emptyList())
    val scanResults: StateFlow<List<ScanItem>> = _scanResults
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state
    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name
    private val _bpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = _bpm

    private var gatt: BluetoothGatt? = null
    private var wantConnected = false
    private var address: String? = null
    private var scanStopJob: Job? = null
    private var staleJob: Job? = null

    /** Noms typiques de montres / capteurs cardio (beaucoup n'annoncent pas le service 0x180D). */
    private val hrNameHints = listOf(
        "amazfit", "balance", "zepp", "gtr", "gts", "t-rex", "helio", "mi band", "xiaomi", "polar", "garmin",
        "forerunner", "fenix", "venu", "coros", "suunto", "wahoo", "tickr", "hrm", "heart", "cardio", "whoop",
        "galaxy watch", "huawei", "honor", "fitbit", "decathlon", "kalenji", "magene", "coospo",
    )

    private fun isLikelyHr(name: String) = hrNameHints.any { name.lowercase().contains(it) }

    /** Résultat de recherche : `ftms` est réutilisé ici pour marquer « service cardio annoncé ». */
    private fun addResult(item: ScanItem) {
        _scanResults.value = (_scanResults.value.filterNot { it.address == item.address } + item)
            .sortedWith(compareByDescending<ScanItem> { it.ftms }.thenByDescending { isLikelyHr(it.name) }.thenByDescending { it.rssi })
    }

    /** Montres déjà appairées / connectées au téléphone (ex. via Zepp) : elles ne s'annoncent plus. */
    private fun addKnownDevices() {
        val bm = context.getSystemService(BluetoothManager::class.java) ?: return
        val connected = runCatching { bm.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList())
        val bonded = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
        (connected + bonded).distinctBy { it.address }.forEach { d ->
            val n = runCatching { d.name }.getOrNull() ?: return@forEach
            if (d.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) return@forEach
            val tag = if (connected.any { it.address == d.address }) " (connectée)" else " (appairée)"
            addResult(ScanItem(d.address, n + tag, rssi = -50, ftms = isLikelyHr(n)))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val hasHr = result.scanRecord?.serviceUuids?.contains(ParcelUuid(FtmsUuids.HR_SERVICE)) == true
            // On garde : service cardio annoncé, ou nom évocateur d'une montre / ceinture
            if (!hasHr && !isLikelyHr(name)) return
            addResult(ScanItem(result.device.address, name, result.rssi, ftms = hasHr))
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            log("Cardio : échec du scan ($errorCode)")
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        _scanResults.value = emptyList()
        addKnownDevices()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        log("Cardio : recherche (montres appairées + appareils à proximité)…")
        _scanning.value = true
        scanStopJob?.cancel()
        scanStopJob = scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!_scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _scanning.value = false
    }

    fun connect(addr: String, displayName: String?) {
        stopScan()
        close()
        val device = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
        wantConnected = true
        firstBpmLogged = false
        address = addr
        _name.value = (displayName ?: device.name ?: addr).removeSuffix(" (connectée)").removeSuffix(" (appairée)")
        settingsStore.update { it.copy(hrAddress = addr, hrName = _name.value) }
        _state.value = ConnState.CONNECTING
        log("Cardio : connexion à ${_name.value}…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantConnected = false
        close()
        _state.value = ConnState.DISCONNECTED
        setBpm(null)
    }

    /** Oublie l'appareil mémorisé. */
    fun forget() {
        disconnect()
        settingsStore.update { it.copy(hrAddress = null, hrName = null) }
    }

    private fun close() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private var firstBpmLogged = false

    private fun setBpm(v: Int?) {
        if (v != null && !firstBpmLogged) {
            firstBpmLogged = true
            log("Cardio : première mesure reçue ($v bpm)")
        }
        _bpm.value = v
        onBpm(v)
        // Si plus aucune mesure pendant 5 s (montre sortie du mode diffusion), on efface la valeur
        staleJob?.cancel()
        if (v != null) {
            staleJob = scope.launch {
                delay(5000)
                _bpm.value = null
                onBpm(null)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (g != gatt) return@launch
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.value = ConnState.DISCOVERING
                    delay(300)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("Cardio : connexion perdue")
                    close()
                    _state.value = ConnState.DISCONNECTED
                    setBpm(null)
                    val addr = address
                    if (wantConnected && addr != null) {
                        delay(3000)
                        if (wantConnected && _state.value == ConnState.DISCONNECTED) connect(addr, _name.value)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val ch = g.getService(FtmsUuids.HR_SERVICE)?.getCharacteristic(FtmsUuids.HR_MEASUREMENT)
                if (ch == null) {
                    log("Cardio : pas de service fréquence cardiaque. Active la diffusion cardio sur la montre puis reconnecte.")
                    g.services.forEach { log("Cardio : service ${it.uuid}") }
                    disconnect()
                    return@launch
                }
                g.setCharacteristicNotification(ch, true)
                val desc = ch.getDescriptor(FtmsUuids.CCCD)
                if (desc != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(desc)
                        }
                    }
                }
                _state.value = ConnState.CONNECTED
                log("Cardio : ${_name.value} connecté, en attente des mesures")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = ch.value?.copyOf() ?: return
                scope.launch { FtmsParser.parseHeartRate(v)?.let { setBpm(it) } }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            scope.launch { FtmsParser.parseHeartRate(v)?.let { setBpm(it) } }
        }
    }

    fun release() {
        stopScan()
        wantConnected = false
        close()
        scope.cancel()
    }
}
