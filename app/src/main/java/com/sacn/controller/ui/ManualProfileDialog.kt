@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sacn.controller.model.ChannelCategory
import com.sacn.controller.model.ChannelDef
import com.sacn.controller.viewmodel.MainViewModel

/**
 * Dialog for creating a fixture profile manually — no GDTF required.
 *
 * User specifies:
 * - Manufacturer & fixture name
 * - List of DMX channels with name, range (8/16-bit), default value, category
 *
 * On save, creates a FixtureProfile with a single "Standard" DMXMode
 * and stores it in the local database, ready for patching.
 */

@Composable
fun ManualProfileDialog(
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var manufacturer by remember { mutableStateOf("") }
    var fixtureName by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf(listOf<ChannelDef>()) }
    var showAddChannel by remember { mutableStateOf(false) }

    // When we have at least one channel, auto-generate name if blank
    if (fixtureName.isBlank() && channels.isNotEmpty()) {
        fixtureName = "Manual ${channels.size}ch"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = {
            Text("Create Fixture Profile", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Manufacturer
                DarkTextField(
                    value = manufacturer,
                    onValueChange = { manufacturer = it },
                    label = "Manufacturer (e.g. ETC, Aputure)"
                )

                // Fixture Name
                DarkTextField(
                    value = fixtureName,
                    onValueChange = { fixtureName = it },
                    label = "Fixture Name"
                )

                // Channel list header
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DMX Channels", color = TextSecond, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showAddChannel = true }) {
                        Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Channel", color = Accent, fontSize = 12.sp)
                    }
                }

                // Channel list
                if (channels.isEmpty()) {
                    Text(
                        "No channels yet. Tap \"Add Channel\" to define your fixture's DMX layout.",
                        color = TextSecond, fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(channels) { index, ch ->
                            ChannelRow(
                                channel = ch,
                                index = index,
                                onUpdate = { updated ->
                                    channels = channels.toMutableList().also { it[index] = updated }
                                },
                                onDelete = {
                                    channels = channels.toMutableList().also { it.removeAt(index) }
                                },
                                onMoveUp = if (index > 0) {
                                    {
                                        channels = channels.toMutableList().also {
                                            val tmp = it[index]; it[index] = it[index - 1]; it[index - 1] = tmp
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (fixtureName.isNotBlank() && channels.isNotEmpty()) {
                        // Recalculate offsets to be consecutive starting at 1
                        val consecutive = mutableListOf<ChannelDef>()
                        var offset = 1
                        channels.forEach { ch ->
                            consecutive.add(ch.copy(offset = offset))
                            offset += if (ch.is16Bit) 2 else 1
                        }
                        vm.createManualProfile(manufacturer, fixtureName, consecutive)
                        onDismiss()
                    }
                },
                enabled = fixtureName.isNotBlank() && channels.isNotEmpty()
            ) {
                Text(
                    "Save Profile (${channels.size} channels)",
                    color = if (fixtureName.isNotBlank() && channels.isNotEmpty()) Accent else TextSecond
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecond)
            }
        }
    )

    // Add Channel sub-dialog
    if (showAddChannel) {
        AddChannelDialog(
            nextOffset = channels.lastOrNull()?.let { if (it.is16Bit) it.offset + 2 else it.offset + 1 } ?: 1,
            onSave = { ch ->
                channels = channels + ch
                showAddChannel = false
            },
            onDismiss = { showAddChannel = false }
        )
    }
}

// ── Single channel row ────────────────────────────────────────────────────────

@Composable
private fun ChannelRow(
    channel: ChannelDef,
    index: Int,
    onUpdate: (ChannelDef) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?
) {
    var editing by remember { mutableStateOf(false) }

    Surface(
        color = BgRaised,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Channel number
            Text(
                "${index + 1}",
                color = AccentDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(22.dp)
            )

            // Name
            Text(
                channel.displayName.ifBlank { "Ch ${channel.offset}" },
                color = TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )

            // Bit depth badge
            Surface(
                color = if (channel.is16Bit) Color(0xFF44CC88).copy(alpha = 0.2f) else BgCard,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    if (channel.is16Bit) "16bit" else "8bit",
                    color = if (channel.is16Bit) Success else TextSecond,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            // Category tag
            Surface(
                color = categoryColor(channel.category).copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    channel.category.name.uppercase(),
                    color = categoryColor(channel.category),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }

            // Move up
            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = TextSecond, modifier = Modifier.size(14.dp))
                }
            }

            // Edit
            IconButton(onClick = { editing = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Edit, "Edit", tint = TextSecond, modifier = Modifier.size(14.dp))
            }

            // Delete
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Delete, "Delete", tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
            }
        }
    }

    if (editing) {
        AddChannelDialog(
            nextOffset = channel.offset,
            initial = channel,
            onSave = { ch ->
                onUpdate(ch)
                editing = false
            },
            onDismiss = { editing = false }
        )
    }
}

