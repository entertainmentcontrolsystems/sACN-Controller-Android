package com.sacn.controller.model

import java.util.UUID

// ──────────────────────────────────────────────────────────────────────────────
// Channel
// ──────────────────────────────────────────────────────────────────────────────

enum class ChannelCategory { INTENSITY, COLOR, POSITION, GOBO, BEAM, OTHER }

data class ChannelDef(
    val name: String,           // Raw GDTF attribute (e.g. "ColorAdd_R")
    val displayName: String,    // Human-friendly (e.g. "Red")
    val offset: Int,            // 1-based DMX offset within fixture footprint (coarse byte)
    val fineOffset: Int? = null,// 1-based offset of fine byte (null if 8-bit)
    val defaultValue: Int = 0,  // 0–255 (coarse), 0–65535 if 16-bit
    val geometry: String = "",
    val category: ChannelCategory = ChannelCategory.OTHER,
    // Home value: where this channel goes when user presses "Home"
    val homeValue: Int = 0
) {
    val is16Bit: Boolean get() = fineOffset != null
    val maxValue: Int get() = if (is16Bit) 65535 else 255
}

// ──────────────────────────────────────────────────────────────────────────────
// DMX Mode
// ──────────────────────────────────────────────────────────────────────────────

data class DMXMode(
    val name: String,
    val channels: List<ChannelDef>,
    val footprint: Int          // Total DMX channel count
)

// ──────────────────────────────────────────────────────────────────────────────
// Fixture Profile  (parsed from GDTF)
// ──────────────────────────────────────────────────────────────────────────────

data class FixtureProfile(
    val id: String = UUID.randomUUID().toString(),
    val manufacturer: String,
    val name: String,
    val modes: List<DMXMode>,
    val gdtfFileName: String = ""
)

// ──────────────────────────────────────────────────────────────────────────────
// Fixture Instance  (a patched fixture in the rig)
// ──────────────────────────────────────────────────────────────────────────────

data class FixtureInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val profileId: String,
    val modeIndex: Int = 0,
    val universe: Int = 1,
    val startAddress: Int = 1   // 1-based DMX address
)

// ──────────────────────────────────────────────────────────────────────────────
// Look  (snapshot of all fixture channel values)
// ──────────────────────────────────────────────────────────────────────────────

data class Look(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    // Map<fixtureInstanceId, Map<channelOffset (1-based), value (0-255 or 0-65535)>>
    val fixtureStates: Map<String, Map<Int, Int>> = emptyMap(),
    val tags: List<String> = emptyList(),     // searchable categories
    val sortOrder: Int = 0
)

// ──────────────────────────────────────────────────────────────────────────────
// Attribute → Display name + category mapping
// ──────────────────────────────────────────────────────────────────────────────

data class AttributeInfo(val displayName: String, val category: ChannelCategory)

