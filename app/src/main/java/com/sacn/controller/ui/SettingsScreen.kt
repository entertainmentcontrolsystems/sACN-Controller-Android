@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.sacn.controller.model.AppSettings
import com.sacn.controller.viewmodel.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val state    by vm.state.collectAsState()
    val settings  = state.settings

    var sourceName by remember(settings.sourceName)   { mutableStateOf(settings.sourceName) }
    var priority   by remember(settings.sacnPriority) { mutableIntStateOf(settings.sacnPriority) }
    var bindIp     by remember(settings.bindIp)       { mutableStateOf(settings.bindIp) }

    val dirty = sourceName != settings.sourceName || priority != settings.sacnPriority
            || bindIp != settings.bindIp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── sACN Output ───────────────────────────────────────────────────────
        SettingsSectionHeader("sACN Output")

        Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                OutlinedTextField(
                    value         = sourceName,
                    onValueChange = { sourceName = it.take(64) },
                    label         = { Text("Source Name (max 64 chars)", color = TextSecond, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = outlinedFieldColors()
                )

                OutlinedTextField(
                    value         = bindIp,
                    onValueChange = { bindIp = it },
                    label         = { Text("Bind IP (blank = auto)", color = TextSecond, fontSize = 11.sp) },
                    placeholder   = { Text("e.g. 192.168.1.50", color = TextSecond.copy(alpha = 0.5f), fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = outlinedFieldColors()
                )

                Column {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Priority", color = TextPrimary, fontSize = 13.sp)
                        Text(priority.toString(), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value         = priority.toFloat(),
                        onValueChange = { priority = it.toInt() },
                        valueRange    = 1f..200f,
                        steps         = 198,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = SliderDefaults.colors(
                            thumbColor       = Accent,
                            activeTrackColor = Accent.copy(alpha = 0.8f),
                            inactiveTrackColor = BgRaised
                        )
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("1", color = TextSecond, fontSize = 9.sp)
                        Text("100 = default   150 = override   200 = max",
                            color = TextSecond, fontSize = 9.sp)
                        Text("200", color = TextSecond, fontSize = 9.sp)
                    }
                }
            }
        }

        if (dirty) {
            Button(
                onClick = { vm.updateSettings(AppSettings(sourceName, priority, bindIp = bindIp)) },
                modifier = Modifier.align(Alignment.End),
                colors   = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Save Settings")
            }
        }

        // ── Protocol reference ────────────────────────────────────────────────
        SettingsSectionHeader("Protocol Reference")

        Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReferenceRow("Protocol", "ANSI E1.31-2018 (sACN)")
                ReferenceRow("Transport", "UDP multicast, port 5568")
                ReferenceRow("Multicast", "239.255.hi.lo per universe")
                ReferenceRow("Packet size", "638 bytes (full 512-ch universe)")
                ReferenceRow("Send rate", "~40 fps continuous")
                ReferenceRow("D16xy encoding", "raw16 / 65535.0 = 0.0–1.0")
            }
        }

        // ── About ─────────────────────────────────────────────────────────────
        SettingsSectionHeader("About")

        Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("sACN DMX Controller", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("GDTF fixture profiles  •  Multi-emitter CIE 1931 picker",
                    color = TextSecond, fontSize = 11.sp)
                Text("D16xy sACN converter  •  Looks / snapshots",
                    color = TextSecond, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text("Emitter chromaticities are nominal (IES TM-30 / datasheet).",
                    color = TextSecond, fontSize = 10.sp)
                Text("Use a Planckian sweep with a spectrometer for calibrated results.",
                    color = TextSecond, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(text.uppercase(), color = TextSecond, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ReferenceRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = TextSecond, fontSize = 11.sp, modifier = Modifier.width(110.dp))
        Text(value, color = TextPrimary, fontSize = 11.sp)
    }
}

@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor      = Accent,
    unfocusedBorderColor    = Color(0xFF444455),
    focusedTextColor        = TextPrimary,
    unfocusedTextColor      = TextPrimary,
    cursorColor             = Accent,
    focusedContainerColor   = BgRaised,
    unfocusedContainerColor = BgRaised
)
