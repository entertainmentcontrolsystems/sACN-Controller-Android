package com.sacn.controller

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.sacn.controller.ui.AppNavHost
import com.sacn.controller.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0E0E12),
                    surface    = Color(0xFF1A1A22),
                    primary    = Color(0xFF4D9EFF),
                    onBackground = Color(0xFFE8E8F0),
                    onSurface    = Color(0xFFE8E8F0)
                )
            ) {
                AppNavHost(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Acquire multicast lock — required on most Android devices for
        // sACN (E1.31) multicast to flow through the Wi-Fi chipset.
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("sacn_controller").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    override fun onPause() {
        super.onPause()
        multicastLock?.release()
        multicastLock = null
    }
}
