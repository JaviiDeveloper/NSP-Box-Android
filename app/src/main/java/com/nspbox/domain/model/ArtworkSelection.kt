package com.nspbox.domain.model

import java.io.File

data class ArtworkSelection(
    val path: File,
    val box: CropBox? = null,
    val fit: Boolean = false
)

data class CropBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
