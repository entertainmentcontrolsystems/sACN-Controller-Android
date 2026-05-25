@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
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

// ═══════════════════════════════════════════════════════════════════════════════
// UI State
// ═══════════════════════════════════════════════════════════════════════════════

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

// ═══════════════════════════════════════════════════════════════════════════════
// ViewModel
// ═══════════════════════════════════════════════════════════════════════════════

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db       = AppDatabase.get(app)
    private val sacn     = SACNSender()
    private val sacnRx   = SACNReceiver()
    private val cueEngine = CueListEngine()
    private val prefs    = app.getSharedPreferences("sacn_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var sendJob: Job? = null

    init {
        // Load settings
        val settings = AppSettings(
            sourceName   = prefs.getString("source_name", "sACN Controller") ?: "sACN Controller",
            sacnPriority = prefs.getInt("sacn_priority", 100),
            bindIp       = prefs.getString("bind_ip", "") ?: ""
        )
        _state.update { it.copy(settings = settings) }

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
        if (s.cuePlayback.isPlaying && s.cuePlayback.liveValues.isNotEmpty()) {
            sendDmxMap(s.cuePlayback.liveValues, s); return
        }
        if (s.blackoutActive) {
            val activeUnis = s.fixtures.map { it.universe }.distinct()
            activeUnis.forEach { sacn.sendUniverse(it, IntArray(512)) }; return
        }
        sendDmxMap(s.dmxValues, s)
    }

    private fun sendDmxMap(dmxMap: Map<String, Map<Int, Int>>, state: UiState) {
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
            s.copy(blackoutActive = new,
                statusMessage = if (new) "BLACKOUT" else "Output restored")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Fixture Selection & Channel Control
    // ═══════════════════════════════════════════════════════════════════════════

    fun selectFixture(fixtureId: String) {
        _state.update { it.copy(selectedFixtureId = fixtureId) }
    }

    fun toggleFixtureSelection(fixtureId: String) {
        _state.update { s ->
            val new = s.multiSelectedIds.toMutableSet()
            if (!new.remove(fixtureId)) new.add(fixtureId)
            s.copy(multiSelectedIds = new)
        }
    }

    fun clearMultiSelect() {
        _state.update { it.copy(multiSelectedIds = emptySet()) }
    }

    fun setChannelValue(fixtureId: String, offset: Int, value: Int) {
        _state.update { s ->
            val vals = s.dmxValues[fixtureId]?.toMutableMap() ?: mutableMapOf()
            vals[offset] = value
            s.copy(dmxValues = s.dmxValues + (fixtureId to vals))
        }
    }

    fun setMultiChannelValue(offset: Int, value: Int) {
        val ids = _state.value.multiSelectedIds
        if (ids.isEmpty()) return
        _state.update { s ->
            var newVals = s.dmxValues
            ids.forEach { fid ->
                val vals = newVals[fid]?.toMutableMap() ?: mutableMapOf()
                vals[offset] = value
                newVals = newVals + (fid to vals)
            }
            s.copy(dmxValues = newVals)
        }
    }

    fun executeFixtureAction(fixtureId: String, action: FixtureAction) {
        val s     = _state.value
        val fix   = s.fixtures.find { it.id == fixtureId } ?: return
        val prof  = s.profiles.find { it.id == fix.profileId } ?: return
        val mode  = prof.modes.getOrNull(fix.modeIndex) ?: return
        val newValues = mutableMapOf<Int, Int>()
        when (action) {
            FixtureAction.HOME -> mode.channels.forEach { ch ->
                newValues[ch.offset] = if (ch.homeValue > 0 || ch.defaultValue == 0) ch.homeValue else ch.defaultValue
            }
            FixtureAction.LAMP_ON -> {
                val lampCh = mode.channels.find { LAMP_CONTROL_ATTRIBUTES.contains(it.name) }
                if (lampCh != null) newValues[lampCh.offset] = 255
            }
            FixtureAction.LAMP_OFF -> {
                val lampCh = mode.channels.find { LAMP_CONTROL_ATTRIBUTES.contains(it.name) }
                if (lampCh != null) newValues[lampCh.offset] = 0
            }
            FixtureAction.RESET -> mode.channels.forEach { ch ->
                newValues[ch.offset] = ch.defaultValue
            }
        }
        if (newValues.isNotEmpty()) {
            _state.update { st ->
                val vals = st.dmxValues[fixtureId]?.toMutableMap() ?: mutableMapOf()
                vals.putAll(newValues)
                st.copy(dmxValues = st.dmxValues + (fixtureId to vals),
                    statusMessage = "${fix.name}: ${action.name}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Fixture Management
    // ═══════════════════════════════════════════════════════════════════════════

    fun importGdtf(ctx: Context, uri: Uri, name: String) {
        viewModelScope.launch {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.readBytes() ?: return@launch
                val parser = GdtfParser()
                val profile = parser.parse(bytes, name)
                val order = db.profileDao().getAll().size
                db.profileDao().upsert(profile.toEntity())
                _state.update { it.copy(statusMessage = "Imported $name") }
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Import failed: ${e.message}") }
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { db.profileDao().deleteById(profileId) }
    }

    fun addFixture(fixture: FixtureInstance) {
        viewModelScope.launch {
            val order = db.instanceDao().maxSortOrder()?.plus(1) ?: 0
            db.instanceDao().upsert(fixture.toEntity(order))
        }
    }

    fun removeFixture(fixtureId: String) { deleteFixture(fixtureId) }

    fun deleteFixture(fixtureId: String) {
        viewModelScope.launch {
            db.instanceDao().deleteById(fixtureId)
            _state.update { s ->
                val newVals = s.dmxValues.toMutableMap()
                newVals.remove(fixtureId)
                s.copy(dmxValues = newVals,
                    selectedFixtureId = if (s.selectedFixtureId == fixtureId) null else s.selectedFixtureId)
            }
        }
    }

    fun updateFixture(fixture: FixtureInstance) {
        viewModelScope.launch {
            val order = db.instanceDao().maxSortOrder()?.plus(1) ?: 0
            db.instanceDao().upsert(fixture.toEntity(order))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Groups
    // ═══════════════════════════════════════════════════════════════════════════

    fun selectGroup(group: FixtureGroup) {
        _state.update { s ->
            s.copy(multiSelectedIds = group.fixtureIds.toSet(),
                selectedFixtureId = group.fixtureIds.firstOrNull())
        }
    }

    fun saveGroup(name: String) {
        val ids = _state.value.multiSelectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val group = FixtureGroup(name = name, fixtureIds = ids,
                color = GROUP_COLORS[ids.size % GROUP_COLORS.size])
            val order = db.groupDao().maxSortOrder()?.plus(1) ?: 0
            db.groupDao().upsert(group.toEntity(order))
            _state.update { it.copy(statusMessage = "Group \"$name\" saved (${ids.size} fixtures)") }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch { db.groupDao().deleteById(groupId) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Looks
    // ═══════════════════════════════════════════════════════════════════════════

    fun saveLook(name: String) {
        viewModelScope.launch {
            val snapshot = _state.value.dmxValues.mapValues { it.value.toMap() }
            val look = Look(name = name, fixtureStates = snapshot)
            val order = db.lookDao().maxSortOrder()?.plus(1) ?: 0
            db.lookDao().upsert(look.toEntity(order))
            _state.update { it.copy(statusMessage = "Look \"$name\" saved") }
        }
    }

    fun recallLook(lookId: String) {
        val look = _state.value.looks.find { it.id == lookId } ?: return
        _state.update { it.copy(dmxValues = look.fixtureStates,
            statusMessage = "Look \"${look.name}\" recalled") }
    }

    fun deleteLook(lookId: String) {
        viewModelScope.launch { db.lookDao().deleteById(lookId) }
    }

    fun renameLook(lookId: String) {
        // Placeholder — rename via setLookTags for now
    }

    fun setLookTags(lookId: String, tags: List<String>) {
        val look = _state.value.looks.find { it.id == lookId } ?: return
        viewModelScope.launch {
            db.lookDao().upsert(look.copy(tags = tags).toEntity(look.sortOrder))
        }
    }

    fun searchLooks(query: String): List<Look> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _state.value.looks
        return _state.value.looks.filter { look ->
            look.name.lowercase().contains(q) || look.tags.any { it.lowercase().contains(q) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Cue Lists
    // ═══════════════════════════════════════════════════════════════════════════

    fun startCueList(cueListId: String) {
        val list = _state.value.cueLists.find { it.id == cueListId } ?: return
        cueEngine.start(list, viewModelScope)
        _state.update { it.copy(statusMessage = "Playing \"${list.name}\"") }
    }

    fun cueStop() { cueEngine.stop(); _state.update { it.copy(statusMessage = "Stopped") } }

    fun cueGo() { cueEngine.go(viewModelScope) }

    fun cueBack() { cueEngine.goBack(viewModelScope) }

    fun createCueList(name: String) { saveCueList(name) }

    fun saveCueList(name: String) {
        viewModelScope.launch {
            val list = CueList(name = name)
            val order = db.cueListDao().maxSortOrder()?.plus(1) ?: 0
            db.cueListDao().upsert(list.toEntity(order))
            _state.update { it.copy(statusMessage = "Cue list \"$name\" created") }
        }
    }

    fun deleteCueList(cueListId: String) {
        viewModelScope.launch { db.cueListDao().deleteById(cueListId) }
    }

    fun saveCurrentAsCue(cueListId: String) {
        val idx = _state.value.cueLists.indexOfFirst { it.id == cueListId }
        if (idx < 0) return
        val list = _state.value.cueLists[idx]
        val snapshot = _state.value.dmxValues.mapValues { it.value.toMap() }
        val nextNum = (list.steps.maxOfOrNull { it.number }?.plus(1f)?.toInt() ?: 1).toFloat()
        val step = CueStep(number = nextNum, label = "Cue ${nextNum.toInt()}", fixtureStates = snapshot)
        val updated = list.copy(steps = list.steps + step)
        viewModelScope.launch {
            db.cueListDao().upsert(updated.toEntity(list.sortOrder))
            _state.update { it.copy(statusMessage = "Added cue ${nextNum.toInt()} to \"${list.name}\"") }
        }
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
            _state.update { it.copy(statusMessage = "Select an output fixture first") }; return
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
        val emitterXY = resolveFixtureEmitterXY(fixture.id)
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
                _state.update { st ->
                    val vals = st.dmxValues[cfg.outputFixtureId]?.toMutableMap() ?: mutableMapOf()
                    vals.putAll(newVals)
                    st.copy(dmxValues = st.dmxValues + (cfg.outputFixtureId to vals))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Settings
    // ═══════════════════════════════════════════════════════════════════════════

    fun updateSettings(settings: AppSettings) {
        prefs.edit()
            .putString("source_name", settings.sourceName)
            .putInt("sacn_priority", settings.sacnPriority)
            .putString("bind_ip", settings.bindIp)
            .apply()
        sacn.priority = settings.sacnPriority
        sacn.sourceName = settings.sourceName
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch(Dispatchers.IO) {
            sacn.close()
            sacn.open(settings.bindIp)
            _state.update { it.copy(sacnRunning = true) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Show File
    // ═══════════════════════════════════════════════════════════════════════════

    fun exportShowFile(): String {
        val s = _state.value
        val file = ShowFile(profiles = s.profiles, fixtures = s.fixtures,
            groups = s.groups, looks = s.looks, cueLists = s.cueLists)
        return file.toJson()
    }

    fun importShowFile(json: String) {
        viewModelScope.launch {
            try {
                val file = showFileFromJson(json)
                if (file.formatVersion != 1) {
                    _state.update { it.copy(importError = "Unsupported show file version") }; return@launch
                }
                file.profiles.forEach { db.profileDao().upsert(it.toEntity()) }
                file.fixtures.forEach { db.instanceDao().upsert(it.toEntity()) }
                file.groups.forEach { db.groupDao().upsert(it.toEntity()) }
                file.looks.forEach { db.lookDao().upsert(it.toEntity()) }
                file.cueLists.forEach { db.cueListDao().upsert(it.toEntity()) }
                _state.update { it.copy(statusMessage = "Show imported: ${file.fixtures.size} fixtures, ${file.looks.size} looks") }
            } catch (e: Exception) {
                _state.update { it.copy(importError = "Import failed: ${e.message}") }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Status
    // ═══════════════════════════════════════════════════════════════════════════

    fun clearStatus() {
        _state.update { it.copy(statusMessage = null, importError = null) }
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
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun resolveFixtureEmitterXY(fixtureId: String): Map<String, Pair<Float, Float>> {
        val fix = _state.value.fixtures.find { it.id == fixtureId } ?: return emptyMap()
        val profile = _state.value.profiles.find { it.id == fix.profileId } ?: return emptyMap()
        val mode = profile.modes.getOrNull(fix.modeIndex) ?: return emptyMap()
        val result = mutableMapOf<String, Pair<Float, Float>>()
        mode.channels.forEach { ch ->
            val xy = resolveEmitterXy(ch.name)
            if (xy != null) result[ch.name] = xy
        }
        return result
    }

    companion object {
        private val GROUP_COLORS = listOf(
            0xFF4D9EFF, 0xFFFF9944, 0xFF44CC88, 0xFFCC88FF,
            0xFFFFDD66, 0xFF88EEFF, 0xFFFF6688, 0xFF66CCAA
        )
    }
}
