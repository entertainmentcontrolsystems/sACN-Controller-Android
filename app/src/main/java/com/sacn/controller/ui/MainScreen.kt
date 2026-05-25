@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.sacn.controller.model.*
import com.sacn.controller.viewmodel.MainViewModel
import com.sacn.controller.viewmodel.UiState

// ── Palette ───────────────────────────────────────────────────────────────────

internal val BgDark      = Color(0xFF0E0E12)
internal val BgCard      = Color(0xFF1A1A22)
internal val BgRaised    = Color(0xFF22222E)
internal val Accent      = Color(0xFF4D9EFF)
internal val AccentDim   = Color(0xFF264F80)
internal val TextPrimary = Color(0xFFE8E8F0)
internal val TextSecond  = Color(0xFF8888AA)
private  val Success     = Color(0xFF44CC88)
private  val Warning     = Color(0xFFFFAA33)

private fun categoryColor(cat: ChannelCategory) = when (cat) {
    ChannelCategory.INTENSITY -> Color(0xFFFFDD66)
    ChannelCategory.COLOR     -> Color(0xFF88EEFF)
    ChannelCategory.POSITION  -> Color(0xFFCC88FF)
    ChannelCategory.GOBO      -> Color(0xFFFF9944)
    ChannelCategory.BEAM      -> Color(0xFF44DDAA)
    ChannelCategory.OTHER     -> TextSecond
}

// ── Root ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val ctx   = LocalContext.current

    var showAddFixtureDialog by remember { mutableStateOf(false) }
    var showAddMenu          by remember { mutableStateOf(false) }
    var showSaveLookDialog   by remember { mutableStateOf(false) }
    var showSaveGroupDialog  by remember { mutableStateOf(false) }
    var showFixtureActions   by remember { mutableStateOf(false) }
    var editingFixture       by remember { mutableStateOf<FixtureInstance?>(null) }

    val profileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "fixture.gdtf"
            vm.importGdtf(ctx, uri, name)
        }
    }

    // Content scaffold — no TopAppBar here, owned by AppNavHost
    Scaffold(
        containerColor = BgDark,
        // Action bar row: Save Look + Add menu
        topBar = {
            TopBarArea(state, vm, showAddMenu, showSaveLookDialog, showFixtureActions,
                { showAddMenu = it }, { showSaveLookDialog = it }, { showFixtureActions = it },
                profileLauncher, { showAddFixtureDialog = true }, { showSaveGroupDialog = true },
                { showSaveGroupDialog = true })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Looks bar (moved from Scaffold bottomBar to avoid Kotlin 2.1 compiler issue)
            if (state.looks.isNotEmpty()) {
                LooksBar(
                    looks = state.looks,
                    onRecall = vm::recallLook,
                    onDelete = vm::deleteLook,
                    onRename = vm::renameLook,
                    onSetTags = vm::setLookTags
                )
            }
            if (state.fixtures.isNotEmpty()) {
                FixtureSelectorStrip(
                    fixtures      = state.fixtures,
                    dmxValues     = state.dmxValues,
                    selectedId    = state.selectedFixtureId,
                    multiSelected = state.multiSelectedIds,
                    onSelect      = vm::selectFixture,
                    onToggleMulti = vm::toggleFixtureSelection,
                    onEdit        = { editingFixture = it },
                    onRemove      = vm::removeFixture
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val selFixture = state.fixtures.find { it.id == state.selectedFixtureId }
                if (selFixture != null) {
                    val selProfile = state.profiles.find { it.id == selFixture.profileId }
                    val selMode    = selProfile?.modes?.getOrNull(selFixture.modeIndex)
                    if (selMode != null) {
                        ChannelSliderPanel(
                            fixture       = selFixture,
                            mode          = selMode,
                            values        = state.dmxValues[selFixture.id] ?: emptyMap(),
                            onValueChange = { off, v -> vm.setChannelValue(selFixture.id, off, v) }
                        )
                    } else {
                        EmptyState(hasProfiles = state.profiles.isNotEmpty())
                    }
                } else {
                    EmptyState(hasProfiles = state.profiles.isNotEmpty())
                }
            }
        }
    }

    if (showAddFixtureDialog) {
        AddFixtureDialog(profiles = state.profiles, existingFixtures = state.fixtures,
            onAdd = { vm.addFixture(it) }, onDismiss = { showAddFixtureDialog = false })
    }
    editingFixture?.let { f ->
        EditFixtureDialog(fixture = f, profiles = state.profiles,
            onSave = { vm.updateFixture(it); editingFixture = null },
            onDismiss = { editingFixture = null })
    }
    if (showSaveLookDialog) {
        SaveLookDialog(onSave = { n -> vm.saveLook(n); showSaveLookDialog = false },
            onDismiss = { showSaveLookDialog = false })
    }
    if (showSaveGroupDialog) {
        SaveGroupDialog(onSave = { n -> vm.saveGroup(n); showSaveGroupDialog = false },
            onDismiss = { showSaveGroupDialog = false })
    }
    // Fixture actions
    val actionFixtureId = if (state.multiSelectedIds.size == 1) state.multiSelectedIds.first()
                           else state.selectedFixtureId
    if (showFixtureActions && actionFixtureId != null) {
        FixtureActionsDialog(
            fixtureId = actionFixtureId,
            onAction  = { act -> vm.executeFixtureAction(actionFixtureId, act); showFixtureActions = false },
            onDismiss = { showFixtureActions = false }
        )
    }
}

