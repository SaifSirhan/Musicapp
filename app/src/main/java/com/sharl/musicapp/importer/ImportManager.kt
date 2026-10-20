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

    fun update(state: ImportState) {
        _state.value = state
    }

    fun reset() {
        _state.value = ImportState.Idle
    }
}
