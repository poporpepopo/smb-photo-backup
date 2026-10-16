package io.github.poporpepopo.photobackup.backup

import io.github.poporpepopo.photobackup.smb.SmbFailure
import io.github.poporpepopo.photobackup.smb.SmbFailureException
import io.github.poporpepopo.photobackup.smb.SmbGateway
import io.github.poporpepopo.photobackup.smb.SmbTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Runs [Uploader] against a real SMB server. Skipped unless SMB_TEST_PORT (server on
 * 127.0.0.1, share "USB", user tester / Dummy-Pass-123) and SMB_TEST_ROOT (the share's
 * directory on this machine) are set. See docs/device-testing.md.
 */
class SmbIntegrationTest {
    private val port = System.getenv("SMB_TEST_PORT")?.toIntOrNull()
    private val root = System.getenv("SMB_TEST_ROOT")?.let(::File)
    private lateinit var dir: String

    private fun target() = SmbTarget("127.0.0.1", "USB", "tester", "Dummy-Pass-123", port!!)

    @Before fun setUp() {
        assumeTrue(port != null && root != null)
        dir = "it-${System.nanoTime()}/2026/10"
    }

    private fun local(id: Long, bytes: ByteArray, name: String = "IMG.jpg") =
        LocalFile(id, name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private fun upload(f: LocalFile) = runBlocking {
        SmbGateway.withShare(target()) { Uploader(it.disk).upload(f, dir) }
    }

    private fun onDisk(name: String) = File(root, "$dir/$name")

    @Test(timeout = 60_000) fun uploadsThenRecognisesTheSameFile() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(UploadResult.Uploaded("$dir/IMG.jpg", bytes.size.toLong()), upload(local(1, bytes)))
        assertArrayEquals(bytes, onDisk("IMG.jpg").readBytes())

        assertEquals(UploadResult.AlreadyPresent("$dir/IMG.jpg"), upload(local(1, bytes)))
        assertEquals(listOf("IMG.jpg"), onDisk("").list()!!.sorted())
    }

    @Test(timeout = 60_000) fun differentContentGetsANumberedName() {
        val first = ByteArray(5000) { 1 }
        val second = ByteArray(5000) { 2 }
        upload(local(1, first))
        assertEquals(UploadResult.Uploaded("$dir/IMG (1).jpg", 5000), upload(local(2, second)))
        assertArrayEquals(first, onDisk("IMG.jpg").readBytes())
        assertArrayEquals(second, onDisk("IMG (1).jpg").readBytes())
    }

    /** A video-sized file: a byte pattern that differs everywhere, so any slip shows. */
    private val video = ByteArray(20 shl 20) { (it * 31 + (it shr 16)).toByte() }

    @Test(timeout = 120_000) fun largeFileIsRecognisedFromSamples() {
        upload(local(6, video, "VID.mp4"))
        val reports = mutableListOf<Triple<Transfer, Long, Long>>()
        val result = runBlocking {
            SmbGateway.withShare(target()) {
                Uploader(it.disk).upload(local(6, video, "VID.mp4"), dir) { t, done, total -> reports += Triple(t, done, total) }
            }
        }
        assertEquals(UploadResult.AlreadyPresent("$dir/VID.mp4"), result)
        assertTrue(reports.all { it.first == Transfer.Sampling })
        val size = video.size.toLong()
        assertEquals(Triple(Transfer.Sampling, size, size), reports.last())
    }

    @Test(timeout = 120_000) fun largeFileDifferingInASampleGetsANumberedName() {
        upload(local(6, video, "VID.mp4"))
        // Same size, different bytes at the end: where a video keeps its index.
        val other = video.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        assertEquals(UploadResult.Uploaded("$dir/VID (1).mp4", other.size.toLong()), upload(local(7, other, "VID.mp4")))
    }

    @Test(timeout = 120_000) fun largeFileDifferingOutsideTheSamplesIsTakenAsTheSame() {
        // The accepted trade-off of ContentSample: bytes between the samples are not read.
        upload(local(6, video, "VID.mp4"))
        val at = 2 shl 20 // past the first 1 MB, before the first middle sample (~2.2 MB)
        val other = video.copyOf().also { it[at] = (it[at] + 1).toByte() }
        assertEquals(UploadResult.AlreadyPresent("$dir/VID.mp4"), upload(local(7, other, "VID.mp4")))
    }

