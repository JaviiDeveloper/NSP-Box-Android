package com.nspbox.domain.format

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

class Region(
    private val mapping: MappedByteBuffer,
    private val offset: Long,
    val size: Long
) {
    constructor(mapping: MappedByteBuffer) : this(mapping, 0, mapping.capacity().toLong())

    operator fun get(index: Long): Byte {
        return mapping.get((offset + index).toInt())
    }

    fun slice(from: Long, length: Long): Region {
        require(from >= 0 && length >= 0 && from + length <= size) { "Range outside container" }
        return Region(mapping, offset + from, length)
    }

    fun slice(from: Long): Region = slice(from, size - from)

    fun readBytes(off: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        for (i in 0 until len) {
            buf[i] = mapping.get((offset + off + i).toInt())
        }
        return buf
    }

    fun toByteArray(): ByteArray = readBytes(0, size.toInt())

    fun readInt(off: Long): Int {
        val b = readBytes(off, 4)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun readLong(off: Long): Long {
        val b = readBytes(off, 8)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    fun readShort(off: Long): Short {
        val b = readBytes(off, 2)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short
    }

    fun digest(): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val blockSize = 4 * 1024 * 1024
        var pos = 0L
        while (pos < size) {
            val len = minOf(blockSize.toLong(), size - pos).toInt()
            val buf = readBytes(pos, len)
            md.update(buf)
            pos += len
        }
        return md.digest()
    }

    fun blockDigests(blockSize: Int, padded: Boolean = false): Sequence<ByteArray> = sequence {
        var pos = 0L
        while (pos < size) {
            val len = minOf(blockSize.toLong(), size - pos).toInt()
            val buf = readBytes(pos, len)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(buf)
            if (padded && len < blockSize) {
                md.update(ByteArray(blockSize - len))
            }
            yield(md.digest())
            pos += len
        }
    }
}

object VerifyFormat {

    fun partition(buf: Region, offset: Long = 0): Map<String, Region> {
        val magic = String(buf.readBytes(offset, 4))
        require(magic == "PFS0" || magic == "HFS0")
        val n = buf.readInt(offset + 4)
        val stSize = buf.readInt(offset + 8)
        val entrySize = if (magic == "HFS0") 0x40 else 0x18
        val strings = offset + 16 + n * entrySize
        val data = strings + stSize

        val result = mutableMapOf<String, Region>()
        for (i in 0 until n) {
            val at = offset + 16 + i * entrySize
            val rel = buf.readLong(at)
            val size = buf.readLong(at + 8)
            val nameOff = buf.readInt(at + 16)

            val nameBytes = buf.readBytes(strings + nameOff, minOf(256, (stSize - nameOff)))
            val name = String(nameBytes.takeWhile { it != 0.toByte() }.toByteArray())

            val view = buf.slice(data + rel, size)
            require(view.size == size) { "Partition size mismatch" }

            if (magic == "HFS0") {
                val hn = buf.readInt(at + 20)
                val ho = buf.readLong(at + 24)
                val expected = buf.readBytes(at + 32, 32)
                val actual = view.slice(ho, hn.toLong()).digest()
                require(actual.contentEquals(expected)) { "HFS hash mismatch: $name" }
            }
            result[name] = view
        }
        return result
    }

    data class NcaInfo(
        val titleId: Long,
        val contentType: Int,
        val sections: List<Pair<String, Region>>
    )

    fun ncaSections(buf: Region): NcaInfo {
        val magic = String(buf.readBytes(0x200, 4))
        require(magic == "NCA3") { "Not an NCA3 container" }
        require(buf.readLong(0x208) == buf.size) { "NCA size mismatch" }

        val titleId = buf.readLong(0x210)
        val contentType = buf[0x205].toInt() and 0xFF
        val sections = mutableListOf<Pair<String, Region>>()

        for (i in 0 until 4) {
            val start = buf.readInt(0x240L + 16L * i).toLong() and 0xFFFFFFFFL
            val end = buf.readInt(0x240L + 16L * i + 4L).toLong() and 0xFFFFFFFFL
            if (start == 0L && end == 0L) continue

            val header = buf.slice(0x400L + 512L * i, 512)
            val expectedHash = buf.readBytes(0x280L + 32L * i, 32)
            require(header.digest().contentEquals(expectedHash)) { "Section header hash mismatch" }
            require(header[4] == 1.toByte()) { "Expected plaintext section" }

            val body = buf.slice(start * 512, (end - start) * 512)

            val hashType = header[3].toInt() and 0xFF
            when (hashType) {
                2 -> {
                    // PFS0 with SHA-256
                    val block = header.readInt(0x28).toLong()
                    val ho = header.readLong(0x30)
                    val hs = header.readLong(0x38)
                    val dOff = header.readLong(0x40)
                    val ds = header.readLong(0x48)

                    val hashes = body.slice(ho, hs)
                    val data = body.slice(dOff, ds)
                    val masterHash = header.readBytes(8, 32)
                    require(hashes.digest().contentEquals(masterHash)) { "PFS master hash mismatch" }

                    var k = 0
                    for (actual in data.blockDigests(block.toInt())) {
                        val expected = hashes.readBytes(k * 32L, 32)
                        require(actual.contentEquals(expected)) { "PFS block hash mismatch" }
                        k++
                    }
                    sections.add("pfs" to data)
                }
                3 -> {
                    // IVFC/RomFS
                    val ivfcMagic = String(header.readBytes(8, 4))
                    require(ivfcMagic == "IVFC") { "Not IVFC" }
                    val count = header.readInt(0x14) - 1
                    require(count == 6) { "Unexpected IVFC level count" }

                    var expected = header.readBytes(0xC8, 32)
                    for (j in 0 until count) {
                        val off = header.readLong(0x18L + j * 24L)
                        val sz = header.readLong(0x18L + j * 24L + 8L)
                        val order = header.readInt(0x18L + j * 24L + 16L)
                        val bsize = 1L shl order

                        val levelData = body.slice(off, sz)
                        var k = 0
                        for (actual in levelData.blockDigests(bsize.toInt(), padded = true)) {
                            val exp = ByteArray(32)
                            for (b in 0 until 32) exp[b] = expected[k * 32 + b]
                            require(actual.contentEquals(exp)) { "IVFC hash mismatch at level $j block $k" }
                            k++
                        }
                        expected = levelData.toByteArray()
                    }
                    sections.add("romfs" to body.slice(
                        header.readLong(0x18L + (count - 1) * 24L),
                        header.readLong(0x18L + (count - 1) * 24L + 8L)
                    ))
                }
                else -> throw IllegalArgumentException("Unsupported hash type: $hashType")
            }
        }
        return NcaInfo(titleId, contentType, sections)
    }

    fun romfsFiles(buf: Region): Map<String, Region> {
        val dtOff = buf.readLong(3L * 8L)
        val ftOff = buf.readLong(7L * 8L)
        val dataOff = buf.readLong(9L * 8L)
        val result = mutableMapOf<String, Region>()

        fun walk(off: Int, parent: String) {
            var current = off
            while (current != -1) {
                val sib = buf.readInt(dtOff + current + 4)
                val child = buf.readInt(dtOff + current + 8)
                val first = buf.readInt(dtOff + current + 12)
                val nameLen = buf.readInt(dtOff + current + 20)
                val name = if (nameLen > 0) String(buf.readBytes(dtOff + current + 24, nameLen)) else ""
                val prefix = if (parent.isEmpty() && name.isEmpty()) "" else "$parent/$name".trimStart('/')

                var f = first
                while (f != -1) {
                    val fs = buf.readInt(ftOff + f + 4)
                    val fo = buf.readLong(ftOff + f + 8)
                    val fsize = buf.readLong(ftOff + f + 16)
                    val fn = buf.readInt(ftOff + f + 28)
                    val fname = String(buf.readBytes(ftOff + f + 32, fn))
                    val fullPath = if (prefix.isEmpty()) fname else "$prefix/$fname"
                    result[fullPath] = buf.slice(dataOff + fo, fsize)
                    f = fs
                }
                if (child != -1) walk(child, prefix)
                current = sib
            }
        }

        walk(0, "")
        return result
    }

    fun verifyImage(
        path: File,
        snapshot: com.nspbox.domain.model.Snapshot,
        inventory: Map<File, Pair<Long, Long>>,
        notify: (String, Float) -> Unit,
        sourceHashes: Map<File, ByteArray>? = null
    ): List<com.nspbox.domain.model.FileEntry> {
        RandomAccessFile(path, "r").use { raf ->
            val channel = raf.channel
            val mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            val buf = Region(mapping)

            require(String(buf.readBytes(0, 4)) == "PFS0") { "Not an NSP/PFS0 file" }

            val ncas = partition(buf)
            val parsed = mutableMapOf<String, NcaInfo>()
            val ncaHashes = mutableMapOf<String, ByteArray>()

            for ((name, data) in ncas) {
                require((data[0x204].toInt() and 0xFF) == 0) { "NCA distribution type mismatch" }
                ncaHashes[name] = data.digest()
                val hashHex = ncaHashes[name]!!.take(16).joinToString("") { "%02x".format(it) }
                require(hashHex == name.take(32)) { "NCA content hash mismatch" }
                parsed[name] = ncaSections(data)
            }

            val program = parsed.values.first { it.contentType == 0 }
            require(program.titleId == snapshot.titleId) { "Program ID mismatch" }

            val exefs = partition(program.sections.first { it.first == "pfs" }.second)
            val romfs = romfsFiles(program.sections.first { it.first == "romfs" }.second)

            val checked = mutableListOf<com.nspbox.domain.model.FileEntry>()
            val total = inventory.values.sumOf { it.first }
            var done = 0L

            for (folder in listOf("exefs", "romfs")) {
                val packed = if (folder == "exefs") exefs else romfs
                val originals = inventory.keys
                    .filter { it.absolutePath.startsWith((snapshot.root.resolve(folder)).absolutePath + File.separator) }
                    .associateBy { it.relativeTo(snapshot.root.resolve(folder)).path.replace('\\', '/') }

                require(originals.keys == packed.keys) { "Packed file list differs from source in $folder" }

                for ((name, file) in originals) {
                    val expected = if (sourceHashes != null) {
                        sourceHashes[file] ?: throw IllegalStateException("Source hash not captured: $folder/$name")
                    } else {
                        val md = MessageDigest.getInstance("SHA-256")
                        file.inputStream().buffered().use { input ->
                            val buf2 = ByteArray(4 * 1024 * 1024)
                            var read: Int
                            while (input.read(buf2).also { read = it } != -1) {
                                md.update(buf2, 0, read)
                            }
                        }
                        md.digest()
                    }
                    val actual = packed[name]!!.digest()
                    require(expected.contentEquals(actual)) { "Packed file differs: $folder/$name" }

                    done += inventory[file]!!.first
                    notify("Comparing source files", 82f + 17f * done / maxOf(total, 1))

                    checked.add(com.nspbox.domain.model.FileEntry(
                        path = "$folder/$name",
                        size = inventory[file]!!.first,
                        sha256 = expected.joinToString("") { "%02x".format(it) }
                    ))
                }
            }

            // Verify CNMT
            val meta = parsed.values.first { it.contentType == 1 }
            val cnmtData = partition(meta.sections[0].second).values.first()
            val cnmtBytes = cnmtData.toByteArray()
            val cnmtBuf = ByteBuffer.wrap(cnmtBytes).order(ByteOrder.LITTLE_ENDIAN)
            val tid = cnmtBuf.getLong(0)
            val ver = cnmtBuf.getInt(8).toLong() and 0xFFFFFFFFL
            val ctype = cnmtBuf.get(12).toInt() and 0xFF
            require(tid == snapshot.titleId && ver == snapshot.version && ctype == 0x80) { "CNMT title or version mismatch" }

            val cnmtDigest = MessageDigest.getInstance("SHA-256").digest(cnmtBytes.copyOfRange(0, cnmtBytes.size - 32))
            require(cnmtDigest.contentEquals(cnmtBytes.copyOfRange(cnmtBytes.size - 32, cnmtBytes.size))) { "CNMT digest mismatch" }

            // Verify control NCA has control.nacp
            val control = parsed.values.first { it.contentType == 2 }
            val cFiles = romfsFiles(control.sections[0].second)
            require("control.nacp" in cFiles) { "Missing control.nacp in control NCA" }

            return checked
        }
    }
}
