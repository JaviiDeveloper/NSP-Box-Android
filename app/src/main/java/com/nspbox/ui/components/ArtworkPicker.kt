package com.nspbox.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nspbox.ui.theme.*
import java.io.File

@Composable
fun ArtworkPicker(
    artworkPath: File?,
    previewBitmap: Bitmap?,
    onPickArtwork: () -> Unit,
    onCropArtwork: () -> Unit,
    onClearArtwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            "Cover Artwork",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Preview
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Field)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .clickable { onPickArtwork() }
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Artwork preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add artwork",
                        tint = Muted,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (artworkPath != null) {
                    Text(
                        artworkPath.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        "256×256 JPEG icon",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "No artwork selected",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Tap to browse or use the crop editor",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (artworkPath != null) {
                IconButton(onClick = onCropArtwork) {
                    Icon(Icons.Default.Crop, "Crop", tint = Accent)
                }
                IconButton(onClick = onClearArtwork) {
                    Icon(Icons.Default.Close, "Remove", tint = Muted)
                }
            }
        }
    }
}
