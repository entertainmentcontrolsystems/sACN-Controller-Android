package com.sacn.controller.viewmodel

import com.sacn.controller.data.AppDatabase
import com.sacn.controller.model.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages looks (DMX snapshots), groups, show file import/export.
 * Extracted from the monolithic MainViewModel.
 */
class LookAndGroupController(
    private val db: AppDatabase,
    private val state: StateFlow<UiState>,
    private val updateState: (UiState.() -> UiState) -> Unit
) {
    fun loadLooks() = db.lookDao().observeAll()
    fun loadGroups() = db.groupDao().observeAll()

    suspend fun saveLook(name: String) {
        val snapshot = state.value.dmxValues.mapValues { it.value.toMap() }
        val look = Look(name = name, fixtureStates = snapshot)
        val order = db.lookDao().maxSortOrder()?.plus(1) ?: 0
        db.lookDao().upsert(look.toEntity(order))
        updateState { copy(statusMessage = "Look \"$name\" saved") }
    }

    suspend fun recallLook(lookId: String) {
        val look = state.value.looks.find { it.id == lookId } ?: return
        updateState { copy(dmxValues = look.fixtureStates, statusMessage = "Look \"${look.name}\" recalled") }
    }

    suspend fun deleteLook(lookId: String) {
        db.lookDao().deleteById(lookId)
    }

    fun setLookTags(lookId: String, tags: List<String>) {
        val look = state.value.looks.find { it.id == lookId } ?: return
        db.lookDao().upsert(look.copy(tags = tags).toEntity(look.sortOrder))
    }

    suspend fun saveGroup(name: String, fixtureIds: List<String>) {
        if (fixtureIds.isEmpty()) return
        val group = FixtureGroup(
            name = name, fixtureIds = fixtureIds,
            color = GROUP_COLORS[fixtureIds.size % GROUP_COLORS.size]
        )
        val order = db.groupDao().maxSortOrder()?.plus(1) ?: 0
        db.groupDao().upsert(group.toEntity(order))
        updateState { copy(statusMessage = "Group \"$name\" saved (${fixtureIds.size} fixtures)") }
    }

    suspend fun deleteGroup(groupId: String) {
        db.groupDao().deleteById(groupId)
    }

    fun exportShowFile(): String {
        val s = state.value
        val file = ShowFile(
            profiles = s.profiles, fixtures = s.fixtures,
            groups = s.groups, looks = s.looks, cueLists = s.cueLists
        )
        return file.toJson()
    }

    suspend fun importShowFile(json: String) {
        val file = showFileFromJson(json)
        if (file.formatVersion != 1) {
            updateState { copy(importError = "Unsupported show file version") }
            return
        }
        file.profiles.forEach { db.profileDao().upsert(it.toEntity()) }
        file.fixtures.forEach { db.instanceDao().upsert(it.toEntity()) }
        file.groups.forEach { db.groupDao().upsert(it.toEntity()) }
        file.looks.forEach { db.lookDao().upsert(it.toEntity()) }
        file.cueLists.forEach { db.cueListDao().upsert(it.toEntity()) }
        updateState {
            copy(statusMessage = "Show imported: ${file.fixtures.size} fixtures, ${file.looks.size} looks")
        }
    }

    companion object {
        private val GROUP_COLORS = listOf(
            0xFF4D9EFF, 0xFFFF9944, 0xFF44CC88, 0xFFCC88FF,
            0xFFFFDD66, 0xFF88EEFF, 0xFFFF6688, 0xFF66CCAA
        )
    }
}
