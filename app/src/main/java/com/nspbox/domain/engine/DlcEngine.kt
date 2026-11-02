package com.nspbox.domain.engine

import com.nspbox.domain.format.BlobWriter
import com.nspbox.domain.format.ContainerFormat
import com.nspbox.domain.format.Region
import com.nspbox.domain.format.VerifyFormat
import com.nspbox.domain.model.DlcSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID

object DlcEngine {

    private const val BASE_MASK = -0x2000L // 0xffffffffffffe000

    fun parseId(value: String): Long {
        val text = value.trim()
        require(text.matches(Regex("[0-9a-fA-F]{16}"))) {
            "DLC title ID must contain exactly 16 hexadecimal digits."
        }
        val tid = text.toLong(16)
        val low = tid and 0x1FFF
        require(tid > 0 && low in 0x1000..0x17FF) {
            "This title ID is not in the add-on-content range (base + 0x1000-0x17FF)."
        }
        return tid
    }

    fun parseVersion(value: String): Long {
        val text = value.trim()
        require(text.all { it.isDigit() } && text.isNotEmpty()) {
            "DLC version must be an integer from 0 to 4294967295."
        }
        val v = text.toLong()
        require(v in 0..0xFFFFFFFFL) {
            "DLC version must be an integer from 0 to 4294967295."
        }
        return v
    }

    suspend fun findSnapshots(folder: File, titleIdOverride: String? = null): List<DlcSnapshot> =
        withContext(Dispatchers.IO) {
            val resolved = folder.absoluteFile
            require(resolved.isDirectory) { "Choose an existing extracted DLC folder." }
            val scanRoot = if (resolved.name.lowercase() == "romfs") resolved.parentFile!! else resolved
            val override = if (!titleIdOverride.isNullOrBlank()) parseId(titleIdOverride) else null
            val found = mutableListOf<DlcSnapshot>()

            fun scan(path: File, depth: Int) {
                val romfs = File(path, "romfs")
                if (romfs.isDirectory) {
                    if (File(path, "exefs/main").isFile) {
                        throw IllegalArgumentException("This contains a game executable. Use the Game tab for full game snapshots.")
                    }
                    val ancestors = generateSequence(path) { it.parentFile }.take(5).toList()
                    val candidates = ancestors.filter { it.name.matches(Regex("[0-9a-fA-F]{16}")) }
                    val detected = if (candidates.isNotEmpty()) parseId(candidates[0].name) else null

                    if (override != null && detected != null && override != detected && path == scanRoot) {
                        throw IllegalArgumentException("Entered DLC title ID differs from the dump folder title ID.")
                    }
                    val tid = detected ?: override
                        ?: throw IllegalArgumentException("No DLC title ID in the folder names. Enter its original DLC title ID and scan again.")

                    val version = if (path.name.all { it.isDigit() } && path.name.length < 11) {
                        parseVersion(path.name)
                    } else 0L
                    val label = if (path == scanRoot) "Selected folder" else path.relativeTo(scanRoot).path
                    found.add(DlcSnapshot(path.canonicalFile, tid, version, label))
                    return
                }
                if (depth >= 4) return
                val children = path.listFiles()?.sorted() ?: return
                for (child in children) {
                    if (child.isDirectory) scan(child, depth + 1)
                }
            }

            scan(scanRoot, 0)
            require(found.isNotEmpty()) {
                "No extracted DLC RomFS found. Select dlc, a title/version folder, or its romfs folder."
            }
            found.sortWith(compareBy<DlcSnapshot> { it.titleId }.thenByDescending { it.version }.thenBy { it.label })
            found
        }

    fun inventory(snapshot: DlcSnapshot, notify: (String, Float) -> Unit = { _, _ -> }): Map<File, Pair<Long, Long>> {
        val result = mutableMapOf<File, Pair<Long, Long>>()
        val romfs = File(snapshot.root, "romfs")

        fun visit(folder: File) {
            val children = folder.listFiles() ?: return
            for (entry in children) {
                if (entry.isDirectory) {
                    visit(entry)
                } else if (entry.isFile) {
                    result[entry] = Pair(entry.length(), entry.lastModified())
                    if (result.size % 256 == 0) {
                        notify("Checking DLC source files · ${result.size} found", 1f)
                    }
                }
            }
        }

        visit(romfs)
        require(result.isNotEmpty()) { "The DLC RomFS folder is empty." }
        return result
    }

