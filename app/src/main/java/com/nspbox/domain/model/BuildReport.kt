package com.nspbox.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuildReport(
    val app: String = "NSP Box",
    val version: String,
    val title: String,
    @SerialName("title_id") val titleId: String,
    val snapshot: String,
    @SerialName("snapshot_version") val snapshotVersion: Long,
    @SerialName("display_version") val displayVersion: String,
    val size: Long,
    val sha256: String,
    @SerialName("original_control_preserved") val originalControlPreserved: Boolean,
    @SerialName("source_file_count") val sourceFileCount: Int,
    val verified: Boolean,
    val files: List<FileEntry>? = null,
    @SerialName("timings_seconds") val timings: Map<String, Double> = emptyMap(),
    @SerialName("verification_method") val verificationMethod: String,
    @SerialName("output_format") val outputFormat: String = "NSP",
    val output: String,
    @SerialName("package_folder") val packageFolder: Boolean,
    @SerialName("switch2_companion") val switch2Companion: CompanionInfo? = null,
    val format: String
)

@Serializable
data class FileEntry(
    val path: String,
    val size: Long,
    val sha256: String
)

@Serializable
data class CompanionInfo(
    val file: String = "main.npdm",
    val sha256: String,
    val purpose: String = "Keep beside the package for Eglantine/PokeDinner Switch 2 memory/clock detection."
)
