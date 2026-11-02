package com.nspbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nspbox.domain.keys.PlaceholderKeys
import com.nspbox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun KeySetupScreen() {
    var keysDirectory by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var inspectResult by remember { mutableStateOf<Map<String, Any?>?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Emulator Key Setup",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            "Some emulator builds require key entries even when NCA headers and sections are plaintext. " +
            "This setup adds locally generated placeholders, not Nintendo keys. " +
            "They cannot decrypt retail content.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        SectionCard("Keys Directory") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = keysDirectory,
                    onValueChange = { keysDirectory = it },
                    placeholder = { Text("Path to emulator keys folder", color = Muted) },
                    singleLine = true,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { /* folder picker */ }) {
                    Icon(Icons.Default.Folder, "Browse", tint = Accent)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    PlaceholderKeys.inspect(File(keysDirectory))
                                }
                                inspectResult = result
                                @Suppress("UNCHECKED_CAST")
                                val missing = result["missing"] as List<String>
                                @Suppress("UNCHECKED_CAST")
                                val invalid = result["invalid"] as List<String>
                                statusIsError = false
                                statusMessage = when {
                                    result["ready"] == true -> "All placeholder keys are present."
                                    missing.isNotEmpty() -> "Missing keys: ${missing.joinToString(", ")}"
                                    invalid.isNotEmpty() -> "Invalid entries: ${invalid.joinToString(", ")}"
                                    else -> "Check complete."
                                }
                            } catch (e: Exception) {
                                statusIsError = true
                                statusMessage = e.message ?: "Check failed"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                    enabled = keysDirectory.isNotEmpty()
                ) { Text("Check") }

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    PlaceholderKeys.install(File(keysDirectory))
                                }
                                @Suppress("UNCHECKED_CAST")
                                val added = result["added"] as List<String>
                                statusIsError = false
                                statusMessage = if (added.isEmpty()) {
                                    "All placeholder keys were already present."
                                } else {
                                    "Added: ${added.joinToString(", ")}. Restart the emulator."
                                }
                                inspectResult = result
                            } catch (e: Exception) {
                                statusIsError = true
                                statusMessage = e.message ?: "Setup failed"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                    enabled = keysDirectory.isNotEmpty()
                ) { Text("Install") }
            }
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard("Status") {
                Text(
                    statusMessage,
                    color = if (statusIsError) ErrorRed else SuccessGreen,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard("Export Keys") {
            Text(
                "Export placeholder key text for manual setup.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
            Spacer(Modifier.height(8.dp))
            var exportText by remember { mutableStateOf("") }
            Button(
                onClick = { exportText = PlaceholderKeys.placeholderText() },
                colors = ButtonDefaults.buttonColors(containerColor = Field),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Show Keys", color = TextPrimary) }

            if (exportText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    exportText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    modifier = Modifier
                        .background(Field, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}
