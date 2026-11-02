package com.nspbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nspbox.domain.model.Snapshot
import com.nspbox.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotSelector(
    snapshots: List<Snapshot>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = if (selectedIndex >= 0 && selectedIndex < snapshots.size)
                snapshots[selectedIndex].label else "Select a snapshot",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Field,
                unfocusedContainerColor = Field,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Panel)
        ) {
            snapshots.forEachIndexed { index, snapshot ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(snapshot.label, color = TextPrimary)
                            Text(
                                "v${snapshot.version} · ${"%016X".format(snapshot.titleId)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}
