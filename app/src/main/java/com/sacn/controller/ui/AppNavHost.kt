@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sacn.controller.viewmodel.MainViewModel

// ── Nav destinations ──────────────────────────────────────────────────────────

private enum class NavTab(val label: String, val icon: ImageVector) {
    MANUAL   ("Manual",    Icons.Default.Tune),
    CUES     ("Cues",      Icons.Default.PlayArrow),
    MONITOR  ("Monitor",   Icons.Default.Visibility),
    CONVERTER("Converter", Icons.Default.SwapHoriz),
    SETTINGS ("Settings",  Icons.Default.Settings)
}


// ── Root nav composable ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var currentTab by remember { mutableStateOf(NavTab.MANUAL) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearStatus()
        }
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost   = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentTab) {
                            NavTab.MANUAL    -> "sACN Controller"
                            NavTab.CUES      -> "Cue Lists"
                            NavTab.MONITOR   -> "DMX Monitor"
                            NavTab.CONVERTER -> "D16xy Converter"
                            NavTab.SETTINGS  -> "Settings"
                        },
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgCard),
                actions = {
                    // Network status chip
                    val ns = state.networkStatus
                    if (ns.wifiConnected) {
                        Surface(
                            color = Color(0xFF1A301A),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                ns.ssid.ifEmpty { "WiFi" },
                                color = Color(0xFF44CC88), fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    // Blackout button
                    BlackoutButton(
                        isActive = state.blackoutActive,
                        onClick = vm::toggleBlackout
                    )
                    Spacer(Modifier.width(8.dp))
                    SacnStatusIndicator(
                        sacnRunning       = state.sacnRunning,
                        converterRunning  = state.converterRunning
                    )
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BgCard) {
                NavTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick  = { currentTab = tab },
                        icon     = { Icon(tab.icon, contentDescription = tab.label) },
                        label    = { Text(tab.label, fontSize = 11.sp) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Accent,
                            selectedTextColor   = Accent,
                            unselectedIconColor = TextSecond,
                            unselectedTextColor = TextSecond,
                            indicatorColor      = AccentDim
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (currentTab) {
                NavTab.MANUAL    -> MainScreen(vm)
                NavTab.CUES      -> CueListScreen(vm)
                NavTab.MONITOR   -> DmxMonitorScreen(vm)
                NavTab.CONVERTER -> ConverterScreen(vm)
                NavTab.SETTINGS  -> SettingsScreen(vm)
            }
        }
    }

    // Import error dialog — shown regardless of active tab
    state.importError?.let { err ->
        AlertDialog(
            onDismissRequest = vm::clearStatus,
            containerColor   = BgCard,
            title            = { Text("Import Error") },
            text             = { Text(err) },
            confirmButton    = { TextButton(onClick = vm::clearStatus) { Text("OK", color = Accent) } }
        )
    }
}

// ── Status indicator ──────────────────────────────────────────────────────────

@Composable
private fun SacnStatusIndicator(sacnRunning: Boolean, converterRunning: Boolean) {
    val color = when {
        converterRunning -> Color(0xFF88EEFF)   // cyan = converting
        sacnRunning      -> Color(0xFF44CC88)   // green = live
        else             -> Color(0xFFFFAA33)   // amber = stopped
    }
    val label = when {
        converterRunning -> "CONV"
        sacnRunning      -> "LIVE"
        else             -> "OFF"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Blackout button ──────────────────────────────────────────────────────────

@Composable
private fun BlackoutButton(isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(0.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) Color(0xFFCC2222) else Color(0xFF333344))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            if (isActive) "BLACKOUT" else "BO",
            color = if (isActive) Color.White else TextSecond,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
