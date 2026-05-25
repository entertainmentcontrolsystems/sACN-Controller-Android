package com.sacn.controller.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.sacn.controller.model.*

/**
 * CueListEngine — Crossfade engine for sequenced cue playback.
 *
 * How crossfades work:
 *   - A "cue" is a CueStep with target DMX values per-fixture.
 *   - When GO is pressed:
 *     1. The current live state is captured as the fadeIn source.
 *     2. Delay (delayMs) passes.
 *     3. Over the next fadeInMs, channel values interpolate from source → target.
 *     4. If next cue has waitType=TIMED, auto-GO after waitTimeMs.
 *     5. If waitType=MANUAL, waits for the user to press GO.
 *   - STOP zeros all output (blackout via fadeOutMs on current cue).
 *
 * The engine runs inside a coroutine scope and emits state updates.
 */
class CueListEngine {

    data class PlaybackState(
        val cueListId: String? = null,
        val cueListName: String = "",
        val currentStepIndex: Int = -1,
        val nextStepIndex: Int = -1,
        val currentCue: CueStep? = null,
        val nextCue: CueStep? = null,
        val isPlaying: Boolean = false,
        val isFading: Boolean = false,
        val fadeProgress: Float = 0f,      // 0.0–1.0
        val elapsedMs: Long = 0,
        val totalSteps: Int = 0,
        /** Live interpolated DMX values — fixtureId → (offset → value) */
        val liveValues: Map<String, Map<Int, Int>> = emptyMap()
    )

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private var job: Job? = null
    private var fadeJob: Job? = null

    /** Source values for the fading-from cue (previous step or black) */
    private var fadeSource: Map<String, Map<Int, Int>> = emptyMap()
    private var cueList: CueList? = null

    /**
     * Start playback of a cue list.
     * Homes to step 0 and executes the first cue.
     */
    fun start(cueList: CueList, scope: CoroutineScope) {
        stop()
        this.cueList = cueList.copy(currentStepIndex = -1, isRunning = true)
        updateState {
            PlaybackState(
                cueListId = cueList.id,
                cueListName = cueList.name,
                totalSteps = cueList.steps.size,
                isPlaying = true
            )
        }

        if (cueList.steps.isNotEmpty()) {
            goNext(cueList, scope)
        }
    }

    fun stop() {
        fadeJob?.cancel()
        job?.cancel()
        fadeJob = null
        job = null
        fadeSource = emptyMap()
        updateState { PlaybackState() }
    }

    fun go(scope: CoroutineScope) {
        val list = this.cueList ?: return
        goNext(list, scope)
    }

    /**
     * Go back to the previous cue.
     * @param fadeTimeMs  Crossfade duration for the back transition (0 = snap, default)
     */
    fun goBack(scope: CoroutineScope, fadeTimeMs: Long = 0) {
        val list = this.cueList ?: return
        val prevIdx = (list.currentStepIndex - 1).coerceAtLeast(-1)
        val prevCue = if (prevIdx >= 0) list.steps[prevIdx] else null

        fadeJob?.cancel()
        job?.cancel()

        if (fadeTimeMs <= 0 || prevCue == null) {
            // Snap back: no crossfade
            updateState {
                PlaybackState(
                    cueListId       = list.id,
                    cueListName     = list.name,
                    currentStepIndex = prevIdx,
                    currentCue      = prevCue,
                    totalSteps      = list.steps.size,
                    liveValues      = prevCue?.fixtureStates ?: emptyMap(),
                    isPlaying       = true
                )
            }
            this@CueListEngine.cueList = list.copy(currentStepIndex = prevIdx)
        } else {
            // Fade back: create a virtual cue step with the previous values
            val currentVals = _playback.value.liveValues
            fadeSource = if (currentVals.isNotEmpty()) currentVals else emptyMap()
            updateState {
                PlaybackState(
                    cueListId       = list.id,
                    cueListName     = list.name,
                    currentStepIndex = prevIdx,
                    currentCue      = prevCue,
                    totalSteps      = list.steps.size,
                    liveValues      = currentVals,
                    isPlaying       = true
                )
            }
            job = scope.launch {
                val backStep = CueStep(number = prevCue.number, fadeInMs = fadeTimeMs, fixtureStates = prevCue.fixtureStates)
                executeCrossfade(list, backStep, prevIdx)
            }
        }
    }

    // ─── Internal ──────────────────────────────────────────────────────────────

