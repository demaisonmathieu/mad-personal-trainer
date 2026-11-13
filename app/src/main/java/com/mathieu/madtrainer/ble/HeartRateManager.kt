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
    /** Connecté mais aucune mesure reçue au bout de quelques secondes. */
    private val _noData = MutableStateFlow(false)
    val noData: StateFlow<Boolean> = _noData
    private var watchdog: Job? = null
    private var subscribeAttempts = 0
    private var framesLogged = 0

    private var gatt: BluetoothGatt? = null
    private var wantConnected = false
    private var address: String? = null
    private var scanStopJob: Job? = null
    private var staleJob: Job? = null

    /** Appareils vus pendant les recherches : on se connecte avec l'objet du scan (adresse + type d'adresse). */
    private val seenDevices = mutableMapOf<String, BluetoothDevice>()
    /** Recherche en cours de la montre mémorisée (les montres changent d'adresse Bluetooth régulièrement). */
    private var searchName: String? = null
    private var searchJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var reconnectAttempts = 0

    /** Renvoie true quand la connexion du vélo est en cours : on ne la perturbe pas. */
    var isBikeBusy: () -> Boolean = { false }
    private val hrSeenLogged = mutableSetOf<String>()

    private fun cleanName(n: String) = n.removeSuffix(" (connectée)").removeSuffix(" (appairée)").removeSuffix(" ❤ FC").trim()

    /** « Amazfit Balance-5B92 » → « amazfit balance » : en diffusion, le suffixe peut changer ou disparaître. */
    private fun baseName(n: String) = cleanName(n).substringBefore('-').trim().lowercase()

    private fun matchesTarget(name: String, hasHr: Boolean, target: String): Boolean =
        cleanName(name).equals(target, true) || (baseName(name).isNotEmpty() && baseName(name) == baseName(target) && hasHr) ||
            (baseName(name) == baseName(target))

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
            seenDevices[d.address] = d
            val tag = if (connected.any { it.address == d.address }) " (connectée)" else " (appairée)"
            addResult(ScanItem(d.address, n + tag, rssi = -50, ftms = isLikelyHr(n)))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val hasHr = result.scanRecord?.serviceUuids?.contains(ParcelUuid(FtmsUuids.HR_SERVICE)) == true
            seenDevices[result.device.address] = result.device
            // Reconnexion automatique : on retrouve la montre par son nom, quelle que soit son adresse du moment
            val target = searchName
            if (target != null && matchesTarget(name, hasHr, target)) {
                searchName = null
                searchJob?.cancel()
                stopScan()
                log("Cardio : montre retrouvée (${result.device.address})")
                connectDevice(result.device, cleanName(name))
                return
            }
            // On garde : service cardio annoncé, ou nom évocateur d'une montre / ceinture
            if (!hasHr && !isLikelyHr(name)) return
            addResult(ScanItem(result.device.address, if (hasHr) "$name ❤ FC" else name, result.rssi, ftms = hasHr))
            if (hasHr && !hrSeenLogged.contains(result.device.address)) {
                hrSeenLogged += result.device.address
                log("Cardio : « $name » diffuse la fréquence cardiaque (${result.device.address})")
            }
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

    /** Connexion choisie dans la liste (appareil vu pendant la recherche). */
    fun connect(addr: String, displayName: String?) {
        stopScan()
        val device = seenDevices[addr] ?: runCatching { adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
        reconnectAttempts = 0
        connectDevice(device, cleanName(displayName ?: runCatching { device.name }.getOrNull() ?: addr))
    }

    /** Reconnexion à la montre mémorisée : on la recherche par son nom (son adresse a pu changer). */
    fun reconnectSaved() {
        val s = settingsStore.settings.value
        val n = s.hrName ?: return
        wantConnected = true
        reconnectAttempts = 0
        searchAndConnect(cleanName(n))
    }

    private fun searchAndConnect(name: String) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        close()
        _name.value = name
        _state.value = ConnState.CONNECTING
        searchJob?.cancel()
        searchJob = scope.launch {
            // On laisse d'abord le vélo terminer sa connexion
            var waited = 0
            while (isBikeBusy() && waited < 20_000) {
                delay(500)
                waited += 500
            }
            // Montre déjà connectée au téléphone (Zepp) : elle n'est pas visible au scan
            addKnownDevices()
            seenDevices.values.firstOrNull { d ->
                runCatching { d.name }.getOrNull()?.let { cleanName(it).equals(name, true) } == true &&
                    runCatching { context.getSystemService(BluetoothManager::class.java)?.getConnectedDevices(BluetoothProfile.GATT) }
                        .getOrNull().orEmpty().any { it.address == d.address }
            }?.let { d ->
                log("Cardio : ${name} déjà reliée au téléphone, connexion directe")
                connectDevice(d, name)
                return@launch
            }
            log("Cardio : recherche de « $name »… (diffusion FC active sur la montre ?)")
            searchName = name
            if (!_scanning.value) {
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(null, settings, scanCallback)
                _scanning.value = true
            }
            delay(15_000)
            if (searchName != null) {
                searchName = null
                stopScan()
                log(
                    "Cardio : « $name » introuvable. Aucune montre ne diffuse sa FC : lance une séance d'entraînement " +
                        "sur la montre avec la diffusion FC activée, puis Rechercher.",
                )
                onConnectFailed()
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice, displayName: String) {
        close()
        wantConnected = true
        firstBpmLogged = false
        framesLogged = 0
        address = device.address
        _name.value = displayName
        settingsStore.update { it.copy(hrAddress = device.address, hrName = displayName) }
        _state.value = ConnState.CONNECTING
        log("Cardio : connexion à $displayName…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        // Android met ~30 s à abandonner une connexion impossible et sature la radio (le vélo décroche) :
        // on coupe nous-mêmes au bout de 12 s.
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(12_000)
            if (_state.value == ConnState.CONNECTING) {
                log("Cardio : la montre ne répond pas")
                close()
                onConnectFailed()
            }
        }
    }

    /** Nouvel essai espacé (5 s, 15 s, 30 s), puis abandon pour ne pas perturber le vélo. */
    private fun onConnectFailed() {
        _state.value = ConnState.DISCONNECTED
        if (!wantConnected) return
        reconnectAttempts++
        if (reconnectAttempts > 3) {
            wantConnected = false
            log("Cardio : abandon. Active la diffusion FC sur la montre puis appuie sur Reconnecter.")
            return
        }
        val name = _name.value ?: settingsStore.settings.value.hrName ?: return
        val wait = when (reconnectAttempts) { 1 -> 5_000L; 2 -> 15_000L; else -> 30_000L }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(wait)
            if (wantConnected) searchAndConnect(cleanName(name))
        }
    }

    fun disconnect() {
        wantConnected = false
        searchName = null
        searchJob?.cancel()
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
        connectTimeoutJob?.cancel()
        watchdog?.cancel()
        _noData.value = false
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
                    connectTimeoutJob?.cancel()
                    reconnectAttempts = 0
                    _state.value = ConnState.DISCOVERING
                    delay(300)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("Cardio : connexion perdue (status $status)")
                    close()
                    setBpm(null)
                    onConnectFailed()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val svc = g.getService(FtmsUuids.HR_SERVICE)
                val ch = svc?.getCharacteristic(FtmsUuids.HR_MEASUREMENT)
                if (ch == null) {
                    log("Cardio : pas de service fréquence cardiaque. Active la diffusion cardio sur la montre puis reconnecte.")
                    g.services.forEach { log("Cardio : service ${it.uuid}") }
                    disconnect()
                    return@launch
                }
                val props = ch.properties
                log(
                    "Cardio : service FC trouvé (notify=${props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0}, " +
                        "indicate=${props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0}, " +
                        "appairage=${bondLabel(g.device.bondState)})",
                )
                subscribeAttempts = 0
                subscribe(g, ch)
                _state.value = ConnState.CONNECTED
                log("Cardio : ${_name.value} connecté, en attente des mesures")
                startWatchdog(g, ch)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Cardio : abonnement aux mesures accepté par la montre")
                    return@launch
                }
                log("Cardio : abonnement refusé (code $status)")
                // 5 / 15 / 137 = authentification ou chiffrement requis : on appaire puis on réessaie
                if ((status == 5 || status == 15 || status == 137) && g.device.bondState != BluetoothDevice.BOND_BONDED) {
                    log("Cardio : la montre exige un appairage, demande en cours (accepte la notification Android)…")
                    runCatching { g.device.createBond() }
                }
                val ch = d.characteristic
                if (subscribeAttempts < 3) {
                    delay(4000)
                    if (g == gatt) subscribe(g, ch)
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = ch.value?.copyOf() ?: return
                scope.launch { onHrFrame(v) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            scope.launch { onHrFrame(v) }
        }
    }

    private fun onHrFrame(v: ByteArray) {
        if (framesLogged < 3) {
            framesLogged++
            log("Cardio : trame ${v.toHex()}")
        }
        val bpm = FtmsParser.parseHeartRate(v)
        if (bpm == null) {
            // valeur 0 = capteur pas encore stabilisé (montre mal ajustée ou mesure en cours)
            if (framesLogged <= 3) log("Cardio : mesure vide (0 bpm) — montre bien serrée au poignet ?")
            return
        }
        _noData.value = false
        setBpm(bpm)
    }

    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        subscribeAttempts++
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(FtmsUuids.CCCD)
        if (desc == null) {
            log("Cardio : pas de descripteur de notification, on écoute quand même")
            return
        }
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(desc, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                desc.value = value
                g.writeDescriptor(desc)
            }
        }
        if (!started) log("Cardio : impossible d'envoyer la demande d'abonnement (essai $subscribeAttempts)")
    }

    /** Si aucune mesure n'arrive, on réessaie l'abonnement puis on prévient l'utilisateur. */
    private fun startWatchdog(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        watchdog?.cancel()
        _noData.value = false
        watchdog = scope.launch {
            delay(8000)
            if (_bpm.value != null || g != gatt) return@launch
            log("Cardio : aucune mesure après 8 s, nouvel abonnement…")
            subscribe(g, ch)
            delay(8000)
            if (_bpm.value != null || g != gatt) return@launch
            _noData.value = true
            log(
                "Cardio : toujours aucune mesure. Vérifie que la diffusion FC est active sur la montre (écran laissé ouvert). " +
                    "Si tu as choisi l'entrée « (connectée) », essaie plutôt celle qui apparaît pendant la diffusion.",
            )
        }
    }

    private fun bondLabel(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "oui"
        BluetoothDevice.BOND_BONDING -> "en cours"
        else -> "non"
    }

    fun release() {
        stopScan()
        wantConnected = false
        close()
        scope.cancel()
    }
}
