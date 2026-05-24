package com.sacn.controller.viewmodel

import com.sacn.controller.data.AppDatabase
import com.sacn.controller.engine.CueListEngine
import com.sacn.controller.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages cue lists and playback engine.
 * Extracted from the monolithic MainViewModel.
 */
class CueListController(
    private val db: AppDatabase,
    private val cueEngine: CueListEngine,
    private val state: StateFlow<UiState>,
    private val updateState: (UiState.() -> UiState) -> Unit,
    private val scope: CoroutineScope
) {
    val playback: StateFlow<CueListEngine.PlaybackState> = cueEngine.playback

    fun loadCueLists() = db.cueListDao().observeAll()

    suspend fun createCueList(name: String) {
        val list = CueList(name = name)
        val order = db.cueListDao().maxSortOrder()?.plus(1) ?: 0
        db.cueListDao().upsert(list.toEntity(order))
    }

    suspend fun deleteCueList(cueListId: String) {
        db.cueListDao().deleteById(cueListId)
    }

    fun playCueList(cueListId: String) {
        val list = state.value.cueLists.find { it.id == cueListId } ?: return
        cueEngine.start(list, scope)
        updateState { copy(statusMessage = "Cue List \"${list.name}\" started") }
    }

    fun stopPlayback() {
        cueEngine.stop()
        updateState { copy(statusMessage = "Playback stopped") }
    }

    fun goNext() {
        cueEngine.go(scope)
    }

    fun goBack() {
        cueEngine.goBack(scope)
    }

    suspend fun saveCurrentAsCue(cueListId: String) {
        val list = state.value.cueLists.find { it.id == cueListId } ?: return
        val snapshot = state.value.dmxValues.mapValues { it.value.toMap() }
        val nextNum = (list.steps.maxOfOrNull { it.number }?.plus(1f)?.toInt() ?: 1).toFloat()
        val step = CueStep(number = nextNum, label = "Cue $nextNum", fixtureStates = snapshot)
        val updated = list.copy(steps = list.steps + step)
        db.cueListDao().upsert(updated.toEntity(list.sortOrder))
        updateState { copy(statusMessage = "Added cue $nextNum to \"${list.name}\"") }
    }
}
