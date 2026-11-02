package com.nspbox.domain.engine

import com.nspbox.domain.format.BlobWriter
import com.nspbox.domain.format.ContainerFormat
import com.nspbox.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

object GameEngine {
    const val VERSION = "3.0.0"

    private val ICON_LANGUAGES = listOf(
        "AmericanEnglish", "BritishEnglish", "Japanese", "French", "German",
        "LatinAmericanSpanish", "Spanish", "Italian", "Dutch", "CanadianFrench",
        "Portuguese", "Russian", "Korean", "TraditionalChinese", "SimplifiedChinese",
        "BrazilianPortuguese", "Polish", "Thai"
    )

    fun readTitleId(path: File): Long {
        val data = path.readBytes()
        require(data.size >= 0x80 && data[0] == 'M'.code.toByte() && data[1] == 'E'.code.toByte()
                && data[2] == 'T'.code.toByte() && data[3] == 'A'.code.toByte()) {
            "main.npdm is not a supported NPDM file."
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val offset = buf.getInt(0x70)
        val size = buf.getInt(0x74)
        require(offset >= 0x80 && offset + size <= data.size && size >= 0x20) {
            "The NPDM application metadata is incomplete or invalid."
        }
        require(data[offset] == 'A'.code.toByte() && data[offset + 1] == 'C'.code.toByte()
                && data[offset + 2] == 'I'.code.toByte() && data[offset + 3] == '0'.code.toByte()) {
            "The NPDM application metadata is incomplete or invalid."
        }
        val tid = ByteBuffer.wrap(data, offset + 0x10, 8).order(ByteOrder.LITTLE_ENDIAN).long
        require(tid != 0L) { "The NPDM has no application title ID." }
        return tid
    }

    fun cleanTitle(name: String): String {
        return name.replace(Regex("""\s*\[[^\]]*]"""), "").trim().ifEmpty { "Extracted game" }
    }

    suspend fun findSnapshots(folder: File): Pair<List<Snapshot>, List<File>> = withContext(Dispatchers.IO) {
        val resolved = folder.canonicalFile
        require(resolved.isDirectory) { "Choose an existing game folder." }
        val scanRoot = if (resolved.name.lowercase() in listOf("exefs", "romfs")) resolved.parentFile!! else resolved

        val found = mutableListOf<Snapshot>()
        val skipped = mutableListOf<File>()
        val invalid = mutableListOf<String>()

        fun scan(path: File, depth: Int) {
            if (depth > 4) return
            coroutineContext.ensureActive()

            val exefsMain = File(path, "exefs/main")
            val exefsNpdm = File(path, "exefs/main.npdm")
            val romfs = File(path, "romfs")

            if (exefsMain.isFile && exefsNpdm.isFile && romfs.isDirectory) {
                try {
                    val tid = readTitleId(exefsNpdm)
                    require(tid and 0x800L == 0L) { "NPDM identifies a patch, not a main application." }

                    val rel = path.relativeTo(scanRoot).path
                    val parts = rel.split(File.separator)
                    val nums = parts.filter { it.all { c -> c.isDigit() } }.map { it.toLong() }
                    val ver = if (nums.isNotEmpty()) nums.last() else 0L
                    require(ver in 0..0xFFFFFFFFL) { "Version number is out of range." }

                    val label = if (rel.isEmpty() || rel == ".") "Selected folder" else rel
                    var title = cleanTitle(scanRoot.name)
                    var nacp: File? = null

                    for (parent in listOf(path, File(path, "control"), scanRoot, File(scanRoot, "control"))) {
                        val nacpFile = File(parent, "control.nacp")
                        if (nacpFile.isFile && nacpFile.length() == 0x4000L) {
                            nacp = nacpFile
                            val nacpData = nacpFile.readBytes()
                            if (nacpData[0x3215].toInt() == 0) {
                                val text = nacpData.copyOfRange(0, 0x200)
                                    .takeWhile { it != 0.toByte() }
                                    .toByteArray()
                                    .toString(Charsets.UTF_8)
                                    .trim()
                                if (text.isNotEmpty()) title = text
                            }
                            break
                        }
                    }
                    found.add(Snapshot(path, tid, ver, label, title, nacp))
                } catch (e: Exception) {
                    invalid.add("$path: ${e.message}")
                }
            }

            if (depth == 4) return
            val children = path.listFiles()?.sorted() ?: return
            for (child in children) {
                if (child.name.lowercase() in listOf("romfs", "exefs", "control")) continue
                if (child.name.lowercase() in listOf("dlc", "aoc", "addons", "add_on_content")) {
                    if (child.isDirectory) skipped.add(child)
                    continue
                }
                if (child.isDirectory) scan(child, depth + 1)
            }
        }

        scan(scanRoot, 0)
        found.sortWith(compareByDescending<Snapshot> { it.version }.thenByDescending { it.label.lowercase() })

        require(found.isNotEmpty()) {
            val details = if (invalid.isNotEmpty()) "\n${invalid[0]}" else ""
            "No complete exefs + romfs snapshot found. Select the folder containing both, or its main game folder.$details"
        }

        Pair(found, skipped)
    }

    fun sourceInventory(root: File, notify: ((String, Float) -> Unit)? = null): Map<File, Pair<Long, Long>> {
        val inventory = mutableMapOf<File, Pair<Long, Long>>()

        fun scan(folder: File) {
            val children = folder.listFiles() ?: return
            for (entry in children) {
                if (entry.isDirectory) {
                    scan(entry)
                } else if (entry.isFile) {
                    inventory[entry] = Pair(entry.length(), entry.lastModified())
                    if (notify != null && inventory.size % 256 == 0) {
                        notify("Checking source files · ${inventory.size} found", 1f)
                    }
                }
            }
        }

        scan(File(root, "exefs"))
        scan(File(root, "romfs"))

        require(inventory.keys.any {
            it.absolutePath.startsWith(File(root, "romfs").absolutePath + File.separator)
        }) { "The RomFS folder is empty." }

        return inventory
    }

    private fun utf8Field(text: String, size: Int): ByteArray {
        val encoded = text.toByteArray(Charsets.UTF_8)
        val trimmed = if (encoded.size >= size) {
            // Trim to fit, avoiding cutting mid-character
            var end = size - 1
            while (end > 0 && (encoded[end].toInt() and 0xC0) == 0x80) end--
            encoded.copyOfRange(0, end)
        } else encoded
        return trimmed.copyOf(size)
    }

    fun controlData(
        snapshot: Snapshot,
        title: String,
        version: String,
        artwork: ByteArray?
    ): Pair<List<Pair<String, ByteArray>>, Boolean> {
        val existing = snapshot.nacp?.readBytes()
        val nacp = existing?.copyOf() ?: ByteArray(0x4000)
        val preserved = existing != null

        if (existing == null || nacp[0x3215].toInt() == 0) {
            val titleField = utf8Field(title, 0x200)
            for (i in 0 until 16) {
                System.arraycopy(titleField, 0, nacp, i * 0x300, 0x200)
            }
        }

        if (existing == null) {
            nacp[0x3025] = 1
            ByteBuffer.wrap(nacp).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x302C, 0xFFFF)
                putLong(0x3038, snapshot.titleId)
                putLong(0x3070, snapshot.titleId + 0x1000)
                putLong(0x3078, snapshot.titleId)
            }
            if ((snapshot.titleId ushr 56).toInt() == 4) {
                nacp[0x3214] = 3
                nacp[0x3217] = 1
            }
        }

