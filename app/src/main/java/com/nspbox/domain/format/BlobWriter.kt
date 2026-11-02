package com.nspbox.domain.format

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed class BlobPart {
    abstract val size: Long

    class Bytes(val data: ByteArray) : BlobPart() {
        override val size: Long get() = data.size.toLong()
    }

    class FilePart(val path: File, override val size: Long) : BlobPart()
    class Padding(override val size: Long) : BlobPart()
}

class BlobWriter(vararg parts: Any) {
    val parts = mutableListOf<BlobPart>()
    val size: Long

    init {
        for (p in parts) {
            when (p) {
                is BlobWriter -> this.parts.addAll(p.parts)
                is BlobPart -> this.parts.add(p)
                is ByteArray -> if (p.isNotEmpty()) this.parts.add(BlobPart.Bytes(p))
                is File -> this.parts.add(BlobPart.FilePart(p, p.length()))
                is Int -> if (p > 0) this.parts.add(BlobPart.Padding(p.toLong()))
                is Long -> if (p > 0) this.parts.add(BlobPart.Padding(p))
            }
        }
        size = this.parts.sumOf { it.size }
    }

    suspend fun chunks(
        blockSize: Int = 1024 * 1024,
        sourceDigests: MutableMap<File, ByteArray>? = null
    ): Sequence<ByteArray> = sequence {
        val pending = ByteArrayOutputStream(blockSize)
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
                    val digest = if (sourceDigests != null && part.path in sourceDigests)
                        MessageDigest.getInstance("SHA-256") else null
                    FileInputStream(part.path).use { fis ->
                        var remaining = part.size
                        while (remaining > 0) {
                            val n = minOf(
                                (blockSize - pending.size()).toLong(),
                                remaining
                            ).toInt()
                            val buf = ByteArray(n)
                            val read = fis.read(buf)
                            if (read != n) throw java.io.IOException("Source changed or short read")
                            digest?.update(buf, 0, read)
                            pending.write(buf, 0, read)
                            remaining -= read
                            if (pending.size() == blockSize) {
                                yield(pending.toByteArray())
                                pending.reset()
                            }
                        }
                    }
                    if (digest != null) {
                        sourceDigests!![part.path] = digest.digest()
                    }
                }
                is BlobPart.Padding -> {
                    var remaining = part.size
                    val zeroBuf = ByteArray(minOf(blockSize.toLong(), remaining).toInt())
                    while (remaining > 0) {
                        val n = minOf(
                            (blockSize - pending.size()).toLong(),
                            remaining
                        ).toInt()
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

    fun readHead(n: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream(n)
        for (part in parts) {
            if (result.size() >= n) break
            val needed = n - result.size()
            when (part) {
                is BlobPart.Bytes -> {
                    val take = minOf(needed, part.data.size)
                    result.write(part.data, 0, take)
                }
                is BlobPart.FilePart -> {
                    val take = minOf(needed.toLong(), part.size).toInt()
                    FileInputStream(part.path).use { fis ->
                        val buf = ByteArray(take)
                        val read = fis.read(buf)
                        if (read > 0) result.write(buf, 0, read)
                    }
                }
                is BlobPart.Padding -> {
                    val take = minOf(needed.toLong(), part.size).toInt()
                    result.write(ByteArray(take))
                }
            }
        }
        return result.toByteArray()
    }

    fun digest(): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val iter = chunksBlocking(4 * 1024 * 1024)
        for (block in iter) {
            md.update(block)
        }
        return md.digest()
    }

    fun pad(alignment: Long): BlobWriter {
        val padded = align(size, alignment) - size
        return BlobWriter(this, padded)
    }

    private fun chunksBlocking(blockSize: Int): Sequence<ByteArray> = sequence {
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
                    FileInputStream(part.path).use { fis ->
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

    companion object {
        fun align(n: Long, a: Long): Long = (n + a - 1) / a * a
    }
}

private class ByteArrayOutputStream(capacity: Int) : java.io.ByteArrayOutputStream(capacity)
