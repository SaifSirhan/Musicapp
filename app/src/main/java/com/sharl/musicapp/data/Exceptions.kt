package com.sharl.musicapp.data

/**
 * Thrown when no streaming source is available (all engines failed).
 */
class StreamingBlockedException : Exception("No streaming source available")

/**
 * Phase 42: Thrown when user is offline and song is not downloaded.
 */
class OfflineException(message: String) : Exception(message)