    @Test(timeout = 60_000) fun localFileLongerThanItsSizeIsNotTheSame() {
        val bytes = ByteArray(5000) { 3 }
        upload(local(8, bytes))
        // MediaStore said 5000 bytes, but the file has grown since.
        val grown = LocalFile(8, "IMG.jpg", 5000) { ByteArrayInputStream(bytes + ByteArray(10)) }
        // Not taken as already sent; sending it then fails the size check, as it should.
        val failure = runCatching { upload(grown) }.exceptionOrNull()
        assertTrue("$failure", failure?.message?.contains("size mismatch") == true)
    }

    @Test(timeout = 60_000) fun emptyFile() {
        assertEquals(UploadResult.Uploaded("$dir/empty.jpg", 0), upload(local(3, ByteArray(0), "empty.jpg")))
        assertEquals(UploadResult.AlreadyPresent("$dir/empty.jpg"), upload(local(3, ByteArray(0), "empty.jpg")))
    }

    @Test(timeout = 60_000) fun tempFileIsTrackedUntilRenamed() {
        val events = mutableListOf<String>()
        val temps = object : TempFiles {
            override fun created(path: String) { events += "+$path" }
            override fun removed(path: String) { events += "-$path" }
        }
        runBlocking {
            SmbGateway.withShare(target()) { Uploader(it.disk, temps = temps).upload(local(5, ByteArray(10)), dir) }
        }
        val temp = "$dir/${Destination.tempName(5)}"
        assertEquals(listOf("+$temp", "-$temp"), events)
    }

    @Test(timeout = 60_000) fun removesLeftoversButNeverOtherFiles() {
        upload(local(1, ByteArray(10)))
        onDisk(Destination.tempName(9)).writeBytes(ByteArray(3))
        runBlocking {
            SmbGateway.withShare(target()) {
                val u = Uploader(it.disk)
                assertTrue(u.removeLeftover("$dir/${Destination.tempName(9)}"))
                assertTrue("already gone counts as removed", u.removeLeftover("$dir/${Destination.tempName(9)}"))
                assertTrue(u.removeLeftover("$dir/IMG.jpg")) // not ours: skipped, not deleted
            }
        }
        assertFalse(onDisk(Destination.tempName(9)).exists())
        assertTrue(onDisk("IMG.jpg").exists())
    }

    @Test(timeout = 60_000) fun refusesAServerWithAnotherIdBeforeLoggingIn() = runBlocking {
        val listing = SmbGateway.probeShare(target(), "")
        val id = listing.serverId
        assertNotNull("test server sends a server ID", id)
        listing.space?.let { (free, total) -> assertTrue(free in 0..total) }
        assertTrue("a user session is signed", listing.signed)
        val pinned = SmbTarget("127.0.0.1", "USB", "tester", "Dummy-Pass-123", port!!, expectedServerId = id)
        SmbGateway.withShare(pinned) { }

        val other = SmbTarget("127.0.0.1", "USB", "tester", "Dummy-Pass-123", port, expectedServerId = UUID.randomUUID())
        val failure = runCatching { SmbGateway.withShare(other) { } }.exceptionOrNull()
        assertEquals(SmbFailure.WrongServer, (failure as SmbFailureException).failure)
    }

    @Test(timeout = 60_000) fun cancellingMidUploadStopsPromptly() = runBlocking {
        // 4 GiB of zeros, produced on demand: far more than can be sent before we cancel.
        val size = 4L shl 30
        val big = LocalFile(4, "big.mp4", size) {
            object : InputStream() {
                var left = size
                override fun read() = if (left-- > 0) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = minOf(len.toLong(), left).toInt()
                    b.fill(0, off, off + n)
                    left -= n
                    return n
                }
            }
        }
        val moving = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            SmbGateway.withShare(target()) {
                Uploader(it.disk).upload(big, dir) { _, sent, _ -> if (sent > 10_000_000) moving.complete(Unit) }
            }
        }
        moving.await()
        val started = System.nanoTime()
        job.cancelAndJoin()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel took $tookMs ms", tookMs < 5_000)
        assertTrue(job.isCancelled)
        assertFalse(onDisk("big.mp4").exists())
    }
}
