package com.nspbox.domain.model

import java.io.File

data class Snapshot(
    val root: File,
    val titleId: Long,
    val version: Long,
    val label: String,
    val title: String,
    val nacp: File? = null
)