// ── Fixture strip ─────────────────────────────────────────────────────────────

@Composable
private fun FixtureSelectorStrip(
    fixtures      : List<FixtureInstance>,
    dmxValues     : Map<String, Map<Int, Int>>,
    selectedId    : String?,
    multiSelected : Set<String>,
    onSelect      : (String) -> Unit,
    onToggleMulti : (String) -> Unit,
    onEdit        : (FixtureInstance) -> Unit,
    onRemove      : (String) -> Unit
) {
    Surface(color = BgCard, tonalElevation = 2.dp) {
        LazyRow(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(fixtures, key = { it.id }) { fixture ->
                FixtureChip(
                    name          = fixture.name,
                    subtitle      = "U${fixture.universe}/${fixture.startAddress}",
                    isSelected    = fixture.id == selectedId,
                    isMultiSelected = fixture.id in multiSelected,
                    hasOutput     = dmxValues[fixture.id]?.values?.any { it > 0 } ?: false,
                    onClick       = { onSelect(fixture.id) },
                    onLongClick   = { onToggleMulti(fixture.id) },
                    onEdit        = { onEdit(fixture) },
                    onRemove      = { onRemove(fixture.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FixtureChip(
    name: String, subtitle: String, isSelected: Boolean, isMultiSelected: Boolean, hasOutput: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    val bgColor = when {
        isMultiSelected -> AccentDim.copy(alpha = 0.6f)
        isSelected -> AccentDim
        else -> BgRaised
    }
    val border = when {
        isMultiSelected -> BorderStroke(1.5.dp, Accent.copy(alpha = 0.5f))
        isSelected -> BorderStroke(1.5.dp, Accent)
        else -> null
    }
    Surface(
        color  = bgColor,
        shape  = RoundedCornerShape(8.dp),
        border = border,
        modifier = Modifier.combinedClickable(
            onClick     = { if (showActions) showActions = false else onClick() },
            onLongClick = { onLongClick().also { showActions = !showActions } }
        )
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (hasOutput) Success else Color(0xFF444455)))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(name, color = if (isSelected || isMultiSelected) Color.White else TextPrimary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TextSecond, fontSize = 10.sp)
            }
            if (showActions) {
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { onEdit(); showActions = false }, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Edit, "Edit", tint = Accent, modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Close, "Remove", tint = Color(0xFFFF6666), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

// ── Channel panel ─────────────────────────────────────────────────────────────

@Composable
private fun ChannelSliderPanel(
    fixture      : FixtureInstance,
    mode         : DMXMode,
    values       : Map<Int, Int>,
    onValueChange: (offset: Int, value: Int) -> Unit
) {
    // Fine offsets consumed by 16-bit pairs — exclude from display
    val fineOffsets   = mode.channels.mapNotNull { it.fineOffset }.toSet()
    val displayChans  = mode.channels.filter { it.offset !in fineOffsets }
    val grouped       = displayChans.groupBy { it.category }

    // All colour channels (after fine dedup) for CIE picker
    val colorChannels = grouped[ChannelCategory.COLOR] ?: emptyList()
    val hasColorEmitters = colorChannels.any { resolveEmitterXy(it.name) != null }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Mode: ${mode.name}  •  ${mode.footprint} ch  •  U${fixture.universe}/${fixture.startAddress}",
                color = TextSecond, fontSize = 11.sp
            )
        }

        ChannelCategory.entries.forEach { cat ->
            val channels = grouped[cat] ?: return@forEach

            item(key = "hdr_$cat") { CategoryHeader(cat) }

            // CIE 1931 picker — shown once at top of Color section
            if (cat == ChannelCategory.COLOR && hasColorEmitters) {
                item(key = "cie_picker") {
                    Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                        Box(Modifier.padding(12.dp)) {
                            CieColorPicker(
                                colorChannels = colorChannels,
                                values        = values,
                                onValueChange = onValueChange
                            )
                        }
                    }
                }
            }

            items(channels, key = { "${it.offset}_${it.name}" }) { ch ->
                ChannelSliderRow(
                    channel       = ch,
                    value         = values[ch.offset] ?: ch.defaultValue,
                    onValueChange = { onValueChange(ch.offset, it) }
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(cat: ChannelCategory) {
    val color = categoryColor(cat)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.width(16.dp), color = color.copy(alpha = 0.5f), thickness = 1.dp)
        Spacer(Modifier.width(6.dp))
        Text(cat.name.lowercase().replaceFirstChar { it.uppercase() },
            color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(6.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = color.copy(alpha = 0.25f), thickness = 1.dp)
    }
}

@Composable
private fun ChannelSliderRow(
    channel      : ChannelDef,
    value        : Int,
    onValueChange: (Int) -> Unit
) {
    val color  = categoryColor(channel.category)
    val maxVal = channel.maxValue.toFloat()
    val pct    = if (maxVal > 0) (value / maxVal * 100).toInt() else 0

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text(channel.displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (channel.is16Bit) "ch ${channel.offset}+${channel.fineOffset}  (16-bit)"
                    else "ch ${channel.offset}",
                    color = TextSecond, fontSize = 9.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value.toString(), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 42.dp), textAlign = TextAlign.End)
                Spacer(Modifier.width(4.dp))
                Text("($pct%)", color = TextSecond, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(20.dp).clip(CircleShape).background(BgRaised)
                    .clickable { onValueChange(0) }, contentAlignment = Alignment.Center) {
                    Text("0", color = TextSecond, fontSize = 9.sp)
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(20.dp).clip(CircleShape).background(BgRaised)
                    .clickable { onValueChange(channel.maxValue) }, contentAlignment = Alignment.Center) {
                    Text("F", color = TextSecond, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value.toFloat(), onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..channel.maxValue.toFloat(), modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = color,
                activeTrackColor = color.copy(alpha = 0.8f), inactiveTrackColor = BgRaised)
        )
    }
}

// ── Looks bar ─────────────────────────────────────────────────────────────────

@Composable
fun LooksBar(
    looks: List<Look>, onRecall: (Look) -> Unit,
    onDelete: (String) -> Unit, onRename: (String, String) -> Unit,
    onSetTags: (String, List<String>) -> Unit
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var tagEditId  by remember { mutableStateOf<String?>(null) }
    var tagText    by remember { mutableStateOf("") }

    val filtered = if (searchQuery.isBlank()) looks else {
        val q = searchQuery.trim().lowercase()
        looks.filter {
            it.name.lowercase().contains(q) ||
            it.tags.any { t -> t.lowercase().contains(q) }
        }
    }

    Surface(color = BgCard, tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = BgRaised)

            // Search bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = TextSecond, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("LOOKS", color = TextSecond, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f).height(32.dp),
                    placeholder = { Text("Search looks...", color = TextSecond.copy(alpha = 0.4f), fontSize = 10.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                if (searchQuery.isNotBlank()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(Icons.Default.Close, "Clear", tint = TextSecond, modifier = Modifier.size(12.dp))
                    }
                }
            }

            if (filtered.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    items(filtered, key = { it.id }) { look ->
                        LookChip(
                            look = look,
                            onClick = { onRecall(look) },
                            onLongClick = { renamingId = look.id; renameText = look.name },
                            onDelete = { onDelete(look.id) },
                            onTagEdit = { tagEditId = look.id; tagText = look.tags.joinToString(", ") }
                        )
                    }
                }
            } else if (searchQuery.isNotBlank()) {
                Text("No matches for \"$searchQuery\"", color = TextSecond, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }

    if (renamingId != null) {
        AlertDialog(
            onDismissRequest = { renamingId = null }, containerColor = BgCard,
            title = { Text("Rename Look", color = TextPrimary) },
            text  = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = TextSecond,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(renamingId!!, renameText); renamingId = null }) {
                    Text("Save", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel", color = TextSecond) } }
        )
    }

    if (tagEditId != null) {
        AlertDialog(
            onDismissRequest = { tagEditId = null }, containerColor = BgCard,
            title = { Text("Edit Tags", color = TextPrimary) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter comma-separated tags (e.g. \"intro, chorus, blue\")",
                        color = TextSecond, fontSize = 11.sp)
                    OutlinedTextField(
                        value = tagText, onValueChange = { tagText = it }, singleLine = true,
                        placeholder = { Text("tags...", color = TextSecond.copy(alpha = 0.4f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = TextSecond,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parts = tagText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onSetTags(tagEditId!!, parts); tagEditId = null
                }) { Text("Save", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { tagEditId = null }) { Text("Cancel", color = TextSecond) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LookChip(
    look: Look, onClick: () -> Unit, onLongClick: () -> Unit,
    onDelete: () -> Unit, onTagEdit: () -> Unit = {}
) {
    var showDelete by remember { mutableStateOf(false) }
    Surface(color = BgRaised, shape = RoundedCornerShape(6.dp),
        modifier = Modifier.combinedClickable(
            onClick     = { if (showDelete) showDelete = false else onClick() },
            onLongClick = { showDelete = true; onLongClick() }
        )
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bookmark, null, tint = Accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(look.name, color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showDelete) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Close, "Delete", tint = Color(0xFFFF6666),
                    modifier = Modifier.size(14.dp).clickable { onDelete(); showDelete = false })
            }
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
fun AddFixtureDialog(
    profiles        : List<FixtureProfile>,
    existingFixtures: List<FixtureInstance>,
    onAdd           : (FixtureInstance) -> Unit,
    onDismiss       : () -> Unit
) {
    var fixtureName         by remember { mutableStateOf("") }
    var selectedProfile     by remember { mutableStateOf(profiles.firstOrNull()) }
    var selectedModeIndex   by remember { mutableIntStateOf(0) }
    var universe            by remember { mutableStateOf("1") }
    var address             by remember { mutableStateOf("1") }
    var showProfileDropdown by remember { mutableStateOf(false) }
    var showModeDropdown    by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProfile) {
        if (fixtureName.isBlank() && selectedProfile != null) {
            val count = existingFixtures.count { it.profileId == selectedProfile!!.id } + 1
            fixtureName = "${selectedProfile!!.name} $count"
        }
        selectedModeIndex = 0
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = BgCard,
        title = { Text("Add Fixture", color = TextPrimary) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DarkTextField(value = fixtureName, onValueChange = { fixtureName = it }, label = "Fixture Name")

                // Profile selector
                Box {
                    Surface(color = BgRaised, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { showProfileDropdown = true }) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), Arrangement.SpaceBetween) {
                            Column {
                                Text("Profile", color = TextSecond, fontSize = 10.sp)
                                Text(selectedProfile?.let { "${it.manufacturer} ${it.name}" } ?: "Select…",
                                    color = TextPrimary, fontSize = 13.sp)
                            }
                            Icon(Icons.Default.ArrowDropDown, null, tint = TextSecond)
                        }
                    }
                    DropdownMenu(expanded = showProfileDropdown, onDismissRequest = { showProfileDropdown = false },
                        modifier = Modifier.background(BgRaised)) {
                        // Use explicit variable name to avoid K2 capture issue with lambda param
                        profiles.forEach { prof ->
                            DropdownMenuItem(
                                text = { Text("${prof.manufacturer} ${prof.name}", color = TextPrimary, fontSize = 13.sp) },
                                onClick = { selectedProfile = prof; showProfileDropdown = false }
                            )
                        }
                    }
                }

                // Mode selector
                val modes = selectedProfile?.modes ?: emptyList()
                if (modes.size > 1) {
                    Box {
                        Surface(color = BgRaised, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { showModeDropdown = true }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), Arrangement.SpaceBetween) {
                                Column {
                                    Text("Mode", color = TextSecond, fontSize = 10.sp)
                                    Text(modes.getOrNull(selectedModeIndex)?.let {
                                        "${it.name} (${it.footprint} ch)" } ?: "Select…",
                                        color = TextPrimary, fontSize = 13.sp)
                                }
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecond)
                            }
                        }
                        DropdownMenu(expanded = showModeDropdown, onDismissRequest = { showModeDropdown = false },
                            modifier = Modifier.background(BgRaised)) {
                            modes.forEachIndexed { idx, modeItem ->
                                DropdownMenuItem(
                                    text = { Text("${modeItem.name} (${modeItem.footprint} ch)", color = TextPrimary, fontSize = 13.sp) },
                                    onClick = { selectedModeIndex = idx; showModeDropdown = false }
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkTextField(value = universe, onValueChange = { universe = it.filter(Char::isDigit) },
                        label = "Universe", modifier = Modifier.weight(1f))
                    DarkTextField(value = address, onValueChange = { address = it.filter(Char::isDigit) },
                        label = "Address", modifier = Modifier.weight(1f))
                }
                modes.getOrNull(selectedModeIndex)?.let { modeItem ->
                    val start = address.toIntOrNull() ?: 1
                    Text("Footprint: ch $start – ${start + modeItem.footprint - 1}  (${modeItem.footprint} channels)",
                        color = TextSecond, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedProfile != null && fixtureName.isNotBlank(),
                onClick = {
                    val prof = selectedProfile ?: return@TextButton
                    onAdd(FixtureInstance(
                        name         = fixtureName.ifBlank { prof.name },
                        profileId    = prof.id,
                        modeIndex    = selectedModeIndex,
                        universe     = universe.toIntOrNull()?.coerceIn(1, 63999) ?: 1,
                        startAddress = address.toIntOrNull()?.coerceIn(1, 512) ?: 1
                    ))
                    onDismiss()
                }
            ) { Text("Add Fixture", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecond) } }
    )
}

@Composable
fun EditFixtureDialog(
    fixture  : FixtureInstance,
    profiles : List<FixtureProfile>,
    onSave   : (FixtureInstance) -> Unit,
    onDismiss: () -> Unit
) {
    var name             by remember { mutableStateOf(fixture.name) }
    var universe         by remember { mutableStateOf(fixture.universe.toString()) }
    var address          by remember { mutableStateOf(fixture.startAddress.toString()) }
    var modeIndex        by remember { mutableIntStateOf(fixture.modeIndex) }
    var showModeDropdown by remember { mutableStateOf(false) }

    val modes = profiles.find { it.id == fixture.profileId }?.modes ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = BgCard,
        title = { Text("Edit Fixture", color = TextPrimary) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DarkTextField(value = name, onValueChange = { name = it }, label = "Name")
                if (modes.size > 1) {
                    Box {
                        Surface(color = BgRaised, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { showModeDropdown = true }) {
                            Row(Modifier.padding(12.dp, 10.dp), Arrangement.SpaceBetween) {
                                Text(modes[modeIndex].name, color = TextPrimary)
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecond)
                            }
                        }
                        DropdownMenu(expanded = showModeDropdown, onDismissRequest = { showModeDropdown = false },
                            modifier = Modifier.background(BgRaised)) {
                            modes.forEachIndexed { idx, modeItem ->
                                DropdownMenuItem(
                                    text    = { Text("${modeItem.name} (${modeItem.footprint} ch)", color = TextPrimary) },
                                    onClick = { modeIndex = idx; showModeDropdown = false }
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkTextField(value = universe, onValueChange = { universe = it.filter(Char::isDigit) },
                        label = "Universe", modifier = Modifier.weight(1f))
                    DarkTextField(value = address, onValueChange = { address = it.filter(Char::isDigit) },
                        label = "Address", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(fixture.copy(
                    name         = name.ifBlank { fixture.name },
                    modeIndex    = modeIndex,
                    universe     = universe.toIntOrNull()?.coerceIn(1, 63999) ?: fixture.universe,
                    startAddress = address.toIntOrNull()?.coerceIn(1, 512) ?: fixture.startAddress
                ))
            }) { Text("Save", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecond) } }
    )
}

@Composable
fun SaveLookDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("Look ${System.currentTimeMillis() % 10000}") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = BgCard,
        title = { Text("Save Look", color = TextPrimary) },
        text  = { DarkTextField(value = name, onValueChange = { name = it }, label = "Look Name") },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name) }, enabled = name.isNotBlank()) {
                Text("Save", color = Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecond) } }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun DarkTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label, color = TextSecond) },
        singleLine = true, modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF444455),
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent,
            focusedContainerColor = BgRaised, unfocusedContainerColor = BgRaised
        )
    )
}

@Composable
private fun EmptyState(hasProfiles: Boolean) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lightbulb, null, tint = TextSecond, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (hasProfiles) "Tap + to add a fixture to the rig"
            else "Tap +  →  Import Fixture Profile  to get started",
            color = TextSecond, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

// ── Group Bar ─────────────────────────────────────────────────────────────────

@Composable
private fun GroupBar(
    groups: List<FixtureGroup>,
    onSelect: (FixtureGroup) -> Unit
) {
    Surface(color = BgRaised) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(groups, key = { it.id }) { group ->
                val color = Color(group.color)
                Surface(
                    color = color.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable { onSelect(group) }
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, tint = color, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(group.name, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(4.dp))
                        Text("${group.fixtureIds.size}", color = TextSecond, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

// ── Fixture Actions Dialog ────────────────────────────────────────────────────

@Composable
private fun FixtureActionsDialog(
    fixtureId: String,
    onAction: (FixtureAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        title = { Text("Fixture Actions", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionRow("Home", "Send all channels to home position", Icons.Default.Home) {
                    onAction(FixtureAction.HOME)
                }
                ActionRow("Reset", "Return to profile defaults", Icons.Default.Refresh) {
                    onAction(FixtureAction.RESET)
                }
                ActionRow("Lamp On", "Strike arc lamp (if supported)", Icons.Default.Light) {
                    onAction(FixtureAction.LAMP_ON)
                }
                ActionRow("Lamp Off", "Douse arc lamp (if supported)", Icons.Default.Lightbulb) {
                    onAction(FixtureAction.LAMP_OFF)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecond) } }
    )
}

// ── TopBarArea — extracted from Scaffold to reduce nesting depth ────────────
// Workaround for Kotlin 2.1.0 Windows compiler bug with deeply nested
// Compose lambdas inside Scaffold parameters.

@Composable
private fun TopBarArea(
    state: UiState,
    vm: MainViewModel,
    showAddMenu: Boolean,
    showSaveLookDialog: Boolean,
    showFixtureActions: Boolean,
    setAddMenu: (Boolean) -> Unit,
    setSaveLook: (Boolean) -> Unit,
    setFixtureActions: (Boolean) -> Unit,
    profileLauncher: androidx.activity.result.contract.ActivityResultLauncher<Intent>,
    onAddFixture: () -> Unit,
    onSaveGroup: () -> Unit,
    showGroup: (Boolean) -> Unit
) {
    Column {
        if (state.groups.isNotEmpty()) {
            GroupBar(groups = state.groups, onSelect = vm::selectGroup)
        }
        Surface(color = BgCard) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.multiSelectedIds.size >= 2) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckBox, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${state.multiSelectedIds.size}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                val selId = if (state.multiSelectedIds.size == 1) state.multiSelectedIds.first()
                            else state.selectedFixtureId
                if (selId != null && state.fixtures.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { setFixtureActions(true) }) {
                            Icon(Icons.Default.Settings, "Fixture Actions", tint = TextSecond, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.fixtures.isNotEmpty()) {
                    IconButton(onClick = { setSaveLook(true) }) {
                        Icon(Icons.Default.Bookmark, "Save Look", tint = Accent)
                    }
                }
                Box {
                    IconButton(onClick = { setAddMenu(true) }) {
                        Icon(Icons.Default.Add, "Add", tint = TextPrimary)
                    }
                    DropdownMenu(
                        expanded = showAddMenu, onDismissRequest = { setAddMenu(false) },
                        modifier = Modifier.background(BgRaised)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import Fixture Profile", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.FileOpen, null, tint = Accent) },
                            onClick = {
                                setAddMenu(false)
                                profileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
                                })
                            }
                        )
                        if (state.profiles.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Add Fixture to Rig", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Lightbulb, null, tint = Warning) },
                                onClick = { setAddMenu(false); onAddFixture() }
                            )
                        }
                        if (state.multiSelectedIds.size >= 2) {
                            DropdownMenuItem(
                                text = { Text("Save Group (${state.multiSelectedIds.size} selected)", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Folder, null, tint = Color(0xFFCC88FF)) },
                                onClick = { setAddMenu(false); onSaveGroup() }
                            )
                        }
                    }
                }
            }
        }
    }
}
