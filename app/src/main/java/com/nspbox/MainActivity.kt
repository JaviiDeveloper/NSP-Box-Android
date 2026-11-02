package com.nspbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nspbox.data.FileAccess
import com.nspbox.data.PreferencesStore
import com.nspbox.ui.components.StartupDisclaimer
import com.nspbox.ui.screens.*
import com.nspbox.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NspBoxTheme {
                MainApp()
            }
        }
    }
}

enum class Screen { Game, Dlc, Keys, About }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.Game) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showStoragePrompt by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val shouldShow = PreferencesStore.shouldShowStartup(context).first()
        showDisclaimer = shouldShow
        showStoragePrompt = !FileAccess.hasManageStoragePermission()
    }

    if (showDisclaimer) {
        StartupDisclaimer(
            onDismiss = { showDisclaimer = false },
            onDontShowAgain = {
                showDisclaimer = false
                scope.launch { PreferencesStore.setShowStartup(context, false) }
            }
        )
    }

    if (showStoragePrompt) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Panel,
            title = { Text("Storage Permission Required", color = TextPrimary) },
            text = {
                Text(
                    "NSP Box needs access to all files to read game dumps and write NSP packages. " +
                    "Please grant the \"All files access\" permission.",
                    color = Muted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        FileAccess.requestManageStoragePermission(context)
                        showStoragePrompt = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Grant Permission") }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePrompt = false }) {
                    Text("Later", color = Muted)
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Panel,
                contentColor = TextPrimary
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
                    label = { Text("Game") },
                    selected = currentScreen == Screen.Game,
                    onClick = { currentScreen = Screen.Game },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted,
                        indicatorColor = Field
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                    label = { Text("DLC") },
                    selected = currentScreen == Screen.Dlc,
                    onClick = { currentScreen = Screen.Dlc },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted,
                        indicatorColor = Field
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Key, contentDescription = null) },
                    label = { Text("Keys") },
                    selected = currentScreen == Screen.Keys,
                    onClick = { currentScreen = Screen.Keys },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted,
                        indicatorColor = Field
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("About") },
                    selected = currentScreen == Screen.About,
                    onClick = { currentScreen = Screen.About },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted,
                        indicatorColor = Field
                    )
                )
            }
        },
        containerColor = Background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Background)
        ) {
            when (currentScreen) {
                Screen.Game -> GameScreen()
                Screen.Dlc -> DlcScreen()
                Screen.Keys -> KeySetupScreen()
                Screen.About -> AboutScreen()
            }
        }
    }
}
