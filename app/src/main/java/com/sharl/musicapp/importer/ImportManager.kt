package com.sharl.musicapp.importer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ImportState {
    data object Idle : ImportState()
    data class Running(val current: Int, val total: Int) : ImportState()
    data class Done(val imported: Int, val skipped: Int) : ImportState()
    data class Error(val message: String) : ImportState()
}

/**
 * Process-wide import status. Lives outside the service so the UI keeps
 * observing progress when the activity is recreated.
 */
object ImportManager {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    // After a cancel the service's in-flight coroutine can still emit progress
    // for the track it was resolving. Gate updates so those late writes cannot
    // flip the UI back to Running once we've gone Idle.
    @Volatile
    private var acceptingUpdates = true

    fun begin() {
        acceptingUpdates = true
    }

    fun update(state: ImportState) {
        if (!acceptingUpdates) return
        _state.value = state
    }

    fun reset() {
        acceptingUpdates = false
        _state.value = ImportState.Idle
    }
}