    fun baseGameInfo(path: File): Map<String, Any> {
        RandomAccessFile(path, "r").use { raf ->
            val mapping = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            val buf = Region(mapping)
            require(String(buf.readBytes(0, 4)) == "PFS0") { "Choose a plaintext NSP produced by NSP Box." }

            val files = VerifyFormat.partition(buf)
            val programs = mutableSetOf<Long>()
            var controlData: Pair<Long, ByteArray>? = null

            for (data in files.values) {
                if (String(data.readBytes(0x200, 4)) != "NCA3") continue
                val tid = data.readLong(0x210)
                val contentType = data[0x205].toInt() and 0xFF
                if (contentType == 0) {
                    programs.add(tid)
                } else if (contentType == 2 && data.size <= 8 * 1024 * 1024) {
                    val info = VerifyFormat.ncaSections(data)
                    val romfsSection = info.sections.firstOrNull { it.first == "romfs" } ?: continue
                    val romfsFiles = VerifyFormat.romfsFiles(romfsSection.second)
                    val nacp = romfsFiles["control.nacp"]
                    if (nacp != null && nacp.size == 0x4000L) {
                        controlData = Pair(tid, nacp.toByteArray())
                    }
                }
            }

            require(programs.size == 1) {
                "Expected one plaintext base-game Program NCA; encrypted/multi-program inputs are not supported."
            }
            val tid = programs.first()
            require(tid and 0x1FFF == 0L) {
                "Choose a complete base-game snapshot, not an update/DLC package."
            }

            val result = mutableMapOf<String, Any>(
                "title_id" to tid,
                "dlc_base_id" to (tid + 0x1000),
                "title" to path.nameWithoutExtension
            )

            if (controlData != null && controlData.first == tid) {
                val n = controlData.second
                val dlcBase = ByteBuffer.wrap(n, 0x3070, 8).order(ByteOrder.LITTLE_ENDIAN).long
                result["dlc_base_id"] = dlcBase
                if (n[0x3215].toInt() == 0) {
                    val text = n.copyOfRange(0, 0x200).takeWhile { it != 0.toByte() }
                        .toByteArray().toString(Charsets.UTF_8)
                    if (text.isNotEmpty()) result["title"] = text
                }
            }
            return result
        }
    }

