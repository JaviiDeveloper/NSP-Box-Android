package com.nspbox.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nspbox.domain.model.CropBox
import com.nspbox.ui.theme.*
import kotlin.math.max
import kotlin.math.min

@Composable
fun CropDialog(
    bitmap: Bitmap,
    initialBox: CropBox?,
    onApply: (CropBox) -> Unit,
    onDismiss: () -> Unit
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(bitmap.width / 2f) }
    var cy by remember { mutableFloatStateOf(bitmap.height / 2f) }

    LaunchedEffect(initialBox) {
        if (initialBox != null) {
            cx = (initialBox.left + initialBox.right) * bitmap.width / 2f
            cy = (initialBox.top + initialBox.bottom) * bitmap.height / 2f
            val side = (initialBox.right - initialBox.left) * bitmap.width
            zoom = if (side > 0) min(bitmap.width.toFloat(), bitmap.height.toFloat()) / side else 1f
        }
    }

    fun clamp() {
        zoom = zoom.coerceIn(1f, 8f)
        val side = min(bitmap.width.toFloat(), bitmap.height.toFloat()) / zoom
        val half = side / 2f
        cx = cx.coerceIn(half, bitmap.width - half)
        cy = cy.coerceIn(half, bitmap.height - half)
    }

    fun currentBox(): CropBox {
        clamp()
        val side = min(bitmap.width.toFloat(), bitmap.height.toFloat()) / zoom
        val half = side / 2f
        return CropBox(
            left = (cx - half) / bitmap.width,
            top = (cy - half) / bitmap.height,
            right = (cx + half) / bitmap.width,
            bottom = (cy + half) / bitmap.height
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Panel,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Crop Icon",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val imageBitmap = remember { bitmap.asImageBitmap() }

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Background, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val scale = size.width.toFloat() / bitmap.width
                                cx -= pan.x / scale
                                cy -= pan.y / scale
                                zoom *= gestureZoom
                                clamp()
                            }
                        }
                ) {
                    val canvasW = size.width
                    val canvasH = size.height
                    val scale = min(canvasW / bitmap.width, canvasH / bitmap.height)
                    val imgW = bitmap.width * scale
                    val imgH = bitmap.height * scale
                    val offsetX = (canvasW - imgW) / 2f
                    val offsetY = (canvasH - imgH) / 2f

                    drawImage(
                        imageBitmap,
                        dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                        dstSize = IntSize(imgW.toInt(), imgH.toInt())
                    )

                    val box = currentBox()
                    val cropLeft = offsetX + box.left * imgW
                    val cropTop = offsetY + box.top * imgH
                    val cropRight = offsetX + box.right * imgW
                    val cropBottom = offsetY + box.bottom * imgH

                    // Dim outside crop area
                    drawRect(Color.Black.copy(alpha = 0.5f), Offset.Zero, Size(canvasW, cropTop))
                    drawRect(Color.Black.copy(alpha = 0.5f), Offset(0f, cropBottom), Size(canvasW, canvasH - cropBottom))
                    drawRect(Color.Black.copy(alpha = 0.5f), Offset(0f, cropTop), Size(cropLeft, cropBottom - cropTop))
                    drawRect(Color.Black.copy(alpha = 0.5f), Offset(cropRight, cropTop), Size(canvasW - cropRight, cropBottom - cropTop))

                    // Crop border
                    drawRect(
                        Color.White,
                        Offset(cropLeft, cropTop),
                        Size(cropRight - cropLeft, cropBottom - cropTop),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2f)
                    )
                }

                // Zoom slider
                Text("Zoom", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it; clamp() },
                    valueRange = 1f..8f,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    modifier = Modifier.fillMaxWidth()
                )

                // Preview
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    val box = currentBox()
                    val previewBmp = remember(box) {
                        val l = (box.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                        val t = (box.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                        val r = (box.right * bitmap.width).toInt().coerceIn(l + 1, bitmap.width)
                        val b = (box.bottom * bitmap.height).toInt().coerceIn(t + 1, bitmap.height)
                        Bitmap.createScaledBitmap(
                            Bitmap.createBitmap(bitmap, l, t, r - l, b - t),
                            64, 64, true
                        )
                    }
                    Canvas(modifier = Modifier.size(64.dp)) {
                        drawImage(previewBmp.asImageBitmap())
                    }

                    Spacer(Modifier.weight(1f))

                    TextButton(onClick = {
                        zoom = 1f; cx = bitmap.width / 2f; cy = bitmap.height / 2f; clamp()
                    }) { Text("Reset") }

                    TextButton(onClick = onDismiss) { Text("Cancel") }

                    Button(
                        onClick = { onApply(currentBox()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("Apply") }
                }
            }
        }
    }
}