val GDTF_ATTRIBUTES: Map<String, AttributeInfo> = mapOf(
    "Dimmer"            to AttributeInfo("Intensity",    ChannelCategory.INTENSITY),
    "Shutter1"          to AttributeInfo("Shutter",      ChannelCategory.INTENSITY),
    "StrobeFrequency"   to AttributeInfo("Strobe Freq",  ChannelCategory.INTENSITY),
    "StrobeDuration"    to AttributeInfo("Strobe Dur",   ChannelCategory.INTENSITY),
    "StrobeRate"        to AttributeInfo("Strobe Rate",  ChannelCategory.INTENSITY),

    "ColorAdd_R"        to AttributeInfo("Red",          ChannelCategory.COLOR),
    "ColorAdd_G"        to AttributeInfo("Green",        ChannelCategory.COLOR),
    "ColorAdd_B"        to AttributeInfo("Blue",         ChannelCategory.COLOR),
    "ColorAdd_W"        to AttributeInfo("White",        ChannelCategory.COLOR),
    "ColorAdd_WW"       to AttributeInfo("Warm White",   ChannelCategory.COLOR),
    "ColorAdd_CW"       to AttributeInfo("Cool White",   ChannelCategory.COLOR),
    "ColorAdd_L"        to AttributeInfo("Lime",         ChannelCategory.COLOR),
    "ColorAdd_A"        to AttributeInfo("Amber",        ChannelCategory.COLOR),
    "ColorAdd_UV"       to AttributeInfo("UV",           ChannelCategory.COLOR),
    "ColorAdd_RY"       to AttributeInfo("Red-Yellow",   ChannelCategory.COLOR),
    "ColorAdd_GY"       to AttributeInfo("Green-Yellow", ChannelCategory.COLOR),
    "ColorAdd_GC"       to AttributeInfo("Green-Cyan",   ChannelCategory.COLOR),
    "ColorAdd_BC"       to AttributeInfo("Blue-Cyan",    ChannelCategory.COLOR),
    "ColorAdd_BM"       to AttributeInfo("Blue-Magenta", ChannelCategory.COLOR),
    "ColorAdd_RM"       to AttributeInfo("Red-Magenta",  ChannelCategory.COLOR),
    "ColorSub_R"        to AttributeInfo("CMY Red",      ChannelCategory.COLOR),
    "ColorSub_G"        to AttributeInfo("CMY Green",    ChannelCategory.COLOR),
    "ColorSub_B"        to AttributeInfo("CMY Blue",     ChannelCategory.COLOR),
    "CTO"               to AttributeInfo("CTO",          ChannelCategory.COLOR),
    "CTB"               to AttributeInfo("CTB",          ChannelCategory.COLOR),
    "CTC"               to AttributeInfo("CTC",          ChannelCategory.COLOR),
    "ColorMacro1"       to AttributeInfo("Color Macro",  ChannelCategory.COLOR),
    "HSB_Hue"           to AttributeInfo("Hue",          ChannelCategory.COLOR),
    "HSB_Saturation"    to AttributeInfo("Saturation",   ChannelCategory.COLOR),
    "HSB_Brightness"    to AttributeInfo("Brightness",   ChannelCategory.COLOR),

    "Pan"               to AttributeInfo("Pan",          ChannelCategory.POSITION),
    "Tilt"              to AttributeInfo("Tilt",         ChannelCategory.POSITION),
    "PanRotate"         to AttributeInfo("Pan Speed",    ChannelCategory.POSITION),
    "TiltRotate"        to AttributeInfo("Tilt Speed",   ChannelCategory.POSITION),
    "XYZ_X"             to AttributeInfo("X",            ChannelCategory.POSITION),
    "XYZ_Y"             to AttributeInfo("Y",            ChannelCategory.POSITION),
    "XYZ_Z"             to AttributeInfo("Z",            ChannelCategory.POSITION),

    "Gobo1"             to AttributeInfo("Gobo 1",       ChannelCategory.GOBO),
    "Gobo2"             to AttributeInfo("Gobo 2",       ChannelCategory.GOBO),
    "Gobo1Pos"          to AttributeInfo("Gobo 1 Rot",   ChannelCategory.GOBO),
    "Gobo2Pos"          to AttributeInfo("Gobo 2 Rot",   ChannelCategory.GOBO),
    "Gobo1PosRotate"    to AttributeInfo("Gobo 1 Spin",  ChannelCategory.GOBO),
    "Gobo2PosRotate"    to AttributeInfo("Gobo 2 Spin",  ChannelCategory.GOBO),

    "Zoom"              to AttributeInfo("Zoom",         ChannelCategory.BEAM),
    "Focus1"            to AttributeInfo("Focus",        ChannelCategory.BEAM),
    "Iris"              to AttributeInfo("Iris",         ChannelCategory.BEAM),
    "Frost1"            to AttributeInfo("Frost",        ChannelCategory.BEAM),
    "Prism1"            to AttributeInfo("Prism",        ChannelCategory.BEAM),
    "Prism1Pos"         to AttributeInfo("Prism Rot",    ChannelCategory.BEAM),
    "Effects1"          to AttributeInfo("Effects",      ChannelCategory.BEAM),
    "BeamEffects1"      to AttributeInfo("Beam FX",      ChannelCategory.BEAM),
    "ShaperRot"         to AttributeInfo("Shaper Rot",   ChannelCategory.BEAM),
    "Blade1A"           to AttributeInfo("Blade 1A",     ChannelCategory.BEAM),
    "Blade1B"           to AttributeInfo("Blade 1B",     ChannelCategory.BEAM),
    "Blade2A"           to AttributeInfo("Blade 2A",     ChannelCategory.BEAM),
    "Blade2B"           to AttributeInfo("Blade 2B",     ChannelCategory.BEAM),
    "Blade3A"           to AttributeInfo("Blade 3A",     ChannelCategory.BEAM),
    "Blade3B"           to AttributeInfo("Blade 3B",     ChannelCategory.BEAM),
    "Blade4A"           to AttributeInfo("Blade 4A",     ChannelCategory.BEAM),
    "Blade4B"           to AttributeInfo("Blade 4B",     ChannelCategory.BEAM),

    "Control1"          to AttributeInfo("Control",      ChannelCategory.OTHER),
    "NoFeature"         to AttributeInfo("Raw DMX",      ChannelCategory.OTHER),
    "Special"           to AttributeInfo("Special",      ChannelCategory.OTHER),
)

