@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.sacn.controller.model.*
import com.sacn.controller.viewmodel.MainViewModel

@Composable
fun ConverterScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val cfg   = state.converterConfig
    val input = state.converterInput    // Triple(dimmer, x, y) or null

    // Local edit state — committed on save
    var inUniverse    by remember(cfg.inputUniverse)   { mutableIntStateOf(cfg.inputUniverse) }
    var inAddr        by remember(cfg.inputStartAddr)  { mutableIntStateOf(cfg.inputStartAddr) }
    var outFixtureId  by remember(cfg.outputFixtureId) { mutableStateOf(cfg.outputFixtureId) }
    var showFixturePicker by remember { mutableStateOf(false) }

    val outFixture    = state.fixtures.find { it.id == outFixtureId }
    val outProfile    = outFixture?.let { f -> state.profiles.find { it.id == f.profileId } }
    val outMode       = outProfile?.modes?.getOrNull(outFixture.modeIndex)
    val fineOffsets   = outMode?.channels?.mapNotNull { it.fineOffset }?.toSet() ?: emptySet()
    val colorChannels = outMode?.channels
        ?.filter { it.category == ChannelCategory.COLOR && it.offset !in fineOffsets }
        ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── Status banner ─────────────────────────────────────────────────────
        Surface(
            color = if (state.converterRunning) Color(0xFF1A2E1A) else BgCard,
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (state.converterRunning) Color(0xFF44CC88) else Color(0xFF555566))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            if (state.converterRunning) "CONVERTER ACTIVE" else "CONVERTER STOPPED",
                            color = if (state.converterRunning) Color(0xFF44CC88) else TextSecond,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        if (state.converterRunning) {
                            Text("Universe ${cfg.inputUniverse}  →  ${outFixture?.name ?: "—"}",
                                color = TextSecond, fontSize = 10.sp)
                        }
                    }
                }
                Button(
                    onClick = { if (state.converterRunning) vm.stopConverter() else vm.startConverter() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.converterRunning) Color(0xFF552222) else Accent
                    )
                ) {
                    Text(if (state.converterRunning) "Stop" else "Start")
                }
            }
        }

        // ── Input config ──────────────────────────────────────────────────────
        SectionHeader("sACN Input  (D16xy format)")

        Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Input Universe",
                        value = inUniverse,
                        range = 1..63999,
                        onValueChange = { inUniverse = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberField(
                        label = "Start Address",
                        value = inAddr,
                        range = 1..507,     // needs 6 channels
                        onValueChange = { inAddr = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Channels ${inAddr}–${inAddr + 5}: " +
                    "Dimmer(${inAddr},${inAddr+1})  x(${inAddr+2},${inAddr+3})  y(${inAddr+4},${inAddr+5})",
                    color = TextSecond, fontSize = 10.sp
                )
            }
        }

        // ── Output fixture ────────────────────────────────────────────────────
        SectionHeader("Output Fixture")

        Surface(
            color = BgCard, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().clickable { showFixturePicker = true }
        ) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(outFixture?.name ?: "Tap to select…", color = TextPrimary, fontSize = 14.sp)
                    if (outFixture != null) {
                        Text(
                            "U${outFixture.universe}/${outFixture.startAddress}  •  " +
                            "${colorChannels.size} color channels",
                            color = TextSecond, fontSize = 10.sp
                        )
                    }
                }
                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecond)
            }
        }

        // Save config
        if (inUniverse != cfg.inputUniverse || inAddr != cfg.inputStartAddr || outFixtureId != cfg.outputFixtureId) {
            TextButton(
                onClick = {
                    vm.updateConverterConfig(cfg.copy(
                        inputUniverse  = inUniverse,
                        inputStartAddr = inAddr,
                        outputFixtureId = outFixtureId
                    ))
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Save, null, tint = Accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Apply Config", color = Accent, fontSize = 12.sp)
            }
        }

        // ── Live CIE display ──────────────────────────────────────────────────
        if (state.converterRunning && colorChannels.isNotEmpty()) {
            SectionHeader("Live Input")

            if (input != null) {
                val (dimmer, x, y) = input
                Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            MetricBadge("Dimmer", "${(dimmer * 100).toInt()}%")
                            MetricBadge("x", "%.4f".format(x))
                            MetricBadge("y", "%.4f".format(y))
                        }
                        // Show CIE diagram in read-only mode (no touch → no onValueChange)
                        CieColorPicker(
                            colorChannels = colorChannels,
                            values        = state.dmxValues[cfg.outputFixtureId] ?: emptyMap(),
                            onValueChange = { _, _ -> }   // read-only here; writes go via converter engine
                        )
                    }
                }
            } else {
                Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Waiting for sACN input…", color = TextSecond, fontSize = 13.sp)
                    }
                }
            }
        } else if (state.converterRunning && colorChannels.isEmpty()) {
            Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Output fixture has no color channels with known chromaticities.\n" +
                        "Check the GDTF profile.",
                        color = TextSecond, fontSize = 12.sp
                    )
                }
            }
        }

        // ── Format reference ──────────────────────────────────────────────────
        SectionHeader("D16xy Format Reference")
        Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormatRow("Ch N+0, N+1", "Dimmer (16-bit)  0 = off, 65535 = full")
                FormatRow("Ch N+2, N+3", "CIE x (16-bit)  value / 65535 = 0.0–1.0")
                FormatRow("Ch N+4, N+5", "CIE y (16-bit)  value / 65535 = 0.0–1.0")
                Spacer(Modifier.height(4.dp))
                Text("D65 white:  x = 20503,  y = 21588", color = TextSecond, fontSize = 10.sp)
                Text("EOS: patch a custom 6-ch fixture on the input universe.",
                    color = TextSecond, fontSize = 10.sp)
            }
        }
    }

    // ── Fixture picker dialog ─────────────────────────────────────────────────
    if (showFixturePicker) {
        AlertDialog(
            onDismissRequest = { showFixturePicker = false },
            containerColor   = BgCard,
            title = { Text("Select Output Fixture", color = TextPrimary) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.fixtures.isEmpty()) {
                        Text("No fixtures in rig. Add a fixture first.",
                            color = TextSecond, fontSize = 13.sp)
                    }
                    state.fixtures.forEach { fix ->
                        val isSelected = fix.id == outFixtureId
                        Surface(
                            color  = if (isSelected) AccentDim else BgRaised,
                            shape  = RoundedCornerShape(6.dp),
                            border = if (isSelected) BorderStroke(1.dp, Accent) else null,
                            modifier = Modifier.fillMaxWidth().clickable {
                                outFixtureId = fix.id; showFixturePicker = false
                            }
                        ) {
                            Row(Modifier.padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lightbulb, null, tint = if (isSelected) Accent else TextSecond,
                                    modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(fix.name, color = TextPrimary, fontSize = 13.sp)
                                    Text("U${fix.universe}/${fix.startAddress}", color = TextSecond, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFixturePicker = false }) { Text("Done", color = Accent) } }
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(text.uppercase(), color = TextSecond, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun MetricBadge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecond, fontSize = 9.sp)
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FormatRow(channel: String, description: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(channel, color = Accent, fontSize = 10.sp, modifier = Modifier.width(110.dp),
            fontWeight = FontWeight.Medium)
        Text(description, color = TextSecond, fontSize = 10.sp)
    }
}

@Composable
private fun NumberField(
    label        : String,
    value        : Int,
    range        : IntRange,
    onValueChange: (Int) -> Unit,
    modifier     : Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value         = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit)
            raw.toIntOrNull()?.coerceIn(range)?.let(onValueChange)
        },
        label     = { Text(label, color = TextSecond, fontSize = 11.sp) },
        singleLine = true,
        modifier   = modifier,
        colors     = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Accent, unfocusedBorderColor = Color(0xFF444455),
            focusedTextColor     = TextPrimary, unfocusedTextColor = TextPrimary,
            cursorColor          = Accent, focusedContainerColor = BgRaised,
            unfocusedContainerColor = BgRaised
        )
    )
}