    private fun goNext(list: CueList, scope: CoroutineScope) {
        fadeJob?.cancel()
        job?.cancel()

        val nextIdx = list.currentStepIndex + 1
        if (nextIdx >= list.steps.size) {
            // Reached end — stop but hold last state
            updateState { copy(isPlaying = false, isFading = false) }
            return
        }

        val nextStep = list.steps[nextIdx]
        val currentVals = _playback.value.liveValues
        fadeSource = if (currentVals.isNotEmpty()) currentVals
                     else emptyMap()  // first cue: fade from black

        updateState {
            PlaybackState(
                cueListId       = list.id,
                cueListName     = list.name,
                currentStepIndex = nextIdx,
                nextStepIndex   = nextIdx,
                currentCue      = nextStep,
                isPlaying       = true,
                totalSteps      = list.steps.size,
                liveValues      = currentVals
            )
        }

        // Execute delay + fade
        job = scope.launch {
            // Delay phase
            if (nextStep.delayMs > 0) {
                updateState { copy(isFading = false, fadeProgress = 0f) }
                val start = System.currentTimeMillis()
                while (isActive && System.currentTimeMillis() - start < nextStep.delayMs) {
                    updateState {
                        copy(elapsedMs = System.currentTimeMillis() - start)
                    }
                    delay(33)
                }
            }

            // Fade phase
            executeCrossfade(list, nextStep, nextIdx)
        }
    }

    private suspend fun executeCrossfade(
        list: CueList, step: CueStep, stepIdx: Int
    ) {
        val source = fadeSource
        val target = step.fixtureStates
        val duration = step.fadeInMs

        if (duration <= 0) {
            // Instant snap
            updateState {
                copy(isFading = false, fadeProgress = 1f,
                    liveValues = target, elapsedMs = 0)
            }
            afterCue(list, step, stepIdx)
            return
        }

        val startMs = System.currentTimeMillis()
        while (isActive) {
            val elapsed = System.currentTimeMillis() - startMs
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val live = interpolate(source, target, progress)

            updateState {
                copy(isFading = progress < 1f, fadeProgress = progress,
                    liveValues = live, elapsedMs = elapsed)
            }

            if (progress >= 1f) break
            delay(16)  // ~60fps granularity
        }

        afterCue(list, step, stepIdx)
    }

    private fun afterCue(list: CueList, step: CueStep, stepIdx: Int) {
        this.cueList = list.copy(currentStepIndex = stepIdx)
        updateState { copy(isFading = false, fadeProgress = 1f) }

        // Auto-GO?
        if (step.waitType == CueWaitType.TIMED && step.waitTimeMs > 0) {
            // schedule next GO
            job = (job as? CompletableJob)?.let { parent ->
                parent.launch {
                    delay(step.waitTimeMs)
                    if (isActive) {
                        goNext(this@CueListEngine.cueList!!, parent)
                    }
                }
            } ?: return
        }
    }

    private fun updateState(block: PlaybackState.() -> PlaybackState) {
        _playback.value = _playback.value.block()
    }

    // ─── Interpolation engine ────────────────────────────────────────────────

    /**
     * Crossfade between source and target DMX states by linear interpolation.
     *
     * For each fixture that appears in either state:
     *   - Channel present in both → linear ramp
     *   - Channel only in target → fade in from 0
     *   - Channel only in source → fade out to 0
     *
     * Uses S-curve easing (smoothstep) for natural-looking fades.
     */
    private fun interpolate(
        source  : Map<String, Map<Int, Int>>,
        target  : Map<String, Map<Int, Int>>,
        progress: Float
    ): Map<String, Map<Int, Int>> {
        if (progress <= 0f) return source
        if (progress >= 1f) return target

        // Smoothstep easing: t = t²(3 − 2t)
        val t = progress * progress * (3f - 2f * progress)

        val result = mutableMapOf<String, Map<Int, Int>>()
        val allFixtureIds = (source.keys + target.keys).toSet()

        for (fid in allFixtureIds) {
            val srcVals = source[fid] ?: emptyMap()
            val tgtVals = target[fid] ?: emptyMap()
            val allChannels = (srcVals.keys + tgtVals.keys).toSet()

            if (allChannels.isEmpty()) continue

            val channelMap = mutableMapOf<Int, Int>()
            for (ch in allChannels) {
                val src = srcVals[ch] ?: 0
                val tgt = tgtVals[ch] ?: 0
                channelMap[ch] = (src + (tgt - src) * t).toInt().coerceIn(0, 65535)
            }
            result[fid] = channelMap
        }

        return result
    }
}
