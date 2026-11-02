package com.nspbox.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nspbox.ui.theme.*

@Composable
fun StartupDisclaimer(
    onDismiss: () -> Unit,
    onDontShowAgain: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                "Experimental Software",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "NSP Box packages extracted game snapshots into plaintext NSP files for compatible emulators.\n\n" +
                    "Set emulator memory to 12 GiB before testing Switch 2 packages. " +
                    "The app does not change this setting.\n\n" +
                    "The emulator patch is optional and only applies to recognized compatible code; " +
                    "it cannot add Switch 2 emulation to an unsupported emulator.\n\n" +
                    "Piracy is not supported. Use only files and artwork you have lawfully obtained " +
                    "and are authorized to use. No games, game artwork, firmware or Nintendo keys are provided.\n\n" +
                    "This is an independent project, not affiliated with Nintendo.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("I Understand")
            }
        },
        dismissButton = {
            TextButton(onClick = onDontShowAgain) {
                Text("Don't show again", color = Muted)
            }
        }
    )
}