fun resolveAttribute(attr: String): AttributeInfo =
    GDTF_ATTRIBUTES[attr]
        ?: GDTF_ATTRIBUTES.entries.firstOrNull { attr.startsWith(it.key) }?.value
        ?: AttributeInfo(attr, ChannelCategory.OTHER)

// ──────────────────────────────────────────────────────────────────────────────
// Approximate emitter chromaticities for CIE 1931 xy picker
// Values from IES TM-30, LED manufacturer data-sheets, and NIST SP 250-91.
// Used for real-time chromaticity display and inverse-mix solve.
// These are NOMINAL — use Planckian sweep for production-accurate data.
// ──────────────────────────────────────────────────────────────────────────────

val EMITTER_XY: Map<String, Pair<Float, Float>> = mapOf(
    "ColorAdd_R"  to Pair(0.696f, 0.304f),  // Red        ~630 nm
    "ColorAdd_G"  to Pair(0.179f, 0.700f),  // Green      ~520 nm
    "ColorAdd_B"  to Pair(0.137f, 0.054f),  // Blue       ~450 nm
    "ColorAdd_W"  to Pair(0.320f, 0.336f),  // White      ~4000 K
    "ColorAdd_WW" to Pair(0.440f, 0.403f),  // Warm White ~2700 K
    "ColorAdd_CW" to Pair(0.294f, 0.311f),  // Cool White ~6500 K
    "ColorAdd_A"  to Pair(0.560f, 0.430f),  // Amber      ~590 nm  (ETC Lime proxy)
    "ColorAdd_L"  to Pair(0.391f, 0.547f),  // Lime       ~570 nm
    "ColorAdd_UV" to Pair(0.162f, 0.023f),  // UV/Indigo  ~400 nm
    "ColorAdd_RY" to Pair(0.570f, 0.428f),  // Red-Yellow ~600 nm  (ETC RY)
    "ColorAdd_GY" to Pair(0.391f, 0.547f),  // Green-Yellow/Lime ~565 nm
    "ColorAdd_GC" to Pair(0.130f, 0.590f),  // Green-Cyan ~505 nm
    "ColorAdd_BC" to Pair(0.112f, 0.216f),  // Blue-Cyan  ~480 nm
    "ColorAdd_BM" to Pair(0.215f, 0.043f),  // Blue-Magenta ~430 nm
    "ColorAdd_RM" to Pair(0.380f, 0.160f),  // Red-Magenta
    "ColorAdd_I"  to Pair(0.162f, 0.023f),  // Indigo     ~430 nm  (ETC I)
    "ColorAdd_C"  to Pair(0.075f, 0.565f),  // Cyan       ~500 nm  (ETC C)
    "ColorAdd_DR" to Pair(0.735f, 0.265f),  // Deep Red   ~660 nm  (ETC DR)
    // Sub-traktive (CMY) approximations
    "ColorSub_R"  to Pair(0.585f, 0.344f),
    "ColorSub_G"  to Pair(0.265f, 0.440f),
    "ColorSub_B"  to Pair(0.210f, 0.197f),
    // CTO/CTB treated as white with CCT offset
    "CTO"         to Pair(0.440f, 0.403f),
    "CTB"         to Pair(0.294f, 0.311f),
    "CTC"         to Pair(0.320f, 0.336f),
)

/** Resolve the best chromaticity for a GDTF attribute name. */
fun resolveEmitterXy(attr: String): Pair<Float, Float>? =
    EMITTER_XY[attr]
        ?: EMITTER_XY.entries.firstOrNull { attr.startsWith(it.key) }?.value

// ──────────────────────────────────────────────────────────────────────────────
// Converter configuration (sACN D16xy input → fixture DMX output)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Configures the D16xy → emitter-mix conversion engine.
 *
 * Input format: E1.31 on [inputUniverse], starting at [inputStartAddr],
 * using 6 channels: Dimmer(coarse+fine), CIE-x(coarse+fine), CIE-y(coarse+fine).
 *
 * Output: drives DMX values on [outputFixtureId] via the existing sACN send loop.
 */
