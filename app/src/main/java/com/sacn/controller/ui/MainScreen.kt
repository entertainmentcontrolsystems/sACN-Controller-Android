@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sacn.controller.model.*
import com.sacn.controller.viewmodel.MainViewModel

// ── Palette ───────────────────────────────────────────────────────────────────

private val BgDark      = Color(0xFF0E0E12)
private val BgCard      = Color(0xFF1A1A22)
private val Accent      = Color(0xFF4D9EFF)
private val TextPrimary = Color(0xFFE8E8F0)
private val TextSecond  = Color(0xFF8888AA)
private val Success     = Color(0xFF44CC88)
private val Warning     = Color(0xFFFFAA33)

// ── Main Screen ───────────────────────────────────────────────────────────────

@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()

    Scaffold(
        containerColor = BgDark,
        topBar = { TopBar(vm, state) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Fixture list
            if (state.fixtures.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No fixtures patched", color = TextSecond, fontSize = 16.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    // Group chips
                    if (state.groups.isNotEmpty()) {
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                                state.groups.forEach { group ->
                                    FilterChip(
                                        selected = group.fixtureIds.all { it in state.multiSelectedIds },
                                        onClick = { vm.selectGroup(group) },
                                        label = { Text(group.name, color = TextPrimary) },
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Fixtures
                    items(state.fixtures) { fixture ->
                        FixtureCard(fixture, state, vm)
                    }
                }
            }

            // Status message
            state.statusMessage?.let { msg ->
                Text(msg, color = TextSecond, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    }
}

// ── Top Bar ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(vm: MainViewModel, state: UiState) {
    var showAddMenu by remember { mutableStateOf(false) }
    var showSaveLookDialog by remember { mutableStateOf(false) }
    var showSaveGroupDialog by remember { mutableStateOf(false) }

    if (showSaveLookDialog) {
        AlertDialog(
            onDismissRequest = { showSaveLookDialog = false },
            title = { Text("Save Look") },
            text = {
                var name by remember { mutableStateOf("") }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = { vm.saveLook("Look ${state.looks.size + 1}"); showSaveLookDialog = false }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { showSaveLookDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSaveGroupDialog) {
        AlertDialog(
            onDismissRequest = { showSaveGroupDialog = false },
            title = { Text("Save Group") },
            text = {
                var name by remember { mutableStateOf("") }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = { vm.saveGroup("Group ${state.groups.size + 1}"); showSaveGroupDialog = false }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { showSaveGroupDialog = false }) { Text("Cancel") } }
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("sACN Controller", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Row {
            // Blackout button
            IconButton(onClick = { vm.toggleBlackout() }) {
                Icon(
                    if (state.blackoutActive) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = "Blackout",
                    tint = if (state.blackoutActive) Color.Red else TextSecond
                )
            }

            // Save Look
            IconButton(onClick = { showSaveLookDialog = true }) {
                Icon(Icons.Default.Save, contentDescription = "Save Look", tint = TextSecond)
            }

            // Multi-select actions
            if (state.multiSelectedIds.isNotEmpty()) {
                IconButton(onClick = { showSaveGroupDialog = true }) {
                    Icon(Icons.Default.Folder, contentDescription = "Save Group", tint = Color(0xFFCC88FF))
                }
            }

            // Add menu
            Box {
                IconButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Accent)
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Add Fixture", color = TextPrimary) },
                        onClick = { showAddMenu = false },
                        leadingIcon = { Icon(Icons.Default.Light, null, tint = Accent) }
                    )
                    DropdownMenuItem(
                        text = { Text("Import GDTF", color = TextPrimary) },
                        onClick = { showAddMenu = false },
                        leadingIcon = { Icon(Icons.Default.Upload, null, tint = Color(0xFF88EEFF)) }
                    )
                    if (state.multiSelectedIds.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Save Group (${state.multiSelectedIds.size} selected)", color = TextPrimary) },
                            onClick = { showAddMenu = false; showSaveGroupDialog = true },
                            leadingIcon = { Icon(Icons.Default.Folder, null, tint = Color(0xFFCC88FF)) }
                        )
                    }
                }
            }
        }
    }
}

// ── Fixture Card ───────────────────────────────────────────────────────────────

@Composable
private fun FixtureCard(fixture: FixtureInstance, state: UiState, vm: MainViewModel) {
    val isSelected = fixture.id == state.selectedFixtureId
    val values = state.dmxValues[fixture.id] ?: emptyMap()

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) BgCard else BgCard.copy(alpha = 0.6f)),
        onClick = { vm.toggleFixtureSelection(fixture.id) }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fixture.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                Text("U${fixture.universe}:${fixture.startAddress}", color = TextSecond, fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))

            // Channel sliders
            val profile = state.profiles.find { it.id == fixture.profileId }
            val mode = profile?.modes?.getOrNull(fixture.modeIndex)
            mode?.channels?.take(6)?.forEach { ch ->
                val value = values[ch.offset] ?: ch.defaultValue
                val maxVal = if (ch.is16Bit) 65535 else 255

                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.displayName, color = TextSecond, fontSize = 11.sp, modifier = Modifier.width(70.dp))
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { vm.setChannelValue(fixture.id, ch.offset, it.toInt()) },
                        valueRange = 0f..maxVal.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(value.toFloat() / maxVal * 100).toInt()}%", color = TextSecond, fontSize = 10.sp, modifier = Modifier.width(36.dp))
                }
            }

            // Actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.executeFixtureAction(fixture.id, FixtureAction.HOME) }) {
                    Text("Home", color = TextSecond, fontSize = 11.sp)
                }
                TextButton(onClick = { vm.deleteFixture(fixture.id) }) {
                    Text("Remove", color = Color.Red.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        }
    }
}
