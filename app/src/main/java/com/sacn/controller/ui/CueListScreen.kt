@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.sacn.controller.engine.CueListEngine
import com.sacn.controller.viewmodel.MainViewModel

@Composable
fun CueListScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val pb    = state.cuePlayback

    var showNewListDialog  by remember { mutableStateOf(false) }
    var selectedListId     by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Playback transport (always visible when active) ─────────────────
        if (pb.isPlaying) {
            TransportBar(pb = pb, onGo = vm::cueGo, onBack = vm::cueBack, onStop = vm::cueStop)
        }

        // ── If a cue list is active, show detailed playback ─────────────────
        if (pb.cueListId != null && pb.isPlaying) {
            ActiveCueDetail(pb = pb, onSaveSnapshot = {
                vm.saveCurrentAsCue(pb.cueListId!!)
            })
        }

        // ── Saved cue lists ─────────────────────────────────────────────────
        SectionHeader("Saved Cue Lists")

        if (state.cueLists.isEmpty()) {
            Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No cue lists yet. Create one from your Looks, or save Looks as cues first.",
                        color = TextSecond, fontSize = 13.sp
                    )
                }
            }
        } else {
            state.cueLists.forEach { list ->
                val isActive = list.id == pb.cueListId
                Surface(
                    color  = if (isActive) AccentDim.copy(alpha = 0.3f) else BgCard,
                    shape  = RoundedCornerShape(10.dp),
                    border = if (isActive) BorderStroke(1.dp, Accent) else null
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedListId =
                                    if (selectedListId == list.id) null else list.id
                            }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isActive) Icons.Default.PlayArrow else Icons.Default.List,
                                null, tint = if (isActive) Accent else TextSecond,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(list.name, color = TextPrimary, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium)
                                Text("${list.steps.size} cues", color = TextSecond, fontSize = 11.sp)
                            }
                            Row {
                                IconButton(onClick = { vm.startCueList(list.id) }) {
                                    Icon(Icons.Default.PlayArrow, "Play", tint = Color(0xFF44CC88))
                                }
                                IconButton(onClick = { vm.deleteCueList(list.id) }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF6666),
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        if (selectedListId == list.id && list.steps.isNotEmpty()) {
                            HorizontalDivider(color = BgRaised)
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                list.steps.forEachIndexed { idx, step ->
                                    val isCurrentCue = pb.currentStepIndex == idx && isActive
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .background(
                                                if (isCurrentCue) AccentDim.copy(alpha = 0.4f)
                                                else Color.Transparent,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${step.number.toInt()}",
                                            color = if (isCurrentCue) Accent else TextSecond,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(24.dp)
                                        )
                                        Text(
                                            step.label.ifEmpty { "Cue ${step.number.toInt()}" },
                                            color = TextPrimary, fontSize = 12.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text("${step.fadeInMs / 1000}s",
                                            color = TextSecond, fontSize = 10.sp)
                                        if (step.waitType == com.sacn.controller.model.CueWaitType.TIMED) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("auto ${step.waitTimeMs / 1000}s",
                                                color = TextSecond, fontSize = 9.sp)
                                        }
                                    }
                                    if (isCurrentCue && pb.isFading) {
                                        LinearProgressIndicator(
                                            progress = { pb.fadeProgress },
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                            color = Accent,
                                            trackColor = BgRaised
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Create from Looks ────────────────────────────────────────────────
        if (state.looks.isNotEmpty() && state.cueLists.isEmpty()) {
            SectionHeader("Quick Start")
            Button(
                onClick = { showNewListDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Generate Cue List from ${state.looks.size} Looks")
            }
        }

        SectionHeader("Reference: Cue Format")
        Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormatRow("Fade", "Time to reach target (default 2s)")
                FormatRow("Delay", "Wait before fade starts")
                FormatRow("Wait", "TIMED = auto-advance after wait. MANUAL = press GO")
                FormatRow("Label", "Free-text for identifying the cue")
            }
        }
    }

    if (showNewListDialog) {
        SaveLookDialog(
            onSave = { n -> vm.saveCueList(n); showNewListDialog = false },
            onDismiss = { showNewListDialog = false }
        )
    }
}

@Composable
private fun TransportBar(
    pb  : CueListEngine.PlaybackState,
    onGo  : () -> Unit,
    onBack: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = Color(0xFF1A2E1A),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF2A5A2A))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(pb.cueListName, color = Color(0xFF44CC88), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Text("Cue ${pb.currentStepIndex + 1}/${pb.totalSteps}",
                    color = TextSecond, fontSize = 11.sp)
            }
            IconButton(onClick = onBack) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = Accent)
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, "Stop", tint = Color(0xFFFF6666),
                    modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onGo) {
                Icon(Icons.Default.PlayArrow, "GO", tint = Color(0xFF44CC88),
                    modifier = Modifier.size(32.dp))
            }
            if (pb.isFading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)) {
                    Text("${(pb.fadeProgress * 100).toInt()}%", color = Accent,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("${pb.elapsedMs / 1000}s", color = TextSecond, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ActiveCueDetail(
    pb: CueListEngine.PlaybackState,
    onSaveSnapshot: () -> Unit
) {
    Surface(color = BgCard, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Current Cue", color = TextSecond, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live: ${pb.liveValues.size} fixtures active",
                        color = Color(0xFF44CC88), fontSize = 10.sp)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSaveSnapshot) {
                        Text("Save as Cue", color = Accent, fontSize = 11.sp)
                    }
                }
            }
            pb.currentCue?.let { cue ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    Arrangement.SpaceBetween
                ) {
                    Text(cue.label.ifEmpty { "Cue ${cue.number.toInt()}" },
                        color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Fade: ${cue.fadeInMs / 1000}s", color = TextSecond, fontSize = 11.sp)
                        if (cue.waitType == com.sacn.controller.model.CueWaitType.TIMED) {
                            Text("Wait: ${cue.waitTimeMs / 1000}s auto", color = TextSecond, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text.uppercase(), color = TextSecond, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun FormatRow(label: String, desc: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Accent, fontSize = 10.sp, modifier = Modifier.width(70.dp))
        Text(desc, color = TextSecond, fontSize = 10.sp)
    }
}
