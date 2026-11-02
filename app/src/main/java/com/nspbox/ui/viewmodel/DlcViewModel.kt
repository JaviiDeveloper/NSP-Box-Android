package com.nspbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nspbox.domain.engine.DlcEngine
import com.nspbox.domain.model.BuildProgress
import com.nspbox.domain.model.DlcSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DlcUiState(
    val folderPath: String = "",
    val titleIdOverride: String = "",
    val snapshots: List<DlcSnapshot> = emptyList(),
    val selectedIndex: Int = -1,
    val outputPath: String = "",
    val verify: Boolean = true,
    val baseGamePath: String = "",
    val progress: BuildProgress = BuildProgress(detail = "Select a DLC folder to begin.")
)

class DlcViewModel : ViewModel() {
    private val _state = MutableStateFlow(DlcUiState())
    val state: StateFlow<DlcUiState> = _state.asStateFlow()

    private var buildJob: Job? = null

    fun setFolder(path: String) {
        _state.value = _state.value.copy(folderPath = path)
    }

    fun setTitleIdOverride(value: String) {
        _state.value = _state.value.copy(titleIdOverride = value)
    }

    fun scan() {
        val folder = _state.value.folderPath.trim()
        if (folder.isEmpty()) return

        _state.value = _state.value.copy(
            progress = BuildProgress(message = "Scanning for DLC...", isScanning = true),
            snapshots = emptyList(),
            selectedIndex = -1
        )

        viewModelScope.launch {
            try {
                val override = _state.value.titleIdOverride.trim().ifEmpty { null }
                val snapshots = DlcEngine.findSnapshots(File(folder), override)
                _state.value = _state.value.copy(
                    snapshots = snapshots,
                    progress = BuildProgress()
                )
                if (snapshots.isNotEmpty()) selectSnapshot(0)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    progress = BuildProgress(
                        message = "Scan failed",
                        detail = e.message ?: "Unknown error",
                        error = e.message
                    )
                )
            }
        }
    }

    fun selectSnapshot(index: Int) {
        val snapshots = _state.value.snapshots
        if (index !in snapshots.indices) return
        val s = snapshots[index]
        val safeName = "%016X".format(s.titleId)
        val defaultOutput = File(s.root.parentFile, "$safeName.nsp").absolutePath

        _state.value = _state.value.copy(
            selectedIndex = index,
            outputPath = defaultOutput
        )
    }

    fun updateOutput(path: String) {
        _state.value = _state.value.copy(outputPath = path)
    }

    fun setVerify(value: Boolean) {
        _state.value = _state.value.copy(verify = value)
    }

    fun setBaseGamePath(path: String) {
        _state.value = _state.value.copy(baseGamePath = path)
    }

    fun build() {
        val s = _state.value
        val snapshot = s.snapshots.getOrNull(s.selectedIndex) ?: return
        if (s.progress.isBuilding) return

        _state.value = s.copy(
            progress = BuildProgress(message = "Starting DLC build...", isBuilding = true)
        )

        buildJob = viewModelScope.launch {
            try {
                val baseGame = s.baseGamePath.trim().let { if (it.isNotEmpty()) File(it) else null }

                DlcEngine.build(
                    snapshot = snapshot,
                    outputPath = File(s.outputPath),
                    verifyOutput = s.verify,
                    baseGame = baseGame,
                    notify = { msg, pct ->
                        _state.value = _state.value.copy(
                            progress = BuildProgress(
                                message = msg,
                                percent = pct,
                                isBuilding = true
                            )
                        )
                    }
                )

                _state.value = _state.value.copy(
                    progress = BuildProgress(
                        message = "DLC package ready",
                        detail = s.outputPath,
                        percent = 100f,
                        isComplete = true
                    )
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    progress = BuildProgress(
                        message = "DLC build failed",
                        detail = e.message ?: "Unknown error",
                        error = e.message
                    )
                )
            }
        }
    }

    fun cancelBuild() {
        buildJob?.cancel()
        _state.value = _state.value.copy(
            progress = BuildProgress(message = "Cancelled", detail = "DLC build was cancelled.")
        )
    }
}
