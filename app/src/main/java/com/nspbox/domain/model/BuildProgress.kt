package com.nspbox.domain.model

data class BuildProgress(
    val message: String = "Ready",
    val detail: String = "Select a game folder to begin.",
    val percent: Float = 0f,
    val isBuilding: Boolean = false,
    val isScanning: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)
