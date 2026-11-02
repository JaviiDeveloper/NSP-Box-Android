package com.nspbox.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nspbox.domain.engine.GameEngine
import com.nspbox.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

data class GameUiState(
    val folderPath: String = "",
    val snapshots: List<Snapshot> = emptyList(),
    val selectedIndex: Int = -1,
    val title: String = "",
    val displayVersion: String = "Snapshot",
    val titleIdText: String = "Choose a game folder to detect the title ID.",
    val artworkPath: File? = null,
    val artworkBitmap: Bitmap? = null,
    val cropBox: CropBox? = null,
    val outputPath: String = "",
    val verify: Boolean = true,
    val packageFolder: Boolean = true,
    val progress: BuildProgress = BuildProgress(),
    val skippedFolders: List<File> = emptyList()
)

class GameViewModel : ViewModel() {
    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private var buildJob: Job? = null

    fun setFolder(path: String) {
        _state.value = _state.value.copy(folderPath = path)
        scan()
    }

    fun scan() {
        val folder = _state.value.folderPath.trim()
        if (folder.isEmpty()) return

        _state.value = _state.value.copy(
            progress = BuildProgress(message = "Finding complete snapshots", isScanning = true),
            snapshots = emptyList(),
            selectedIndex = -1,
            titleIdText = "Scanning folder..."
        )

        viewModelScope.launch {
            try {
                val (snapshots, skipped) = GameEngine.findSnapshots(File(folder))
                _state.value = _state.value.copy(
                    snapshots = snapshots,
                    skippedFolders = skipped,
                    progress = BuildProgress()
                )
                if (snapshots.isNotEmpty()) selectSnapshot(0)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    progress = BuildProgress(
                        message = "Scan failed",
                        detail = e.message ?: "Unknown error",
                        error = e.message
                    ),
                    titleIdText = "Choose a game folder to detect the title ID."
                )
            }
        }
    }

    fun selectSnapshot(index: Int) {
        val snapshots = _state.value.snapshots
        if (index !in snapshots.indices) return
        val s = snapshots[index]

        val version = if (s.nacp != null) {
            val nacpData = s.nacp.readBytes()
            val vBytes = nacpData.copyOfRange(0x3060, 0x3070)
            val v = vBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            v.ifEmpty { if (s.version > 0) "dump-${s.version}" else "Snapshot" }
        } else {
            if (s.version > 0) "dump-${s.version}" else "Snapshot"
        }

        val safeName = s.title.replace(Regex("""[<>:"/\\|?*\x00-\x1f]"""), "_")
            .trimEnd('.', ' ').take(110)
        val defaultOutput = File(s.root.parentFile, "$safeName.nsp").absolutePath

        _state.value = _state.value.copy(
            selectedIndex = index,
            title = s.title,
            displayVersion = version,
            titleIdText = "Title ID  ${"%016X".format(s.titleId)}   ·   Snapshot version  ${s.version}",
            outputPath = defaultOutput
        )
    }

    fun updateTitle(title: String) {
        _state.value = _state.value.copy(title = title)
    }

    fun updateVersion(version: String) {
        _state.value = _state.value.copy(displayVersion = version)
    }

    fun updateOutput(path: String) {
        _state.value = _state.value.copy(outputPath = path)
    }

    fun setVerify(value: Boolean) {
        _state.value = _state.value.copy(verify = value)
    }

    fun setPackageFolder(value: Boolean) {
        _state.value = _state.value.copy(packageFolder = value)
    }

    fun setArtwork(file: File) {
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { loadBitmap(file) }
                _state.value = _state.value.copy(
                    artworkPath = file,
                    artworkBitmap = bitmap,
                    cropBox = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    progress = _state.value.progress.copy(
                        error = "Failed to load artwork: ${e.message}"
                    )
                )
            }
        }
    }

    fun setCropBox(box: CropBox) {
        _state.value = _state.value.copy(cropBox = box)
    }

    fun clearArtwork() {
        _state.value = _state.value.copy(
            artworkPath = null,
            artworkBitmap = null,
            cropBox = null
        )
    }

    fun build() {
        val s = _state.value
        val snapshot = s.snapshots.getOrNull(s.selectedIndex) ?: return
        if (s.progress.isBuilding) return

        _state.value = s.copy(
            progress = BuildProgress(message = "Starting build...", isBuilding = true)
        )

        buildJob = viewModelScope.launch {
            try {
                val artBytes = if (s.artworkPath != null && s.artworkBitmap != null) {
                    withContext(Dispatchers.IO) {
                        encodeArtwork(s.artworkBitmap, s.cropBox)
                    }
                } else null

                GameEngine.build(
                    snapshot = snapshot,
                    outputPath = File(s.outputPath),
                    title = s.title,
                    displayVersion = s.displayVersion,
                    artworkBytes = artBytes,
                    verify = s.verify,
                    notify = { msg, pct ->
                        _state.value = _state.value.copy(
                            progress = BuildProgress(
                                message = msg,
                                percent = pct,
                                isBuilding = true
                            )
                        )
                    },
                    packageFolder = s.packageFolder
                )

                _state.value = _state.value.copy(
                    progress = BuildProgress(
                        message = "Complete",
                        detail = s.outputPath,
                        percent = 100f,
                        isComplete = true
                    )
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    progress = BuildProgress(
                        message = "Build failed",
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
            progress = BuildProgress(message = "Cancelled", detail = "Build was cancelled.")
        )
    }

    private fun loadBitmap(file: File): Bitmap {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val pixels = (options.outWidth.toLong()) * options.outHeight
        require(pixels <= 50_000_000) { "Artwork is too large (maximum 50 megapixels)." }

        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 4096 ||
            options.outHeight / options.inSampleSize > 4096) {
            options.inSampleSize *= 2
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Cannot decode image file.")

        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        }
        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
    }

    companion object {
        const val ICON_SIZE = 256

        fun encodeArtwork(bitmap: Bitmap, cropBox: CropBox?): ByteArray {
            val box = cropBox ?: run {
                val side = minOf(bitmap.width, bitmap.height).toFloat()
                CropBox(
                    left = (bitmap.width - side) / 2f / bitmap.width,
                    top = (bitmap.height - side) / 2f / bitmap.height,
                    right = (bitmap.width + side) / 2f / bitmap.width,
                    bottom = (bitmap.height + side) / 2f / bitmap.height
                )
            }

            val l = (box.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val t = (box.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val r = (box.right * bitmap.width).toInt().coerceIn(l + 1, bitmap.width)
            val b = (box.bottom * bitmap.height).toInt().coerceIn(t + 1, bitmap.height)

            val cropped = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
            val scaled = Bitmap.createScaledBitmap(cropped, ICON_SIZE, ICON_SIZE, true)

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
            return out.toByteArray()
        }
    }
}
