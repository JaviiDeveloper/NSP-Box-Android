package com.nspbox.domain.format

import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object ContainerFormat {

    const val EMPTY = 0xFFFFFFFFL
    const val EMPTY_INT = -1 // 0xFFFFFFFF as Int

    fun align(n: Long, a: Long): Long = (n + a - 1) / a * a

    fun sha(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun packLE(format: String, vararg values: Any): ByteArray {
        var totalSize = 0
        for (c in format) {
            totalSize += when (c) {
                'B' -> 1; 'H' -> 2; 'I' -> 4; 'Q' -> 8; else -> 0
            }
        }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        var vi = 0
        for (c in format) {
            when (c) {
                'B' -> buf.put((values[vi++] as Number).toByte())
                'H' -> buf.putShort((values[vi++] as Number).toShort())
                'I' -> buf.putInt((values[vi++] as Number).toInt())
                'Q' -> buf.putLong((values[vi++] as Number).toLong())
            }
        }
        return buf.array()
    }

    // --- PFS0/HFS0 filesystem ---

    fun filesystem(entries: List<Pair<String, BlobWriter>>, hfs: Boolean = false): BlobWriter {
        val entrySize = if (hfs) 0x40 else 0x18
        val nameBytes = entries.map { it.first.toByteArray(Charsets.UTF_8) + byteArrayOf(0) }
        val tableBytes = nameBytes.fold(byteArrayOf()) { acc, b -> acc + b }
        val baseLen = 0x10 + entrySize * entries.size + tableBytes.size
        val alignTo = if (hfs) 0x200L else 0x10L
        val paddedTableSize = tableBytes.size + (align(baseLen.toLong(), alignTo) - baseLen).toInt()
        val paddedTable = tableBytes.copyOf(paddedTableSize)

        val magic = if (hfs) "HFS0" else "PFS0"
        val hdr = java.io.ByteArrayOutputStream()
        hdr.write(packLE("4BIH", magic[0].code, magic[1].code, magic[2].code, magic[3].code,
            entries.size, paddedTableSize, 0))

        // Wait, the pack format for 4sIII needs special handling.
        // Let me redo: header is magic(4) + count(4) + string_table_size(4) + reserved(4) = 16 bytes
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        header.put(magic.toByteArray(Charsets.US_ASCII))
        header.putInt(entries.size)
        header.putInt(paddedTableSize)
        header.putInt(0)

        val entryHeaders = java.io.ByteArrayOutputStream()
        val bodyParts = mutableListOf<Any>()
        var nameOff = 0
        var dataOff = 0L

        for ((i, entry) in entries.withIndex()) {
            val (name, blob) = entry
            if (hfs) {
                val hashedSize = minOf(blob.size, 0x200L)
                val headBytes = blob.readHead(hashedSize.toInt())
                val headHash = sha(headBytes)
                val eb = ByteBuffer.allocate(0x40).order(ByteOrder.LITTLE_ENDIAN)
                eb.putLong(dataOff)
                eb.putLong(blob.size)
                eb.putInt(nameOff)
                eb.putInt(hashedSize.toInt())
                eb.putLong(0)
                eb.put(headHash)
                entryHeaders.write(eb.array())
            } else {
                val eb = ByteBuffer.allocate(0x18).order(ByteOrder.LITTLE_ENDIAN)
                eb.putLong(dataOff)
                eb.putLong(blob.size)
                eb.putInt(nameOff)
                eb.putInt(0)
                entryHeaders.write(eb.array())
            }
            nameOff += nameBytes[i].size
            val padded = blob.pad(if (hfs) 0x200L else 0x10L)
            bodyParts.add(padded)
            dataOff += padded.size
        }

        return BlobWriter(header.array(), entryHeaders.toByteArray(), paddedTable, *bodyParts.toTypedArray())
    }

    // --- RomFS ---

    private data class DirEntry(
        val path: File,
        val name: ByteArray,
        val parentIdx: Int,
        val childDirs: MutableList<Int> = mutableListOf(),
        val childFiles: MutableList<Int> = mutableListOf(),
        var offset: Int = 0,
        var sibling: Int = EMPTY_INT
    )

    private data class RomFileEntry(
        val path: File,
        val name: ByteArray,
        val parentDirIdx: Int,
        val fileSize: Long,
        var offset: Int = 0,
        var dataOffset: Long = 0,
        var sibling: Int = EMPTY_INT
    )

    fun bucketCount(n: Int): Int {
        if (n < 3) return 3
        if (n < 19) return n or 1
        var v = n
        while (listOf(2, 3, 5, 7, 11, 13, 17).any { v % it == 0 }) v++
        return v
    }

    fun nameHash(parent: Int, name: ByteArray): Int {
        var h = parent xor 123456789
        for (b in name) {
            h = ((h ushr 5) or ((h shl 27))) xor (b.toInt() and 0xFF)
        }
        return h
    }

    data class RomFsManifestEntry(
        val path: String,
        val size: Long,
        val romfsOffset: Long
    )

    fun romfs(
        root: File,
        manifest: MutableList<RomFsManifestEntry>,
        notify: ((Int) -> Unit)? = null
    ): BlobWriter {
        val dirs = mutableListOf<DirEntry>()
        val files = mutableListOf<RomFileEntry>()

        fun visit(path: File, parentIdx: Int): Int {
            val dirIdx = dirs.size
            val entry = DirEntry(
                path = path,
                name = if (parentIdx < 0) byteArrayOf() else path.name.toByteArray(Charsets.UTF_8),
                parentIdx = if (parentIdx < 0) 0 else parentIdx
            )
            dirs.add(entry)

            val children = (path.listFiles() ?: emptyArray())
                .sortedBy { it.name.toByteArray(Charsets.UTF_8).toList().toString() }

            for (child in children) {
                if (child.isDirectory) {
                    entry.childDirs.add(visit(child, dirIdx))
                } else if (child.isFile) {
                    val fi = files.size
                    files.add(
                        RomFileEntry(
                            path = child,
                            name = child.name.toByteArray(Charsets.UTF_8),
                            parentDirIdx = dirIdx,
                            fileSize = child.length()
                        )
                    )
                    entry.childFiles.add(fi)
                    if (notify != null && files.size % 1024 == 0) notify(files.size)
                }
            }
            return dirIdx
        }

        visit(root, -1)

        // Compute offsets
        var doff = 0
        for (d in dirs) {
            d.offset = doff
            doff += 0x18 + alignInt(d.name.size, 4)
        }
        var foff = 0
        var dataoff = 0L
        for (f in files) {
            f.offset = foff
            foff += 0x20 + alignInt(f.name.size, 4)
            f.dataOffset = align(dataoff, 0x10)
            dataoff = f.dataOffset + f.fileSize
        }

        // Link siblings
        for (d in dirs) {
            for ((i, childIdx) in d.childDirs.withIndex()) {
                dirs[childIdx].sibling = if (i + 1 < d.childDirs.size) dirs[d.childDirs[i + 1]].offset else EMPTY_INT
            }
            for ((i, childIdx) in d.childFiles.withIndex()) {
                files[childIdx].sibling = if (i + 1 < d.childFiles.size) files[d.childFiles[i + 1]].offset else EMPTY_INT
            }
        }

        // Build hash buckets and tables
        val dBuckets = IntArray(bucketCount(dirs.size)) { EMPTY_INT }
        val fBuckets = IntArray(bucketCount(files.size)) { EMPTY_INT }
        val dirTable = ByteArray(doff)
        val fileTable = ByteArray(foff)

        for (d in dirs) {
            val parentOff = if (d.parentIdx == 0 && dirs[0] == d) 0 else dirs[d.parentIdx].offset
            val bucketIdx = ((nameHash(parentOff, d.name).toLong() and 0xFFFFFFFFL) % dBuckets.size).toInt()

            val entry = packLE("IIIIII",
                parentOff,
                d.sibling,
                if (d.childDirs.isNotEmpty()) dirs[d.childDirs[0]].offset else EMPTY_INT,
                if (d.childFiles.isNotEmpty()) files[d.childFiles[0]].offset else EMPTY_INT,
                dBuckets[bucketIdx],
                d.name.size
            )
            dBuckets[bucketIdx] = d.offset
            System.arraycopy(entry, 0, dirTable, d.offset, 0x18)
            System.arraycopy(d.name, 0, dirTable, d.offset + 0x18, d.name.size)
        }

        val payloadParts = mutableListOf<Any>()
        var currentPos = 0L
        for (f in files) {
            val parentOff = dirs[f.parentDirIdx].offset
            val bucketIdx = ((nameHash(parentOff, f.name).toLong() and 0xFFFFFFFFL) % fBuckets.size).toInt()

            val entry = packLE("IIQQII",
                parentOff,
                f.sibling,
                f.dataOffset,
                f.fileSize,
                fBuckets[bucketIdx],
                f.name.size
            )
            fBuckets[bucketIdx] = f.offset
            System.arraycopy(entry, 0, fileTable, f.offset, 0x20)
            System.arraycopy(f.name, 0, fileTable, f.offset + 0x20, f.name.size)

            val padding = f.dataOffset - currentPos
            if (padding > 0) payloadParts.add(padding)
            payloadParts.add(BlobPart.FilePart(f.path, f.fileSize))
            currentPos = f.dataOffset + f.fileSize

            manifest.add(RomFsManifestEntry(f.path.absolutePath, f.fileSize, f.dataOffset))
        }

        val dhBytes = packIntArray(dBuckets)
        val fhBytes = packIntArray(fBuckets)

        val dhPos = 0x50L
        val dtPos = dhPos + dhBytes.size
        val fhPos = dtPos + dirTable.size
        val ftPos = fhPos + fhBytes.size
        val dataPos = align(ftPos + fileTable.size, 0x10)

        val header = packLE("QQQQQQQQQQ",
            0x50L, dhPos, dhBytes.size.toLong(), dtPos, dirTable.size.toLong(),
            fhPos, fhBytes.size.toLong(), ftPos, fileTable.size.toLong(), dataPos
        )

        val gapAfterFileTable = dataPos - ftPos - fileTable.size

        return BlobWriter(
            header, dhBytes, dirTable, fhBytes, fileTable,
            gapAfterFileTable, *payloadParts.toTypedArray()
        )
    }

    // --- Integrity trees ---

    fun pfsSection(blob: BlobWriter): Pair<ByteArray, BlobWriter> {
        var block = 0x10000L
        while (blob.size > block * block / 32) block *= 2

        val hashes = java.io.ByteArrayOutputStream()
        for (chunk in blob.chunksBlocking(block.toInt())) {
            hashes.write(sha(chunk))
        }
        val hashData = hashes.toByteArray()
        val dataOff = align(hashData.size.toLong(), 0x200)

        val header = ByteArray(0x200)
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        hb.putShort(0, 2)
        hb.put(2, 1)
        hb.put(3, 2)
        hb.put(4, 1)
        val masterHash = sha(hashData)
        System.arraycopy(masterHash, 0, header, 8, 32)
        hb.putInt(0x28, block.toInt())
        hb.putInt(0x2C, 2)
        hb.putLong(0x30, 0)
        hb.putLong(0x38, hashData.size.toLong())
        hb.putLong(0x40, dataOff)
        hb.putLong(0x48, blob.size)

        val body = BlobWriter(hashData, dataOff - hashData.size, blob).pad(0x200)
        return Pair(header, body)
    }

    fun romfsSection(blob: BlobWriter): Pair<ByteArray, BlobWriter> {
        var block = 0x4000L
        val levels = mutableListOf(blob)

        for (layer in 0 until 5) {
            val digests = java.io.ByteArrayOutputStream()
            for (chunk in levels[0].chunksBlocking(block.toInt())) {
                val padded = chunk + ByteArray(maxOf(0, block.toInt() - chunk.size))
                digests.write(sha(padded))
            }
            levels.add(0, BlobWriter(digests.toByteArray()))
        }

        val masterBlock = levels[0].readHead(block.toInt())
        val masterPadded = masterBlock + ByteArray(maxOf(0, block.toInt() - masterBlock.size))
        val masterHash = sha(masterPadded)

        val header = ByteArray(0x200)
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        hb.putShort(0, 2)
        hb.put(2, 0)
        hb.put(3, 3)
        hb.put(4, 1)
        // IVFC magic
        header[8] = 'I'.code.toByte()
        header[9] = 'V'.code.toByte()
        header[10] = 'F'.code.toByte()
        header[11] = 'C'.code.toByte()
        hb.putInt(0x0C, 0x20000)
        hb.putInt(0x10, 32)
        hb.putInt(0x14, 7)

        var off = 0L
        for (i in levels.indices) {
            val level = levels[i]
            hb.putLong(0x18 + 24 * i, off)
            hb.putLong(0x18 + 24 * i + 8, level.size)
            hb.putInt(0x18 + 24 * i + 16, 14)
            hb.putInt(0x18 + 24 * i + 20, 0)
            val padded = level.pad(block)
            off += padded.size
        }

        System.arraycopy(masterHash, 0, header, 0xC8, 32)

        val bodyParts = levels.map { it.pad(block) }
        return Pair(header, BlobWriter(*bodyParts.toTypedArray()))
    }

    // --- NCA ---

    fun nca(
        sections: List<Pair<ByteArray, BlobWriter>>,
        titleId: Long,
        contentType: Int,
        distribution: Int = 1
    ): BlobWriter {
        val hdr = ByteArray(0xC00)
        val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)

        // NCA3 magic at 0x200
        hdr[0x200] = 'N'.code.toByte()
        hdr[0x201] = 'C'.code.toByte()
        hdr[0x202] = 'A'.code.toByte()
        hdr[0x203] = '3'.code.toByte()

        hdr[0x204] = distribution.toByte()
        hdr[0x205] = contentType.toByte()
        hdr[0x206] = 1 // crypto type
        hdr[0x207] = 0

        hb.putLong(0x210, titleId)
        hb.putInt(0x21C, 0x000C1100)

        val bodyParts = mutableListOf<Any>()
        var off = 0xC00L

        for ((i, section) in sections.withIndex()) {
            val (fsh, body) = section
            val paddedBody = body.pad(0x200)

            hb.putInt(0x240 + 16 * i, (off / 512).toInt())
            hb.putInt(0x240 + 16 * i + 4, ((off + paddedBody.size) / 512).toInt())
            hb.putInt(0x240 + 16 * i + 8, 0)
            hb.putInt(0x240 + 16 * i + 12, 0)

            val fshHash = sha(fsh)
            System.arraycopy(fshHash, 0, hdr, 0x280 + 32 * i, 32)
            System.arraycopy(fsh, 0, hdr, 0x400 + 512 * i, minOf(fsh.size, 512))

            bodyParts.add(paddedBody)
            off += paddedBody.size
        }

        hb.putLong(0x208, off)

        return BlobWriter(hdr, *bodyParts.toTypedArray())
    }

    // --- NSP package ---

    fun buildPackage(entries: List<Pair<String, BlobWriter>>): BlobWriter {
        val result = filesystem(entries)
        if (result.size > 32L shl 30) {
            throw IllegalArgumentException("This release supports packages up to 32 GiB.")
        }
        return result
    }

    // --- Helpers ---

    private fun alignInt(n: Int, a: Int): Int = (n + a - 1) / a * a

    private fun packIntArray(arr: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in arr) buf.putInt(v)
        return buf.array()
    }

    private fun BlobWriter.chunksBlocking(blockSize: Int): Sequence<ByteArray> = sequence {
        val pending = java.io.ByteArrayOutputStream(blockSize)
        for (part in parts) {
            when (part) {
                is BlobPart.Bytes -> {
                    var pos = 0
                    while (pos < part.data.size) {
                        val n = minOf(blockSize - pending.size(), part.data.size - pos)
                        pending.write(part.data, pos, n)
                        pos += n
                        if (pending.size() == blockSize) {
                            yield(pending.toByteArray())
                            pending.reset()
                        }
                    }
                }
                is BlobPart.FilePart -> {
                    java.io.FileInputStream(part.path).use { fis ->
                        var remaining = part.size
                        while (remaining > 0) {
                            val n = minOf((blockSize - pending.size()).toLong(), remaining).toInt()
                            val buf = ByteArray(n)
                            val read = fis.read(buf)
                            if (read != n) throw java.io.IOException("Source changed or short read")
                            pending.write(buf, 0, read)
                            remaining -= read
                            if (pending.size() == blockSize) {
                                yield(pending.toByteArray())
                                pending.reset()
                            }
                        }
                    }
                }
                is BlobPart.Padding -> {
                    var remaining = part.size
                    val zeroBuf = ByteArray(minOf(blockSize.toLong(), remaining).toInt())
                    while (remaining > 0) {
                        val n = minOf((blockSize - pending.size()).toLong(), remaining).toInt()
                        pending.write(zeroBuf, 0, n)
                        remaining -= n
                        if (pending.size() == blockSize) {
                            yield(pending.toByteArray())
                            pending.reset()
                        }
                    }
                }
            }
        }
        if (pending.size() > 0) {
            yield(pending.toByteArray())
        }
    }
}
