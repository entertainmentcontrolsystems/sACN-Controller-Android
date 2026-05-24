@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.sacn.controller.viewmodel.MainViewModel

/**
 * DMX Output Monitor — Shows live DMX values per universe.
 *
 * Displays a per-universe 512-channel grid, grouped by fixture slot
 * assignments for quick verification of what's actually on the wire.
 */
@Composable
fun DmxMonitorScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var selectedUniverse by remember { mutableIntStateOf(0) }

    // Build universe data from fixtures
    val activeUniverses = remember(state.fixtures) {
        state.fixtures.map { it.universe }.distinct().sorted()
    }
    if (selectedUniverse == 0 && activeUniverses.isNotEmpty()) {
        selectedUniverse = activeUniverses.first()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Universe selector tabs
        if (activeUniverses.size > 1) {
            Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                ScrollableTabRow(
                    selectedTabIndex = activeUniverses.indexOf(selectedUniverse)
                        .coerceAtLeast(0),
                    containerColor = Color.Transparent,
                    contentColor = Accent,
                    edgePadding = 8.dp
                ) {
                    activeUniverses.forEach { uni ->
                        Tab(
                            selected = selectedUniverse == uni,
                            onClick = { selectedUniverse = uni },
                            text = {
                                Text("Uni $uni",
                                    color = if (selectedUniverse == uni) Accent else TextSecond,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                        )
                    }
                }
            }
        }

        // Monitor view of selected universe
        if (selectedUniverse > 0) {
            UniverseMonitorView(
                universe = selectedUniverse,
                fixtures = state.fixtures.filter { it.universe == selectedUniverse },
                dmxValues = state.dmxValues
            )
        } else if (state.fixtures.isEmpty()) {
            Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Add fixtures to monitor DMX output",
                        color = TextSecond, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun UniverseMonitorView(
    universe: Int,
    fixtures: List<com.sacn.controller.model.FixtureInstance>,
    dmxValues: Map<String, Map<Int, Int>>
) {
    // Build a mapping of DMX address → fixture name + channel
    data class SlotInfo(
        val address: Int,
        val fixtureName: String,
        val channelName: String,
        val value: Int
    )

    val slots = remember(fixtures, dmxValues) {
        buildList {
            fixtures.forEach { fix ->
                val vals = dmxValues[fix.id] ?: return@forEach
                vals.forEach { (offset, value) ->
                    val dmxAddr = fix.startAddress + offset - 1
                    if (dmxAddr in 1..512) {
                        add(SlotInfo(dmxAddr, fix.name, "Ch $offset", value))
                    }
                }
            }
        }.sortedBy { it.address }
    }

    val grouped = slots.groupBy { it.address }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.weight(1f)
    ) {
        // DMX header
        item(key = "hdr") {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Addr", color = TextSecond, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                Text("Value", color = TextSecond, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                Text("Pct", color = TextSecond, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                Text("Bar", color = TextSecond, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Fixture → Channel", color = TextSecond, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(160.dp))
            }
        }

        // Non-zero slots (compact)
        val nonZero = grouped.filter { (_, list) -> list.any { it.value > 0 } }
        if (nonZero.isNotEmpty()) {
            item(key = "active_hdr") {
                Surface(color = Color(0xFF1A301A), shape = RoundedCornerShape(4.dp)) {
                    Text(
                        "  ${nonZero.size} active slots",
                        color = Color(0xFF44CC88), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
            }
            items(nonZero.toList(), key = { "act_${it.first}" }) { (addr, addrSlots) ->
                val maxVal = addrSlots.maxOf { it.value }
                DmxSlotRow(addr, maxVal, addrSlots.first().fixtureName,
                    addrSlots.first().channelName)
            }
        }

        // Summary
        item(key = "summary") {
            Spacer(Modifier.height(8.dp))
            Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Universe $universe Summary", color = TextPrimary, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Total active addresses: ${nonZero.size}", color = TextSecond, fontSize = 11.sp)
                    Text("Total fixtures: ${fixtures.size}", color = TextSecond, fontSize = 11.sp)
                    Text("Highest value: ${
                        slots.maxOfOrNull { it.value } ?: 0
                    }/${
                        if (slots.maxOfOrNull { it.value } ?: 0 > 255) 65535 else 255
                    }", color = TextSecond, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DmxSlotRow(addr: Int, value: Int, fixture: String, channel: String) {
    val color = when {
        value > 255 -> Accent  // 16-bit range
        value > 128 -> Color(0xFFFFDD66)
        value > 0   -> Color(0xFF88EEFF)
        else        -> Color.Transparent
    }
    val pct = if (value > 255) ((value / 65535f) * 100).toInt()
              else (value / 2.55f).toInt()

    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Address
        Text("$addr", color = TextSecond, fontSize = 10.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.width(32.dp))

        // Value
        SelectionContainer {
            Text(value.toString(), color = TextPrimary, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        }

        // Percentage
        Text("$pct%", color = TextSecond, fontSize = 9.sp, modifier = Modifier.width(36.dp))

        // Bar
        LinearProgressIndicator(
            progress = { value.coerceAtMost(255) / 255f },
            modifier = Modifier.weight(1f).height(6.dp),
            color = color,
            trackColor = BgRaised
        )

        // Fixture + Channel label
        SelectionContainer {
            Text(
                "$fixture : $channel",
                color = TextSecond, fontSize = 9.sp,
                modifier = Modifier.width(160.dp),
                maxLines = 1
            )
        }
    }
}