        val versionField = utf8Field(version, 0x10)
        System.arraycopy(versionField, 0, nacp, 0x3060, 0x10)

        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries.add("control.nacp" to nacp)

        if (artwork != null) {
            for (lang in ICON_LANGUAGES) {
                entries.add("icon_$lang.dat" to artwork)
            }
        }

        return Pair(entries, preserved)
    }

    suspend fun build(
        snapshot: Snapshot,
        outputPath: File,
        title: String,
        displayVersion: String,
        artworkBytes: ByteArray? = null,
        verify: Boolean = true,
        notify: (String, Float) -> Unit = { _, _ -> },
        packageFolder: Boolean = false
    ): BuildReport = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val timings = mutableMapOf<String, Double>()
        var tick = started

        val output = if (packageFolder) {
            File(outputPath.parentFile, outputPath.nameWithoutExtension).resolve(outputPath.name)
        } else outputPath

        require(title.isNotBlank()) { "Enter a game title." }
        require(displayVersion.toByteArray(Charsets.UTF_8).size <= 15) {
            "Display version must fit within 15 UTF-8 bytes."
        }
        require(output.extension.lowercase() == "nsp") { "Output filename must end in .nsp." }

        val root = snapshot.root.canonicalFile
        require(!output.canonicalPath.startsWith(File(root, "exefs").canonicalPath) &&
                !output.canonicalPath.startsWith(File(root, "romfs").canonicalPath)) {
            "Save the NSP outside the source exefs and romfs folders."
        }
        require(!output.exists()) { "An output with that name already exists. Choose another filename." }
        require(readTitleId(File(root, "exefs/main.npdm")) == snapshot.titleId) {
            "Source title ID changed. Scan the folder again."
        }

        val isSwitch2 = (snapshot.titleId ushr 56).toInt() == 4
        val companion = if (packageFolder && isSwitch2) File(output.parentFile, "main.npdm") else null
        val companionData = if (companion != null) File(root, "exefs/main.npdm").readBytes() else null

        if (companion != null && companion.exists() && !companion.readBytes().contentEquals(companionData)) {
            throw IllegalArgumentException("A different main.npdm already exists in the output folder.")
        }

        notify("Checking source files", 1f)
        val inventory = sourceInventory(root, notify)
        timings["source_scan"] = (System.nanoTime() - tick) / 1e9
        tick = System.nanoTime()

        output.parentFile?.mkdirs()
        val estimate = (inventory.values.sumOf { it.first } * 1.02 + 32 * 1024 * 1024).toLong()
        val freeSpace = output.parentFile?.usableSpace ?: 0
        require(freeSpace >= estimate) { "Not enough free disk space for this NSP." }

        notify("Building RomFS file tables", 2f)
        val sourceManifest = mutableListOf<ContainerFormat.RomFsManifestEntry>()
        val romfsBlob = ContainerFormat.romfs(File(root, "romfs"), sourceManifest) { count ->
            notify("Building RomFS file tables · $count files", 2f)
        }

        val exefsEntries = File(root, "exefs").listFiles()
            ?.filter { it.isFile }
            ?.sorted()
            ?.map { it.name to BlobWriter(it) }
            ?: emptyList()

        require(File(root, "exefs").listFiles()?.none { it.isDirectory } != false) {
            "ExeFS must contain files directly, not nested directories."
        }

        val exefsBlob = ContainerFormat.filesystem(exefsEntries)

        notify("Building RomFS integrity tree", 2f)
        val programNca = ContainerFormat.nca(
            listOf(ContainerFormat.pfsSection(exefsBlob), ContainerFormat.romfsSection(romfsBlob)),
            snapshot.titleId, 0, 0
        )
        timings["filesystems_and_integrity_tree"] = (System.nanoTime() - tick) / 1e9
        tick = System.nanoTime()

        // Control NCA
        val (controlEntries, preserved) = controlData(snapshot, title, displayVersion, artworkBytes)
        val tempDir = File.createTempFile("nspbox-ctrl", "").apply { delete(); mkdirs() }
        try {
            for ((name, data) in controlEntries) {
                File(tempDir, name).writeBytes(data)
            }
            notify("Preparing artwork and metadata", 22f)
            val controlManifest = mutableListOf<ContainerFormat.RomFsManifestEntry>()
            val controlNca = ContainerFormat.nca(
                listOf(ContainerFormat.romfsSection(ContainerFormat.romfs(tempDir, controlManifest))),
                snapshot.titleId, 2, 0
            )

            // Content records and CNMT
            notify("Hashing game content", 23f)
            val records = mutableListOf<ByteArray>()
            val ncaEntries = mutableListOf<Pair<String, BlobWriter>>()

            for ((blob, ctype) in listOf(programNca to 1, controlNca to 3)) {
                val digest = blob.digest()
                val name = digest.take(16).joinToString("") { "%02x".format(it) } + ".nca"
                ncaEntries.add(name to blob)
                val record = ByteArray(0x38)
                System.arraycopy(digest, 0, record, 0, 32)
                System.arraycopy(digest, 0, record, 32, 16)
                val sizeBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(blob.size).array()
                System.arraycopy(sizeBytes, 0, record, 48, 6)
                record[54] = ctype.toByte()
                records.add(record)
            }

            val cnmt = ByteArray(0x20 + 16 + records.sumOf { it.size } + 32)
            ByteBuffer.wrap(cnmt).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(0, snapshot.titleId)
                putInt(8, snapshot.version.toInt())
                put(12, 0x80.toByte())
                put(13, 0)
                putShort(14, 16)
                putShort(16, records.size.toShort())
                putShort(18, 0)
            }
            // Extended header
            val extHeader = ContainerFormat.packLE("QII", snapshot.titleId + 0x800, 0, 0)
            System.arraycopy(extHeader, 0, cnmt, 0x20, extHeader.size)

            var cnmtOff = 0x20 + 16
            for (r in records) {
                System.arraycopy(r, 0, cnmt, cnmtOff, r.size)
                cnmtOff += r.size
            }
            val cnmtDigest = ContainerFormat.sha(cnmt.copyOfRange(0, cnmt.size - 32))
            System.arraycopy(cnmtDigest, 0, cnmt, cnmt.size - 32, 32)

            val cnmtFileName = "Application_${"%016x".format(snapshot.titleId)}.cnmt"
            val metaNca = ContainerFormat.nca(
                listOf(ContainerFormat.pfsSection(ContainerFormat.filesystem(listOf(cnmtFileName to BlobWriter(cnmt))))),
                snapshot.titleId, 1, 0
            )
            val metaDigest = metaNca.digest()
            ncaEntries.add(metaDigest.take(16).joinToString("") { "%02x".format(it) } + ".cnmt.nca" to metaNca)

            timings["metadata_and_content_hashing"] = (System.nanoTime() - tick) / 1e9
            tick = System.nanoTime()

            val result = ContainerFormat.buildPackage(ncaEntries)

            // Write
            val tempFile = File(output.parentFile, "${output.name}.${UUID.randomUUID().toString().take(10)}.partial")
            var committed = false
            var companionCreated = false
            try {
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                notify("Writing NSP", 45f)

                val sourceDigests = if (verify) inventory.keys.associateWith { ByteArray(0) }.toMutableMap() else null

                tempFile.outputStream().buffered().use { fos ->
                    for (block in result.chunksBlocking(4 * 1024 * 1024, sourceDigests)) {
                        fos.write(block)
                        md.update(block)
                        done += block.size
                        notify("Writing NSP", 45f + 29f * done / result.size)
                    }
                    fos.flush()
                }
                timings["writing"] = (System.nanoTime() - tick) / 1e9
                tick = System.nanoTime()

                var checked: List<FileEntry>? = null
                if (verify) {
                    notify("Verifying container integrity", 75f)
                    checked = VerifyFormat.verifyImage(tempFile, snapshot, inventory, { msg, pct ->
                        notify(msg, pct)
                    }, sourceDigests?.mapValues { it.value }.takeIf { sourceDigests != null })
                }
                timings["verification"] = (System.nanoTime() - tick) / 1e9

                // Check source files haven't changed
                for ((file, expected) in inventory) {
                    require(file.length() == expected.first && file.lastModified() == expected.second) {
                        "A source file changed during packaging. Try again with the dump idle."
                    }
                }

                // Write companion
                if (companion != null && companionData != null) {
                    companion.writeBytes(companionData)
                    companionCreated = true
                }

                tempFile.renameTo(output)
                committed = true

                val sha256Hex = md.digest().joinToString("") { "%02x".format(it) }

                val report = BuildReport(
                    version = VERSION,
                    title = title,
                    titleId = "%016X".format(snapshot.titleId),
                    snapshot = root.absolutePath,
                    snapshotVersion = snapshot.version,
                    displayVersion = displayVersion,
                    size = result.size,
                    sha256 = sha256Hex,
                    originalControlPreserved = preserved,
                    sourceFileCount = inventory.size,
                    verified = verify,
                    files = checked,
                    timings = timings.mapValues { (it.value * 1000).toLong() / 1000.0 },
                    verificationMethod = if (verify) "Source SHA-256 captured during writing; full independent output readback." else "Skipped",
                    output = output.absolutePath,
                    packageFolder = packageFolder,
                    switch2Companion = if (companion != null && companionData != null) CompanionInfo(
                        sha256 = MessageDigest.getInstance("SHA-256").digest(companionData)
                            .joinToString("") { "%02x".format(it) }
                    ) else null,
                    format = "Unsigned plaintext emulator NSP; one complete snapshot; no automatic DLC merge."
                )

                if (packageFolder) {
                    val reportPath = File(output.parentFile, "${output.name}.report.json")
                    val json = Json { prettyPrint = true }
                    reportPath.writeText(json.encodeToString(report))
                }

                notify("Complete", 100f)
                report
            } finally {
                if (!committed && tempFile.exists()) tempFile.delete()
                if (!committed && companionCreated && companion != null && companion.exists()) {
                    if (companion.readBytes().contentEquals(companionData)) companion.delete()
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Bridge for BlobWriter blocking chunks with source digest capture
    private fun BlobWriter.chunksBlocking(
        blockSize: Int,
        sourceDigests: MutableMap<File, ByteArray>?
    ): Sequence<ByteArray> = sequence {
        val pending = java.io.ByteArrayOutputStream(blockSize)
        for (part in parts) {
            when (part) {
                is com.nspbox.domain.format.BlobPart.Bytes -> {
                    var pos = 0
                    while (pos < part.data.size) {
                        val n = minOf(blockSize - pending.size(), part.data.size - pos)
                        pending.write(part.data, pos, n)
                        pos += n
                        if (pending.size() == blockSize) { yield(pending.toByteArray()); pending.reset() }
                    }
                }
                is com.nspbox.domain.format.BlobPart.FilePart -> {
                    val digest = if (sourceDigests != null && part.path in sourceDigests)
                        MessageDigest.getInstance("SHA-256") else null
                    java.io.FileInputStream(part.path).use { fis ->
                        var remaining = part.size
                        while (remaining > 0) {
                            val n = minOf((blockSize - pending.size()).toLong(), remaining).toInt()
                            val buf = ByteArray(n)
                            val read = fis.read(buf)
                            if (read != n) throw java.io.IOException("Short read")
                            digest?.update(buf, 0, read)
                            pending.write(buf, 0, read)
                            remaining -= read
                            if (pending.size() == blockSize) { yield(pending.toByteArray()); pending.reset() }
                        }
                    }
                    if (digest != null) sourceDigests!![part.path] = digest.digest()
                }
                is com.nspbox.domain.format.BlobPart.Padding -> {
                    var remaining = part.size
                    val zeroBuf = ByteArray(minOf(blockSize.toLong(), remaining).toInt())
                    while (remaining > 0) {
                        val n = minOf((blockSize - pending.size()).toLong(), remaining).toInt()
                        pending.write(zeroBuf, 0, n)
                        remaining -= n
                        if (pending.size() == blockSize) { yield(pending.toByteArray()); pending.reset() }
                    }
                }
            }
        }
        if (pending.size() > 0) yield(pending.toByteArray())
    }
}
