package com.sacn.controller.viewmodel

import com.sacn.controller.data.*
import com.sacn.controller.model.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages fixture profiles, instances, and channel values.
 * Extracted from the monolithic MainViewModel.
 */
class FixtureController(
    private val db: AppDatabase,
    private val state: StateFlow<UiState>,
    private val updateState: (UiState.() -> UiState) -> Unit
) {
    fun loadProfiles() = db.profileDao().observeAll()
    fun loadInstances() = db.instanceDao().observeAll()

    fun executeFixtureAction(fixtureId: String, action: FixtureAction) {
        val s = state.value
        val fix = s.fixtures.find { it.id == fixtureId } ?: return
        val prof = s.profiles.find { it.id == fix.profileId } ?: return
        val mode = prof.modes.getOrNull(fix.modeIndex) ?: return

        val newValues = mutableMapOf<Int, Int>()
        when (action) {
            FixtureAction.HOME -> mode.channels.forEach { ch ->
                newValues[ch.offset] = if (ch.homeValue > 0 || ch.defaultValue == 0)
                    ch.homeValue else ch.defaultValue
            }
            FixtureAction.LAMP_ON -> {
                val lampCh = mode.channels.find { LAMP_CONTROL_ATTRIBUTES.contains(it.name) }
                if (lampCh != null) newValues[lampCh.offset] = 255
            }
            FixtureAction.LAMP_OFF -> {
                val lampCh = mode.channels.find { LAMP_CONTROL_ATTRIBUTES.contains(it.name) }
                if (lampCh != null) newValues[lampCh.offset] = 0
            }
            FixtureAction.RESET -> mode.channels.forEach { ch ->
                newValues[ch.offset] = ch.defaultValue
            }
        }

        if (newValues.isNotEmpty()) {
            updateState { st ->
                val vals = st.dmxValues[fixtureId]?.toMutableMap() ?: mutableMapOf()
                vals.putAll(newValues)
                st.copy(
                    dmxValues = st.dmxValues + (fixtureId to vals),
                    statusMessage = "${fix.name}: ${action.name}"
                )
            }
        }
    }

    fun setSingleChannelValue(fixtureId: String, offset: Int, value: Int) {
        updateState { s ->
            val vals = s.dmxValues[fixtureId]?.toMutableMap() ?: mutableMapOf()
            vals[offset] = value
            s.copy(dmxValues = s.dmxValues + (fixtureId to vals))
        }
    }

    fun setMultiChannelValue(ids: Set<String>, offset: Int, value: Int) {
        if (ids.isEmpty()) return
        updateState { s ->
            var newVals = s.dmxValues
            ids.forEach { fid ->
                val vals = newVals[fid]?.toMutableMap() ?: mutableMapOf()
                vals[offset] = value
                newVals = newVals + (fid to vals)
            }
            s.copy(dmxValues = newVals)
        }
    }

    suspend fun importProfile(gdtfBytes: ByteArray, fileName: String) {
        val parser = com.sacn.controller.gdtf.GdtfParser()
        val profile = parser.parse(gdtfBytes, fileName)
        db.profileDao().upsert(profile.toEntity())
    }

    suspend fun deleteProfile(profileId: String) {
        db.profileDao().deleteById(profileId)
    }

    suspend fun addFixture(fixture: FixtureInstance) {
        val order = db.instanceDao().maxSortOrder()?.plus(1) ?: 0
        db.instanceDao().upsert(fixture.toEntity(order))
    }

    suspend fun deleteFixture(fixtureId: String) {
        db.instanceDao().deleteById(fixtureId)
        updateState { s ->
            val newVals = s.dmxValues.toMutableMap()
            newVals.remove(fixtureId)
            s.copy(
                dmxValues = newVals,
                selectedFixtureId = if (s.selectedFixtureId == fixtureId) null else s.selectedFixtureId
            )
        }
    }
}
