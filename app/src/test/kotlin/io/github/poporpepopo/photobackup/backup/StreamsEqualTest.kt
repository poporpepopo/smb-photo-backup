package io.github.poporpepopo.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class StreamsEqualTest {

    /** Returns at most [chunk] bytes per read, like an SMB stream does. */
    private class Trickle(bytes: ByteArray, private val chunk: Int) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, chunk))
    }

    private val big = ByteArray(300_000) { (it * 31).toByte() }

    @Test fun equalContentWithDifferentReadSizes() {
        assertTrue(streamsEqual(Trickle(big, 1000), Trickle(big.copyOf(), 65_000)))
    }

    @Test fun differenceNearTheEnd() {
        val other = big.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(streamsEqual(ByteArrayInputStream(big), ByteArrayInputStream(other)))
    }

    @Test fun prefixIsNotEqual() {
        assertFalse(streamsEqual(ByteArrayInputStream(big), ByteArrayInputStream(big.copyOf(1000))))
    }

    @Test fun emptyStreamsAreEqual() {
        assertTrue(streamsEqual(ByteArrayInputStream(ByteArray(0)), ByteArrayInputStream(ByteArray(0))))
    }
}

class BoundedInputStreamTest {
    private val file = ByteArray(1000) { it.toByte() }
    private val requests = mutableListOf<Pair<Long, Int>>()

    /** Serves at most 300 bytes per call, and records every request. */
    private fun stream(size: Long) = BoundedInputStream(size) { buf, at, off, len ->
        requests += at to len
        val n = minOf(len, 300, (file.size - at).toInt())
        if (n <= 0) -1 else { System.arraycopy(file, at.toInt(), buf, off, n); n }
    }

    @Test fun readsExactlySizeBytes() {
        assertTrue(streamsEqual(stream(1000), ByteArrayInputStream(file)))
    }

    @Test fun neverRequestsBeyondTheEnd() {
        val s = stream(1000)
        val buf = ByteArray(4096)
        while (s.read(buf, 0, buf.size) >= 0) Unit
        assertTrue(requests.all { (at, len) -> at + len <= 1000 })
        assertTrue(requests.isNotEmpty())
    }

    @Test fun readsARangeFromAnOffset() {
        val s = BoundedInputStream(100, start = 900) { buf, at, off, len ->
            requests += at to len
            val n = minOf(len, 30)
            System.arraycopy(file, at.toInt(), buf, off, n)
            n
        }
        assertTrue(streamsEqual(s, ByteArrayInputStream(file, 900, 100)))
        assertEquals(900L, requests.first().first)
        assertTrue(requests.all { (at, len) -> at >= 900 && at + len <= 1000 })
    }

    @Test fun emptyFileNeverHitsTheServer() {
        assertTrue(streamsEqual(stream(0), ByteArrayInputStream(ByteArray(0))))
        assertTrue(requests.isEmpty())
    }

    @Test fun shortFileOnServerIsNotEqual() {
        // The server has 1000 bytes, but we expect 1200: the stream ends early.
        assertFalse(streamsEqual(stream(1200), ByteArrayInputStream(ByteArray(1200))))
    }
}
