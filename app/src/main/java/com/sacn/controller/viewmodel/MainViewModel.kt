@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sacn.controller.data.*
import com.sacn.controller.gdtf.GdtfParser
import com.sacn.controller.model.*
import com.sacn.controller.sacn.SACNReceiver
import com.sacn.controller.sacn.SACNSender
import com.sacn.controller.sacn.parseD16xy
import com.sacn.controller.engine.CueListEngine
import com.sacn.controller.ui.insideLocus
import com.sacn.controller.ui.solveEmitterMix
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetAddress
import java.net.NetworkInterface

// ── UI State (single source of truth for the app) ────────────────────────────

data class UiState(
    val profiles         : List<FixtureProfile>     = emptyList(),
    val fixtures         : List<FixtureInstance>    = emptyList(),
    val looks            : List<Look>               = emptyList(),
    val groups           : List<FixtureGroup>       = emptyList(),
    val cueLists         : List<CueList>            = emptyList(),
    val selectedFixtureId: String?                  = null,
    val multiSelectedIds : Set<String>              = emptySet(),
    val dmxValues        : Map<String, Map<Int, Int>> = emptyMap(),
    val sacnRunning      : Boolean                  = false,
    val blackoutActive   : Boolean                  = false,
    val cuePlayback      : CueListEngine.PlaybackState = CueListEngine.PlaybackState(),
    val networkStatus    : NetworkStatus            = NetworkStatus(),
    val statusMessage    : String?                  = null,
    val importError      : String?                  = null,
    val converterConfig  : ConverterConfig          = ConverterConfig(),
    val converterRunning : Boolean                  = false,
    val converterInput   : Triple<Float, Float, Float>? = null,
    val settings         : AppSettings              = AppSettings()
)

