package com.nspbox.domain.model

import java.io.File

data class DlcSnapshot(
    val root: File,
    val titleId: Long,
    val version: Long,
    val label: String
) {
    val baseId: Long get() = titleId and -0x2000L // 0xffffffffffffe000
}