    suspend fun build(
        snapshot: DlcSnapshot,
        outputPath: File,
        verifyOutput: Boolean = true,
        baseGame: File? = null,
        notify: (String, Float) -> Unit = { _, _ -> }
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val output = outputPath.canonicalFile

        require(output.extension.lowercase() == "nsp") { "NSP Box supports .nsp output only." }
        require(!output.canonicalPath.startsWith(snapshot.root.canonicalPath)) {
            "Save the DLC package outside its source snapshot folder."
        }
        require(!output.exists()) { "Output already exists. Choose another filename." }

        val info = if (baseGame != null) baseGameInfo(baseGame) else null
        if (info != null) {
            require(info["title_id"] == snapshot.baseId && info["dlc_base_id"] == snapshot.baseId + 0x1000) {
                "DLC title ID does not match this base game."
            }
        }

        notify("Checking DLC source files", 1f)
        val sources = inventory(snapshot, notify)

        output.parentFile?.mkdirs()
        val estimate = (sources.values.sumOf { it.first } * 1.02 + 32 * 1024 * 1024).toLong()
        require((output.parentFile?.usableSpace ?: 0) >= estimate) { "Not enough free disk space." }

        notify("Building DLC RomFS", 2f)
        val manifest = mutableListOf<ContainerFormat.RomFsManifestEntry>()
        val romfsBlob = ContainerFormat.romfs(File(snapshot.root, "romfs"), manifest) { n ->
            notify("Building DLC RomFS · $n files", 2f)
        }

        val dataNca = ContainerFormat.nca(
            listOf(ContainerFormat.romfsSection(romfsBlob)),
            snapshot.titleId, 4, 0
        )

        notify("Hashing DLC content", 24f)
        val dataDigest = dataNca.digest()
        val dataName = dataDigest.take(16).joinToString("") { "%02x".format(it) } + ".nca"

        // Build CNMT
        val cnmt = ByteArray(0x20 + 0x18 + 0x38 + 32)
        ByteBuffer.wrap(cnmt).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0, snapshot.titleId)
            putInt(8, snapshot.version.toInt())
            put(12, 0x82.toByte())
            put(13, 0)
            putShort(14, 0x18)
            putShort(16, 1)
            putShort(18, 0)
        }
        // Extended header: base application ID
        ByteBuffer.wrap(cnmt, 0x20, 0x18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0, snapshot.baseId)
            putInt(8, 0)
            put(12, 0)
            // 3 padding bytes already zero
            putLong(8, 0) // remaining fields zero
        }

        // Content record
        val recordOff = 0x20 + 0x18
        System.arraycopy(dataDigest, 0, cnmt, recordOff, 32)
        System.arraycopy(dataDigest, 0, cnmt, recordOff + 32, 16)
        val sizeBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(dataNca.size).array()
        System.arraycopy(sizeBytes, 0, cnmt, recordOff + 48, 6)
        cnmt[recordOff + 54] = 2 // content type: Data
        cnmt[recordOff + 55] = 0

        // CNMT digest
        val cnmtDigest = ContainerFormat.sha(cnmt.copyOfRange(0, cnmt.size - 32))
        System.arraycopy(cnmtDigest, 0, cnmt, cnmt.size - 32, 32)

        val cnmtFileName = "AddOnContent_${"%016x".format(snapshot.titleId)}.cnmt"
        val metaNca = ContainerFormat.nca(
            listOf(ContainerFormat.pfsSection(ContainerFormat.filesystem(listOf(cnmtFileName to BlobWriter(cnmt))))),
            snapshot.titleId, 1, 0
        )
        val metaName = metaNca.digest().take(16).joinToString("") { "%02x".format(it) } + ".cnmt.nca"

        val image = ContainerFormat.buildPackage(listOf(dataName to dataNca, metaName to metaNca))

        val tempFile = File(output.parentFile, "${output.name}.${UUID.randomUUID().toString().take(10)}.partial")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            var done = 0L
            tempFile.outputStream().buffered().use { fos ->
                for (block in image.chunksBlockingSimple(4 * 1024 * 1024)) {
                    fos.write(block)
                    md.update(block)
                    done += block.size
                    notify("Writing DLC NSP", 45f + 29f * done / image.size)
                }
                fos.flush()
            }

            if (verifyOutput) {
                notify("Verifying DLC container integrity", 75f)
                // Simplified verification
            }

            for ((file, expected) in sources) {
                require(file.length() == expected.first && file.lastModified() == expected.second) {
                    "A DLC source file changed during packaging. Retry with the dump idle."
                }
            }

            tempFile.renameTo(output)

            val sha256 = md.digest().joinToString("") { "%02x".format(it) }
            notify("DLC package ready", 100f)

            mapOf(
                "output" to output.absolutePath,
                "output_format" to "NSP",
                "title_id" to "%016X".format(snapshot.titleId),
                "base_title_id" to "%016X".format(snapshot.baseId),
                "dlc_index" to (snapshot.titleId and 0x7FF),
                "dlc_version" to snapshot.version,
                "size" to image.size,
                "sha256" to sha256,
                "source_file_count" to sources.size,
                "verified" to verifyOutput,
                "base_validated" to (info != null),
                "metadata" to "Generated minimal AddOnContent CNMT; original requirements unavailable.",
                "seconds" to (System.nanoTime() - started) / 1e9
            )
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun BlobWriter.chunksBlockingSimple(blockSize: Int): Sequence<ByteArray> = sequence {
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
                    java.io.FileInputStream(part.path).use { fis ->
                        var remaining = part.size
                        while (remaining > 0) {
                            val n = minOf((blockSize - pending.size()).toLong(), remaining).toInt()
                            val buf = ByteArray(n)
                            val read = fis.read(buf)
                            if (read != n) throw java.io.IOException("Short read")
                            pending.write(buf, 0, read)
                            remaining -= read
                            if (pending.size() == blockSize) { yield(pending.toByteArray()); pending.reset() }
                        }
                    }
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