// ── ViewModel (orchestrator — delegates to focused controllers) ──────────────

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ── Dependencies ─────────────────────────────────────────────────────────
    private val db          = AppDatabase.get(app)
    private val sacn        = SACNSender()
    private val sacnRx      = SACNReceiver()
    private val cueEngine   = CueListEngine()
    private val prefs       = app.getSharedPreferences("sacn_settings", Context.MODE_PRIVATE)

    // ── Focused controllers ──────────────────────────────────────────────────
    lateinit var fixtures: FixtureController
    lateinit var cues: CueListController
    lateinit var settingsCtrl: SettingsController
    lateinit var looksGroups: LookAndGroupController

    // ── State ────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var sendJob: Job? = null
    private val updateState: (UiState.() -> UiState) -> Unit = { block ->
        _state.update(block)
    }

    init {
        // Initialize controllers
        fixtures = FixtureController(db, state, updateState)
        cues = CueListController(db, cueEngine, state, updateState, viewModelScope)
        settingsCtrl = SettingsController(prefs, state, updateState)
        looksGroups = LookAndGroupController(db, state, updateState)

        // Load settings
        _state.update { it.copy(settings = settingsCtrl.load()) }

        // Start network monitor
        startNetworkMonitor()

        // Observe DB
        viewModelScope.launch {
            db.profileDao().observeAll().collect { entities ->
                _state.update { it.copy(profiles = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            db.instanceDao().observeAll().collect { entities ->
                val fixs = entities.map { it.toModel() }
                _state.update { s ->
                    val newVals = s.dmxValues.toMutableMap()
                    fixs.forEach { f -> if (!newVals.containsKey(f.id)) newVals[f.id] = emptyMap() }
                    s.copy(fixtures = fixs, dmxValues = newVals)
                }
            }
        }
        viewModelScope.launch {
            db.lookDao().observeAll().collect { entities ->
                _state.update { it.copy(looks = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            db.cueListDao().observeAll().collect { entities ->
                _state.update { it.copy(cueLists = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            db.groupDao().observeAll().collect { entities ->
                _state.update { it.copy(groups = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            cueEngine.playback.collect { pb ->
                _state.update { it.copy(cuePlayback = pb) }
            }
        }

        startSACN()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  sACN Send Loop
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startSACN() {
        sendJob?.cancel()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            sacn.sourceName = _state.value.settings.sourceName
            sacn.priority = _state.value.settings.sacnPriority
            sacn.open(_state.value.settings.bindIp)
            _state.update { it.copy(sacnRunning = true) }
            while (isActive) {
                sendAllUniverses()
                delay(25)
            }
        }
    }

    fun stopSACN() {
        sendJob?.cancel()
        sacn.close()
        _state.update { it.copy(sacnRunning = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopSACN()
        sacnRx.stop()
    }

    private fun sendAllUniverses() {
        val s = _state.value

        // Priority 1: Cue engine playback
        val cuePb = s.cuePlayback
        if (cuePb.isPlaying && cuePb.liveValues.isNotEmpty()) {
            sendDmxMap(cuePb.liveValues, s)
            return
        }

        // Priority 2: Blackout
        if (s.blackoutActive) {
            val activeUnis = s.fixtures.map { it.universe }.distinct()
            activeUnis.forEach { uni -> sacn.sendUniverse(uni, IntArray(512)) }
            return
        }

        // Priority 3: Manual control
        sendDmxMap(s.dmxValues, s)
    }

    private fun sendDmxMap(
        dmxMap: Map<String, Map<Int, Int>>,
        state: UiState
    ) {
        val universeArrays = HashMap<Int, IntArray>()
        state.fixtures.forEach { fixture ->
            val profile = state.profiles.find { it.id == fixture.profileId } ?: return@forEach
            val mode    = profile.modes.getOrNull(fixture.modeIndex)    ?: return@forEach
            val values  = dmxMap[fixture.id] ?: emptyMap()
            val array   = universeArrays.getOrPut(fixture.universe) { IntArray(512) }
            mode.channels.forEach { ch ->
                val value   = values[ch.offset] ?: ch.defaultValue
                val dmxAddr = fixture.startAddress + ch.offset - 2
                if (dmxAddr in 0 until 512) {
                    if (ch.is16Bit) {
                        array[dmxAddr] = (value shr 8) and 0xFF
                        val fineAddr = fixture.startAddress + (ch.fineOffset ?: 0) - 2
                        if (fineAddr in 0 until 512) array[fineAddr] = value and 0xFF
                    } else {
                        array[dmxAddr] = value and 0xFF
                    }
                }
            }
        }
        universeArrays.forEach { (universe, dmx) -> sacn.sendUniverse(universe, dmx) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Blackout
    // ═══════════════════════════════════════════════════════════════════════════

    fun toggleBlackout() {
        _state.update { s ->
            val new = !s.blackoutActive
            s.copy(
                blackoutActive = new,
                statusMessage = if (new) "⚠ BLACKOUT ACTIVE" else "Output restored"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Fixture Actions (delegated)
    // ═══════════════════════════════════════════════════════════════════════════

    fun executeFixtureAction(fixtureId: String, action: FixtureAction) =
        fixtures.executeFixtureAction(fixtureId, action)

    fun setChannelValue(fixtureId: String, offset: Int, value: Int) {
        fixtures.setSingleChannelValue(fixtureId, offset, value)
    }

    fun toggleFixtureSelection(fixtureId: String) {
        _state.update { s ->
            val new = s.multiSelectedIds.toMutableSet()
            if (!new.remove(fixtureId)) new.add(fixtureId)
            s.copy(multiSelectedIds = new)
        }
    }

    fun selectGroup(group: FixtureGroup) {
        _state.update { s ->
            s.copy(
                multiSelectedIds = group.fixtureIds.toSet(),
                selectedFixtureId = group.fixtureIds.firstOrNull()
            )
        }
    }

    fun clearMultiSelect() {
        _state.update { it.copy(multiSelectedIds = emptySet()) }
    }

    fun setMultiChannelValue(offset: Int, value: Int) {
        fixtures.setMultiChannelValue(_state.value.multiSelectedIds, offset, value)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Groups (delegated)
    // ═══════════════════════════════════════════════════════════════════════════

    fun saveGroup(name: String) {
        val ids = _state.value.multiSelectedIds.toList()
        viewModelScope.launch { looksGroups.saveGroup(name, ids) }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch { looksGroups.deleteGroup(groupId) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Looks (delegated)
    // ═══════════════════════════════════════════════════════════════════════════

    fun saveLook(name: String) {
        viewModelScope.launch { looksGroups.saveLook(name) }
    }

    fun recallLook(lookId: String) {
        viewModelScope.launch { looksGroups.recallLook(lookId) }
    }

    fun deleteLook(lookId: String) {
        viewModelScope.launch { looksGroups.deleteLook(lookId) }
    }

    fun setLookTags(lookId: String, tags: List<String>) {
        looksGroups.setLookTags(lookId, tags)
    }

    fun searchLooks(query: String): List<Look> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _state.value.looks
        return _state.value.looks.filter { look ->
            look.name.lowercase().contains(q) || look.tags.any { it.lowercase().contains(q) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Cue Lists (delegated)
    // ═══════════════════════════════════════════════════════════════════════════

    fun createCueList(name: String) {
        viewModelScope.launch { cues.createCueList(name) }
    }

    fun deleteCueList(cueListId: String) {
        viewModelScope.launch { cues.deleteCueList(cueListId) }
    }

    fun playCueList(cueListId: String) = cues.playCueList(cueListId)

    fun stopPlayback() = cues.stopPlayback()

    fun goNextCue() = cues.goNext()

    fun goBackCue() = cues.goBack()

    fun saveCurrentAsCue(cueListId: String) {
        viewModelScope.launch { cues.saveCurrentAsCue(cueListId) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  GDTF Import / Fixture Management (delegated)
    // ═══════════════════════════════════════════════════════════════════════════

    fun importGdtfProfile(gdtfBytes: ByteArray, fileName: String) {
        viewModelScope.launch { fixtures.importProfile(gdtfBytes, fileName) }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { fixtures.deleteProfile(profileId) }
    }

    fun addFixture(fixture: FixtureInstance) {
        viewModelScope.launch { fixtures.addFixture(fixture) }
    }

    fun deleteFixture(fixtureId: String) {
        viewModelScope.launch { fixtures.deleteFixture(fixtureId) }
    }

    fun updateFixture(fixture: FixtureInstance) {
        viewModelScope.launch { fixtures.updateFixture(fixture) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Converter
    // ═══════════════════════════════════════════════════════════════════════════

    fun updateConverterConfig(config: ConverterConfig) {
        _state.update { it.copy(converterConfig = config) }
    }

    fun startConverter() {
        val cfg = _state.value.converterConfig
        if (cfg.outputFixtureId.isEmpty()) {
            _state.update { it.copy(statusMessage = "Select an output fixture first") }
            return
        }
        sacnRx.stop()
        sacnRx.start(viewModelScope, cfg.inputUniverse) { data ->
            processConverterFrame(data.dmx)
        }
        _state.update { it.copy(converterRunning = true,
            statusMessage = "Converter listening on universe ${cfg.inputUniverse}") }
    }

    fun stopConverter() {
        sacnRx.stop()
        _state.update { it.copy(converterRunning = false, converterInput = null) }
    }

    private fun processConverterFrame(dmx: ByteArray) {
        val s   = _state.value
        val cfg = s.converterConfig
        val fixture = s.fixtures.find { it.id == cfg.outputFixtureId } ?: return

        val (dimmer, x, y) = parseD16xy(dmx, cfg.inputStartAddr) ?: return
        _state.update { it.copy(converterInput = Triple(dimmer, x, y)) }

        val emitterXY = fixture.resolveEmitterXY()
        val profile = s.profiles.find { it.id == fixture.profileId } ?: return
        val mode = profile.modes.getOrNull(fixture.modeIndex) ?: return

        val colorChannels = mode.channels.filter {
            it.category == ChannelCategory.COLOR || it.name.startsWith("Color")
        }

        if (emitterXY.isNotEmpty() && colorChannels.size >= 3) {
            val mixResult = solveEmitterMix(emitterXY, x, y)
            if (mixResult != null) {
                val (r, g, b) = mixResult
                val newVals = mutableMapOf<Int, Int>()
                val sortedChannels = colorChannels.sortedBy { it.offset }
                if (sortedChannels.size >= 3) {
                    val maxVals = sortedChannels.map { it.maxValue }
                    newVals[sortedChannels[0].offset] = (r * maxVals[0]).toInt().coerceIn(0, maxVals[0])
                    newVals[sortedChannels[1].offset] = (g * maxVals[1]).toInt().coerceIn(0, maxVals[1])
                    newVals[sortedChannels[2].offset] = (b * maxVals[2]).toInt().coerceIn(0, maxVals[2])
                }

                val dimmerCh = mode.channels.find { it.category == ChannelCategory.INTENSITY }
                if (dimmerCh != null) {
                    newVals[dimmerCh.offset] = (dimmer * dimmerCh.maxValue).toInt().coerceIn(0, dimmerCh.maxValue)
                }

                updateState { st ->
                    val vals = st.dmxValues[cfg.outputFixtureId]?.toMutableMap() ?: mutableMapOf()
                    vals.putAll(newVals)
                    st.copy(dmxValues = st.dmxValues + (cfg.outputFixtureId to vals))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Settings (delegated)
    // ═══════════════════════════════════════════════════════════════════════════

    fun updateSettings(settings: AppSettings) {
        settingsCtrl.update(settings)
        sacn.priority = settings.sacnPriority
        sacn.sourceName = settings.sourceName
        viewModelScope.launch(Dispatchers.IO) {
            sacn.close()
            sacn.open(settings.bindIp)
            _state.update { it.copy(sacnRunning = true) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Show File (delegated)
    // ═══════════════════════════════════════════════════════════════════════════

    fun exportShowFile(): String = looksGroups.exportShowFile()

    fun importShowFile(json: String) {
        viewModelScope.launch { looksGroups.importShowFile(json) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Network Monitor
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startNetworkMonitor() {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateNetworkStatus() }
            override fun onLost(network: Network) {
                _state.update { it.copy(networkStatus = it.networkStatus.copy(wifiConnected = false, ssid = "")) }
            }
        })
        updateNetworkStatus()
    }

    private fun updateNetworkStatus() {
        val wifi = getApplication<Application>()
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo
        val ssid = info.ssid?.replace("\"", "") ?: ""
        val connected = info.networkId != -1
        val bind = sacn.bindAddress

        _state.update {
            it.copy(networkStatus = NetworkStatus(
                ssid = ssid, wifiConnected = connected,
                multicastLockHeld = true, bindIp = bind,
                sendErrors = sacn.sendErrorCount
            ))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helper — resolve emitter chromaticities for a fixture
    // ═══════════════════════════════════════════════════════════════════════════

    private fun FixtureInstance.resolveEmitterXY(): Map<String, Pair<Float, Float>> {
        val profile = _state.value.profiles.find { it.id == this.profileId } ?: return emptyMap()
        val mode = profile.modes.getOrNull(this.modeIndex) ?: return emptyMap()
        val result = mutableMapOf<String, Pair<Float, Float>>()
        mode.channels.forEach { ch ->
            val xy = resolveEmitterXy(ch.name)
            if (xy != null) result[ch.name] = xy
        }
        return result
    }
}
