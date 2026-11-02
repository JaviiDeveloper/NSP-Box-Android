package com.nspbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nspbox.domain.model.BuildProgress
import com.nspbox.ui.theme.*

@Composable
fun BuildButton(
    progress: BuildProgress,
    onBuild: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (progress.isBuilding) {
            val animatedProgress by animateFloatAsState(
                targetValue = progress.percent / 100f,
                label = "progress"
            )
            @Suppress("DEPRECATION")
            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .padding(bottom = 4.dp),
                color = Accent,
                trackColor = Field
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    progress.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (progress.error != null) ErrorRed
                    else if (progress.isComplete) SuccessGreen
                    else TextPrimary
                )
                if (progress.detail.isNotEmpty()) {
                    Text(
                        progress.detail,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            if (progress.isBuilding) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                ) {
                    Text("Cancel")
                }
            } else {
                Button(
                    onClick = onBuild,
                    enabled = enabled && !progress.isScanning,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        disabledContainerColor = Field
                    )
                ) {
                    Text("Build NSP")
                }
            }
        }
    }
}
