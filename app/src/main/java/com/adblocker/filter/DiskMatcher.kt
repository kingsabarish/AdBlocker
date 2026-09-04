package com.adblocker.filter

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Memory-mapped blocklist matcher. Domains are sorted and stored in a file that is
 * memory-mapped via [MappedByteBuffer]. Binary search operates on the mmap'd buffer
 * so the domain data lives in the page cache, not the JVM heap.
 *
 * File layout:
 *   [int32 count]
 *   [int32 offset_0][int32 len_0]
 *   [int32 offset_1][int32 len_1]
 *   ...
 *   [byte[] domain_0][byte[] domain_1]...
 */
class DiskMatcher private constructor(
    private val buffer: MappedByteBuffer,
    private val offsets: IntArray,
    private val lengths: IntArray,
    private val count: Int,
) : DomainMatcher {

    override fun size(): Int = count

    override fun matches(domain: String): Boolean {
        val d = domain.lowercase().trim()
        if (d.isEmpty()) return false
        if (binarySearch(d)) return true
        var dot = d.indexOf('.')
        while (dot >= 0) {
            if (binarySearch(d.substring(dot + 1))) return true
            dot = d.indexOf('.', dot + 1)
        }
        return false
    }

    private fun binarySearch(target: String): Boolean {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val off = offsets[mid]
            val len = lengths[mid]
            val cmp = compareBuffer(off, len, target)
            if (cmp < 0) lo = mid + 1
            else if (cmp > 0) hi = mid - 1
            else return true
        }
        return false
    }

    private fun compareBuffer(off: Int, len: Int, target: String): Int {
        val tLen = target.length
        val minLen = if (len < tLen) len else tLen
        var i = 0
        while (i < minLen) {
            val a = buffer.get(off + i).toInt() and 0xFF
            val b = target[i].code
            if (a != b) return a - b
            i++
        }
        return len - tLen
    }

    companion object {
        private const val TAG = "AdBlock/DiskMatcher"

        fun build(dir: File, tempDomains: MutableList<String>): DiskMatcher {
            tempDomains.sort()
            var prev = ""
            val unique = ArrayList<String>(tempDomains.size)
            for (d in tempDomains) {
                if (d != prev) {
                    unique.add(d)
                    prev = d
                }
            }
            tempDomains.clear()

            val count = unique.size
            val headerSize = 4 + count * 8
            var dataSize = 0
            for (d in unique) dataSize += d.toByteArray().size
            val totalSize = headerSize + dataSize

            val file = File(dir, "blocklist.bin")
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(totalSize.toLong())
                raf.writeInt(count)
                var dataOffset = headerSize
                for (i in unique.indices) {
                    raf.seek(4L + i * 8L)
                    raf.writeInt(dataOffset)
                    val bytes = unique[i].toByteArray()
                    raf.writeInt(bytes.size)
                    raf.seek(dataOffset.toLong())
                    raf.write(bytes)
                    dataOffset += bytes.size
                }
            }
            unique.clear()

            val channel = FileInputStream(file).channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            channel.close()

            val offsetsArr = IntArray(count)
            val lengthsArr = IntArray(count)
            buffer.position(0)
            val readCount = buffer.getInt()
            for (i in 0 until readCount) {
                offsetsArr[i] = buffer.getInt()
                lengthsArr[i] = buffer.getInt()
            }

            Log.i(TAG, "built blocklist: $count domains, ${file.length()} bytes")
            return DiskMatcher(buffer, offsetsArr, lengthsArr, readCount)
        }
    }
}
