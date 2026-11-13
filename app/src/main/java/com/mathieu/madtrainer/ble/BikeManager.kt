package com.mathieu.madtrainer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.mathieu.madtrainer.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class ConnState { DISCONNECTED, CONNECTING, DISCOVERING, CONNECTED }

data class ScanItem(val address: String, val name: String, val rssi: Int, val ftms: Boolean)

data class Capabilities(
    val hasFtms: Boolean = false,
    val machineType: String = "?",
    /** null = inconnu (caractéristique Feature non lue) */
    val canResistance: Boolean? = null,
    val canPower: Boolean? = null,
    val resistanceRange: FtmsParser.Range? = null,
    val powerRange: FtmsParser.Range? = null,
    val controlGranted: Boolean = false,
    val legacyDomyos: Boolean = false,
)

/**
 * Gestion BLE de la console : scan, connexion GATT, abonnement aux données FTMS
 * et envoi des commandes (résistance / puissance cible).
 * Toutes les opérations GATT sont sérialisées (Android n'en supporte qu'une à la fois).
 */
@SuppressLint("MissingPermission")
class BikeManager(private val context: Context, private val settingsStore: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val btManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    private val _scanResults = MutableStateFlow<List<ScanItem>>(emptyList())
    val scanResults: StateFlow<List<ScanItem>> = _scanResults
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName
    private val _metrics = MutableStateFlow(MachineData())
    val metrics: StateFlow<MachineData> = _metrics
    private val _caps = MutableStateFlow(Capabilities())
    val caps: StateFlow<Capabilities> = _caps
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var wantConnected = false
    private var targetAddress: String? = null
    private var scanStopJob: Job? = null

    private val opMutex = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<ByteArray?>? = null

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------------------------------------------------------------- utilitaires

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        _log.value = (_log.value + line).takeLast(200)
    }

    fun rawToLevel(raw: Int): Double = raw.toDouble() / settingsStore.settings.value.resistanceScale.coerceAtLeast(1)
    fun levelToRaw(level: Double): Int = (level * settingsStore.settings.value.resistanceScale).roundToInt()

    // ---------------------------------------------------------------- scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val ftms = result.scanRecord?.serviceUuids?.contains(ParcelUuid(FtmsUuids.FTMS_SERVICE)) == true
            val item = ScanItem(result.device.address, name, result.rssi, ftms)
            val list = _scanResults.value.filterNot { it.address == item.address } + item
            _scanResults.value = list.sortedWith(
                compareByDescending<ScanItem> { it.ftms || it.name.contains("domyos", true) }
                    .thenByDescending { it.rssi },
            )
        }

        override fun onScanFailed(errorCode: Int) {
            log("Échec du scan (code $errorCode)")
            _scanning.value = false
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth indisponible ou désactivé")
            return
        }
        if (_scanning.value) return
        _scanResults.value = emptyList()
        // Pas de filtre : certaines consoles Domyos n'annoncent pas le service FTMS dans la pub BLE.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        _scanning.value = true
        log("Recherche des appareils…")
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
        scanStopJob?.cancel()
    }

    // ---------------------------------------------------------------- connexion

    fun connect(address: String, name: String?) {
        stopScan()
        closeGatt()
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            log("Appareil introuvable : $address")
            return
        }
        wantConnected = true
        targetAddress = address
        _deviceName.value = name ?: device.name ?: address
        settingsStore.update { it.copy(lastAddress = address, lastName = _deviceName.value) }
        _state.value = ConnState.CONNECTING
        _metrics.value = MachineData()
        _caps.value = Capabilities()
        log("Connexion à ${_deviceName.value}…")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantConnected = false
        closeGatt()
        _state.value = ConnState.DISCONNECTED
        log("Déconnecté")
    }

    private fun closeGatt() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        controlPoint = null
        pendingOp?.complete(null)
        pendingOp = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (g != gatt) return@launch
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("Connecté, découverte des services…")
                    _state.value = ConnState.DISCOVERING
                    delay(300)
                    g.requestMtu(185)
                    delay(300)
                    if (!g.discoverServices()) log("discoverServices a échoué")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("Connexion perdue (status $status)")
                    closeGatt()
                    _state.value = ConnState.DISCONNECTED
                    _caps.value = _caps.value.copy(controlGranted = false)
                    val addr = targetAddress
                    if (wantConnected && addr != null) {
                        delay(3000)
                        if (wantConnected && _state.value == ConnState.DISCONNECTED) {
                            log("Reconnexion…")
                            connect(addr, _deviceName.value)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch { setup(g) }
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                completeOp(if (status == BluetoothGatt.GATT_SUCCESS) ch.value ?: ByteArray(0) else null)
            }
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            completeOp(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            completeOp(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            completeOp(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch { log("MTU = $mtu") }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = ch.value?.copyOf() ?: return
                scope.launch { onNotify(ch.uuid, v) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            scope.launch { onNotify(ch.uuid, v) }
        }
    }

    private fun completeOp(value: ByteArray?) {
        pendingOp?.complete(value)
    }

    /** Lance une opération GATT et attend son callback (timeout 5 s). */
    private suspend fun gattOp(start: (BluetoothGatt) -> Boolean): ByteArray? = opMutex.withLock {
        val g = gatt ?: return@withLock null
        val d = CompletableDeferred<ByteArray?>()
        pendingOp = d
        val started = runCatching { start(g) }.getOrDefault(false)
        val res = if (started) withTimeoutOrNull(5000) { d.await() } else null
        pendingOp = null
        res
    }

    private suspend fun read(ch: BluetoothGattCharacteristic): ByteArray? = gattOp { it.readCharacteristic(ch) }

    private suspend fun write(ch: BluetoothGattCharacteristic, value: ByteArray): Boolean = gattOp { g ->
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = value
                g.writeCharacteristic(ch)
            }
        }
    } != null

    private suspend fun subscribe(ch: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val notify = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (!indicate && !notify) return false
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(FtmsUuids.CCCD) ?: return true
        val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return gattOp { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    desc.value = value
                    gg.writeDescriptor(desc)
                }
            }
        } != null
    }

    private suspend fun setup(g: BluetoothGatt) {
        if (g != gatt) return
        val ftms = g.getService(FtmsUuids.FTMS_SERVICE)
        if (ftms == null) {
            val legacy = g.getService(FtmsUuids.DOMYOS_LEGACY_SERVICE) != null
            _caps.value = Capabilities(hasFtms = false, legacyDomyos = legacy)
            _state.value = ConnState.CONNECTED
            log(
                if (legacy) "Console Domyos ancienne génération (protocole propriétaire, pas de FTMS) : non pris en charge pour l'instant."
                else "Cet appareil n'expose pas le service FTMS.",
            )
            g.services.forEach { log("Service ${it.uuid}") }
            return
        }

        var caps = Capabilities(hasFtms = true)

        ftms.getCharacteristic(FtmsUuids.FEATURE)?.let { ch ->
            read(ch)?.let { FtmsParser.parseFeatures(it) }?.let { f ->
                caps = caps.copy(canResistance = f.supportsResistanceTarget, canPower = f.supportsPowerTarget)
                log("Fonctions : résistance=${f.supportsResistanceTarget}, puissance=${f.supportsPowerTarget}")
            }
        }
        ftms.getCharacteristic(FtmsUuids.RESISTANCE_RANGE)?.let { ch ->
            read(ch)?.let { FtmsParser.parseRange(it) }?.let { r ->
                caps = caps.copy(resistanceRange = r)
                log("Plage résistance (brute) : ${r.min}..${r.max} pas ${r.step}")
            }
        }
        ftms.getCharacteristic(FtmsUuids.POWER_RANGE)?.let { ch ->
            read(ch)?.let { FtmsParser.parseRange(it) }?.let { r ->
                caps = caps.copy(powerRange = r)
                log("Plage puissance : ${r.min}..${r.max} W")
            }
        }

        val crossTrainer = ftms.getCharacteristic(FtmsUuids.CROSS_TRAINER_DATA)
        val bike = ftms.getCharacteristic(FtmsUuids.INDOOR_BIKE_DATA)
        caps = caps.copy(
            machineType = when {
                crossTrainer != null -> "Vélo elliptique (Cross Trainer)"
                bike != null -> "Vélo d'intérieur"
                else -> "Machine FTMS"
            },
        )
        crossTrainer?.let { subscribe(it) }
        bike?.let { subscribe(it) }
        ftms.getCharacteristic(FtmsUuids.MACHINE_STATUS)?.let { subscribe(it) }

        g.getService(FtmsUuids.HR_SERVICE)?.getCharacteristic(FtmsUuids.HR_MEASUREMENT)?.let {
            subscribe(it)
            log("Cardio de la console détecté")
        }

        controlPoint = ftms.getCharacteristic(FtmsUuids.CONTROL_POINT)
        controlPoint?.let { subscribe(it) }
        _caps.value = caps
        _state.value = ConnState.CONNECTED
        log("Prêt : ${caps.machineType}")

        if (controlPoint != null) requestControl() else log("Pas de point de contrôle : lecture seule")
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            FtmsUuids.CROSS_TRAINER_DATA ->
                _metrics.value = _metrics.value.merge(FtmsParser.parseCrossTrainerData(value))
            FtmsUuids.INDOOR_BIKE_DATA ->
                _metrics.value = _metrics.value.merge(FtmsParser.parseIndoorBikeData(value))
            FtmsUuids.HR_MEASUREMENT ->
                FtmsParser.parseHeartRate(value)?.let { _metrics.value = _metrics.value.copy(heartRate = it) }
            FtmsUuids.MACHINE_STATUS -> {
                log("Console : ${FtmsParser.describeMachineStatus(value)}")
                if ((value.firstOrNull()?.toInt() ?: 0) and 0xFF == 0xFF) {
                    _caps.value = _caps.value.copy(controlGranted = false)
                }
            }
            FtmsUuids.CONTROL_POINT -> {
                val r = FtmsControl.parseResponse(value)
                if (r == null) {
                    log("Réponse inconnue : ${value.toHex()}")
                } else {
                    log("${FtmsControl.opName(r.requestOp)} → ${r.resultText}")
                    if (r.requestOp == FtmsControl.OP_REQUEST_CONTROL && r.success) {
                        _caps.value = _caps.value.copy(controlGranted = true)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- commandes

    private suspend fun sendControl(bytes: ByteArray): Boolean {
        val cp = controlPoint ?: run {
            log("Commande impossible : pas de point de contrôle")
            return false
        }
        log("→ ${bytes.toHex()}")
        val ok = write(cp, bytes)
        if (!ok) log("Écriture refusée par la console")
        return ok
    }

    private suspend fun ensureControl() {
        if (!_caps.value.controlGranted) {
            sendControl(FtmsControl.requestControl())
            delay(300)
        }
    }

    fun requestControl() = scope.launch { sendControl(FtmsControl.requestControl()) }

    fun startMachine() = scope.launch {
        ensureControl()
        sendControl(FtmsControl.start())
    }

    fun pauseMachine() = scope.launch {
        ensureControl()
        sendControl(FtmsControl.pause())
    }

    fun stopMachine() = scope.launch {
        ensureControl()
        sendControl(FtmsControl.stop())
    }

    fun setResistanceLevel(level: Double) = scope.launch {
        ensureControl()
        var raw = levelToRaw(level)
        _caps.value.resistanceRange?.let { r -> if (r.max > r.min) raw = raw.coerceIn(r.min, r.max) }
        log("Résistance cible : %.1f".format(level))
        sendControl(FtmsControl.setResistance(raw, settingsStore.settings.value.wideResistance))
    }

    fun setTargetPower(watts: Int) = scope.launch {
        ensureControl()
        var w = watts
        _caps.value.powerRange?.let { r -> if (r.max > r.min) w = w.coerceIn(r.min, r.max) }
        log("Puissance cible : $w W")
        sendControl(FtmsControl.setPower(w))
    }

    fun release() {
        stopScan()
        wantConnected = false
        closeGatt()
        scope.cancel()
    }
}
