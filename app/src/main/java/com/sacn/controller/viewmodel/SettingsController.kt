package com.sacn.controller.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.sacn.controller.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages app settings persistence.
 * Extracted from the monolithic MainViewModel.
 */
class SettingsController(
    private val prefs: SharedPreferences,
    private val state: StateFlow<UiState>,
    private val updateState: (UiState.() -> UiState) -> Unit
) {
    fun load(): AppSettings {
        return AppSettings(
            sourceName   = prefs.getString("source_name", "sACN Controller") ?: "sACN Controller",
            sacnPriority = prefs.getInt("sacn_priority", 100),
            bindIp       = prefs.getString("bind_ip", "") ?: ""
        )
    }

    fun update(settings: AppSettings) {
        prefs.edit()
            .putString("source_name", settings.sourceName)
            .putInt("sacn_priority", settings.sacnPriority)
            .putString("bind_ip", settings.bindIp)
            .apply()
        updateState { copy(settings = settings) }
    }
}