data class ConverterConfig(
    val enabled         : Boolean = false,
    val inputUniverse   : Int     = 1,
    val inputStartAddr  : Int     = 1,    // 1-based
    val outputFixtureId : String  = ""    // empty = not yet assigned
)

// ──────────────────────────────────────────────────────────────────────────────
// App-level settings
// ──────────────────────────────────────────────────────────────────────────────

data class AppSettings(
    val sourceName    : String  = "sACN Controller",
    val sacnPriority  : Int     = 100,
    val outputUniverse: Int     = 0,     // 0 = use per-fixture universe
    val bindIp        : String  = ""     // empty = auto (0.0.0.0)
)

// ──────────────────────────────────────────────────────────────────────────────
// Fixture Group  (logical grouping of fixtures for collective control)
// ──────────────────────────────────────────────────────────────────────────────

data class FixtureGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val fixtureIds: List<String> = emptyList(),
    val color: Long = 0xFF4D9EFF,  // display color
    val sortOrder: Int = 0
)

// ──────────────────────────────────────────────────────────────────────────────
// Fixture Actions (Home, Lamp On/Off)
// ──────────────────────────────────────────────────────────────────────────────

enum class FixtureAction { HOME, LAMP_ON, LAMP_OFF, RESET }

/**
 * Known GDTF attributes that signal lamp control channels.
 * We resolve these to determine if a fixture has lamp strike/douse support.
 */
val LAMP_CONTROL_ATTRIBUTES = setOf(
    "LampControl", "LampOn", "LampOff", "LampOnOff", "Control2", "Control3"
)

/**
 * Default home values per-channel-category.
 * Intensity → full, Color → full white (all at max), everything else → 128 (mid).
 */
fun defaultHomeValue(cat: ChannelCategory, attr: String): Int {
    return when (cat) {
        ChannelCategory.INTENSITY -> if (attr.startsWith("Dimmer")) 255 else 128
        ChannelCategory.COLOR -> {
            // White: all colour channels at max for additive sources
            if (attr.startsWith("ColorSub_")) 0 else 255
        }
        ChannelCategory.POSITION -> 128
        else -> 128
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Network status snapshot
// ──────────────────────────────────────────────────────────────────────────────

data class NetworkStatus(
    val ssid: String = "",
    val wifiConnected: Boolean = false,
    val multicastLockHeld: Boolean = false,
    val bindIp: String = "",
    val sendErrors: Int = 0
)

// ──────────────────────────────────────────────────────────────────────────────
// Cue List  (sequenced playback with crossfades)
// ──────────────────────────────────────────────────────────────────────────────

enum class CueWaitType { MANUAL, TIMED }

data class CueStep(
    val id: String = UUID.randomUUID().toString(),
    val number: Float,           // e.g. 1, 1.5, 2 (allows insert-between)
    val label: String = "",
    val fadeInMs: Long = 2000,   // fade-in duration
    val delayMs: Long = 0,       // delay before executing
    val waitType: CueWaitType = CueWaitType.MANUAL,
    val waitTimeMs: Long = 0,    // for TIMED: auto-goto-next after this
    val fixtureStates: Map<String, Map<Int, Int>> = emptyMap()
)

data class CueList(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<CueStep> = emptyList(),
    val currentStepIndex: Int = -1,
    val isRunning: Boolean = false,
    val sortOrder: Int = 0
)

// ──────────────────────────────────────────────────────────────────────────────
// DMX Monitor data per-universe
// ──────────────────────────────────────────────────────────────────────────────

data class UniverseMonitorData(
    val universe: Int,
    val values: IntArray,
    val fixtureSlots: Map<Int, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UniverseMonitorData) return false
        return universe == other.universe &&
               values.contentEquals(other.values) &&
               fixtureSlots == other.fixtureSlots
    }
    override fun hashCode(): Int {
        var result = universe
        result = 31 * result + values.contentHashCode()
        result = 31 * result + fixtureSlots.hashCode()
        return result
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Show file (full export/import)
// ──────────────────────────────────────────────────────────────────────────────

data class ShowFile(
    val formatVersion: Int = 1,
    val appName: String = "sACN Controller",
    val exportedAt: Long = System.currentTimeMillis(),
    val profiles: List<FixtureProfile> = emptyList(),
    val fixtures: List<FixtureInstance> = emptyList(),
    val groups: List<FixtureGroup> = emptyList(),
    val looks: List<Look> = emptyList(),
    val cueLists: List<CueList> = emptyList()
)