// ── Add/Edit Channel Dialog ───────────────────────────────────────────────────

@Composable
private fun AddChannelDialog(
    nextOffset: Int,
    initial: ChannelDef? = null,
    onSave: (ChannelDef) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.displayName ?: "") }
    var is16Bit by remember { mutableStateOf(initial?.is16Bit ?: false) }
    var defaultVal by remember { mutableStateOf((initial?.defaultValue ?: 0).toString()) }
    var category by remember { mutableStateOf(initial?.category ?: ChannelCategory.INTENSITY) }
    var homeVal by remember { mutableStateOf((initial?.homeValue ?: 0).toString()) }
    var showCategoryMenu by remember { mutableStateOf(false) }

    val categories = listOf(
        ChannelCategory.INTENSITY, ChannelCategory.COLOR, ChannelCategory.POSITION,
        ChannelCategory.GOBO, ChannelCategory.BEAM, ChannelCategory.OTHER
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        title = {
            Text(
                if (initial != null) "Edit Channel" else "Add Channel",
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DMX Offset: $nextOffset", color = TextSecond, fontSize = 11.sp)

                DarkTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Channel Name (e.g. Dimmer, Red, Pan)"
                )

                // Category selector
                Box {
                    Surface(
                        color = BgRaised, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { showCategoryMenu = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            Arrangement.SpaceBetween,
                            Alignment.CenterVertically
                        ) {
                            Text("Category: ${category.name}", color = categoryColor(category), fontSize = 13.sp)
                            Icon(Icons.Default.ArrowDropDown, null, tint = TextSecond)
                        }
                    }
                    DropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false },
                        modifier = Modifier.background(BgRaised)
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        cat.name,
                                        color = categoryColor(cat),
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = { category = cat; showCategoryMenu = false }
                            )
                        }
                    }
                }

                // 8-bit vs 16-bit
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = is16Bit,
                        onCheckedChange = { is16Bit = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (is16Bit) "16-bit (2 DMX channels)" else "8-bit (1 DMX channel)",
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                }

                // Default value
                DarkTextField(
                    value = defaultVal,
                    onValueChange = { defaultVal = it },
                    label = "Default Value (0-${if (is16Bit) 65535 else 255})",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                // Home value
                DarkTextField(
                    value = homeVal,
                    onValueChange = { homeVal = it },
                    label = "Home Value (0-${if (is16Bit) 65535 else 255})",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val maxVal = if (is16Bit) 65535 else 255
                val def = defaultVal.toIntOrNull()?.coerceIn(0, maxVal) ?: 0
                val home = homeVal.toIntOrNull()?.coerceIn(0, maxVal) ?: 0
                val channel = ChannelDef(
                    name = name.ifBlank { "Channel $nextOffset" },
                    displayName = name.ifBlank { "Ch $nextOffset" },
                    offset = initial?.offset ?: nextOffset,
                    fineOffset = if (is16Bit) (initial?.offset ?: nextOffset) + 1 else null,
                    defaultValue = def,
                    category = category,
                    homeValue = home
                )
                onSave(channel)
            }) {
                Text("Save", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecond)
            }
        }
    )
}

// ── Dark text field utility ────────────────────────────────────────────────────

// Already defined in MainScreen.kt as internal — using existing reference
