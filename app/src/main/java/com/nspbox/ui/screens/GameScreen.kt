package com.nspbox.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nspbox.ui.components.*
import com.nspbox.ui.theme.*
import com.nspbox.ui.viewmodel.GameViewModel
import java.io.File

@Composable
fun GameScreen(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var showCropDialog by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val path = getPathFromUri(uri)
            if (path != null) viewModel.setFolder(path)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val context = LocalContext
            val path = getPathFromUri(uri)
            if (path != null) viewModel.setArtwork(File(path))
        }
    }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            val path = getPathFromUri(uri)
            if (path != null) viewModel.updateOutput(path)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Folder selection
        SectionCard("Game Folder") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.folderPath,
                    onValueChange = { viewModel.setFolder(it) },
                    placeholder = { Text("Path to game dump folder", color = Muted) },
                    singleLine = true,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Default.Folder, "Browse", tint = Accent)
                }
            }
            if (state.folderPath.isNotEmpty() && state.snapshots.isEmpty() && !state.progress.isScanning) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.scan() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Scan") }
            }
        }

        // Snapshot selector
        if (state.snapshots.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard("Snapshot") {
                SnapshotSelector(
                    snapshots = state.snapshots,
                    selectedIndex = state.selectedIndex,
                    onSelect = { viewModel.selectSnapshot(it) }
                )
                Spacer(Modifier.height(8.dp))
                Text(state.titleIdText, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Title and version
        if (state.selectedIndex >= 0) {
            Spacer(Modifier.height(12.dp))
            SectionCard("Metadata") {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text("Game Title", color = Muted) },
                    singleLine = true,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.displayVersion,
                    onValueChange = { viewModel.updateVersion(it) },
                    label = { Text("Display Version", color = Muted) },
                    singleLine = true,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Artwork
            Spacer(Modifier.height(12.dp))
            SectionCard("Artwork") {
                ArtworkPicker(
                    artworkPath = state.artworkPath,
                    previewBitmap = state.artworkBitmap,
                    onPickArtwork = { imagePicker.launch("image/*") },
                    onCropArtwork = { showCropDialog = true },
                    onClearArtwork = { viewModel.clearArtwork() }
                )
            }

            // Output
            Spacer(Modifier.height(12.dp))
            SectionCard("Output") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.outputPath,
                        onValueChange = { viewModel.updateOutput(it) },
                        label = { Text("Output NSP path", color = Muted) },
                        singleLine = true,
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { outputPicker.launch("game.nsp") }) {
                        Icon(Icons.Default.Save, "Save as", tint = Accent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Checkbox(
                            checked = state.verify,
                            onCheckedChange = { viewModel.setVerify(it) },
                            colors = CheckboxDefaults.colors(checkedColor = Accent)
                        )
                        Text("Verify after building", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Checkbox(
                            checked = state.packageFolder,
                            onCheckedChange = { viewModel.setPackageFolder(it) },
                            colors = CheckboxDefaults.colors(checkedColor = Accent)
                        )
                        Text("Package folder", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                }
            }

            // Build
            Spacer(Modifier.height(16.dp))
            BuildButton(
                progress = state.progress,
                onBuild = { viewModel.build() },
                onCancel = { viewModel.cancelBuild() },
                enabled = state.selectedIndex >= 0 && state.outputPath.isNotEmpty()
            )
        }

        // Status
        if (state.progress.error != null || state.progress.isScanning) {
            Spacer(Modifier.height(12.dp))
            StatusBar(state.progress.message, state.progress.detail)
        }
    }

    // Crop dialog
    if (showCropDialog && state.artworkBitmap != null) {
        CropDialog(
            bitmap = state.artworkBitmap!!,
            initialBox = state.cropBox,
            onApply = { box ->
                viewModel.setCropBox(box)
                showCropDialog = false
            },
            onDismiss = { showCropDialog = false }
        )
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .border(1.dp, Border, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Field,
    unfocusedContainerColor = Field,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Border,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Accent,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted
)

fun getPathFromUri(uri: Uri): String? {
    val path = uri.path ?: return null
    // Try to extract real path from tree URI
    if (path.contains("/tree/")) {
        val treePart = path.substringAfter("/tree/")
        val decoded = Uri.decode(treePart)
        return if (decoded.startsWith("primary:")) {
            "/storage/emulated/0/" + decoded.removePrefix("primary:")
        } else if (decoded.contains(":")) {
            val volume = decoded.substringBefore(":")
            val relativePath = decoded.substringAfter(":")
            "/storage/$volume/$relativePath"
        } else decoded
    }
    // Document URI
    if (path.contains("/document/")) {
        val docPart = path.substringAfter("/document/")
        val decoded = Uri.decode(docPart)
        return if (decoded.startsWith("primary:")) {
            "/storage/emulated/0/" + decoded.removePrefix("primary:")
        } else decoded
    }
    return path
}
