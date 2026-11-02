package com.nspbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nspbox.domain.engine.GameEngine
import com.nspbox.ui.theme.*

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "NSP Box ${GameEngine.VERSION}",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        InfoSection("About") {
            "A portable app that packages a complete, already-extracted ExeFS/RomFS snapshot " +
            "into a plaintext NSP for compatible emulators. Choose a folder, add your own artwork, " +
            "and build an NSP."
        }

        InfoSection("Notes & Compatibility") {
            "Set emulator memory to 12 GiB before testing Switch 2 packages. " +
            "The app does not change this setting.\n\n" +
            "Without the emulator patch, retain the exact game's external main.npdm " +
            "in its own package folder. Selecting 12 GiB alone can boot with Switch 1 " +
            "timing and reduced performance.\n\n" +
            "NSP output streams directly to its destination without an intermediate container. " +
            "The app includes square artwork cropping, progress/cancellation, and full output verification.\n\n" +
            "Packages up to 32 GiB are supported."
        }

        InfoSection("Accepted Folder Layouts") {
            "Direct snapshot:\n" +
            "  My Game/\n" +
            "    exefs/\n" +
            "      main\n" +
            "      main.npdm\n" +
            "    romfs/\n\n" +
            "Main folder with snapshots:\n" +
            "  My Game/\n" +
            "    base/\n" +
            "      exefs/...\n" +
            "      romfs/...\n" +
            "    updates/\n" +
            "      65536/\n" +
            "        exefs/...\n" +
            "        romfs/..."
        }

        InfoSection("Disclaimer") {
            "Piracy is not supported. Use only files and artwork you have lawfully obtained " +
            "and are authorized to use. No games, game artwork, firmware or Nintendo keys are provided. " +
            "Nintendo Switch, Nintendo Switch 2 and related marks belong to Nintendo; " +
            "game content belongs to its respective rights holders.\n\n" +
            "This is an independent project, not affiliated with, sponsored by or endorsed by Nintendo."
        }

        InfoSection("License") {
            "Original application code released under the MIT license. " +
            "Third-party components retain their own licenses. " +
            "This software was developed with AI assistance."
        }
    }
}

@Composable
private fun InfoSection(title: String, content: () -> String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    Text(
        content(),
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
}
