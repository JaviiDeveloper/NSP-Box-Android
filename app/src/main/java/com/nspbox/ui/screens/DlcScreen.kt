package com.nspbox.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nspbox.ui.components.BuildButton
import com.nspbox.ui.components.StatusBar
import com.nspbox.ui.theme.*
import com.nspbox.ui.viewmodel.DlcViewModel

@Composable
fun DlcScreen(viewModel: DlcViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.setFolder(getPathFromUri(uri) ?: return@let) }
    }

    val baseGamePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.setBaseGamePath(getPathFromUri(uri) ?: return@let) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // DLC folder
        SectionCard("DLC Folder") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.folderPath,
                    onValueChange = { viewModel.setFolder(it) },
                    placeholder = { Text("Path to extracted DLC folder", color = Muted) },
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

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.titleIdOverride,
                onValueChange = { viewModel.setTitleIdOverride(it) },
                label = { Text("DLC Title ID (optional)", color = Muted) },
                placeholder = { Text("e.g. 0100F2C0115B6001", color = Muted) },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.scan() },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Scan") }
        }

        // Snapshot list
        if (state.snapshots.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard("DLC Snapshots") {
                state.snapshots.forEachIndexed { index, snap ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = index == state.selectedIndex,
                            onClick = { viewModel.selectSnapshot(index) },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(snap.label, color = TextPrimary)
                            Text(
                                "ID: ${"%016X".format(snap.titleId)} · v${snap.version}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // Base game validation (optional)
        if (state.selectedIndex >= 0) {
            Spacer(Modifier.height(12.dp))
            SectionCard("Base Game (Optional)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.baseGamePath,
                        onValueChange = { viewModel.setBaseGamePath(it) },
                        placeholder = { Text("Path to base game NSP", color = Muted) },
                        singleLine = true,
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { baseGamePicker.launch("application/octet-stream") }) {
                        Icon(Icons.Default.Folder, "Browse", tint = Accent)
                    }
                }
                Text(
                    "Optional: validates DLC matches the base game",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Output
            Spacer(Modifier.height(12.dp))
            SectionCard("Output") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.outputPath,
                        onValueChange = { viewModel.updateOutput(it) },
                        label = { Text("Output DLC NSP path", color = Muted) },
                        singleLine = true,
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { /* Could use SAF create */ }) {
                        Icon(Icons.Default.Save, "Save as", tint = Accent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.verify,
                        onCheckedChange = { viewModel.setVerify(it) },
                        colors = CheckboxDefaults.colors(checkedColor = Accent)
                    )
                    Text("Verify after building", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
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

        if (state.progress.error != null || state.progress.isScanning) {
            Spacer(Modifier.height(12.dp))
            StatusBar(state.progress.message, state.progress.detail)
        }
    }
}
