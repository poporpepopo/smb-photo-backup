package io.github.poporpepopo.photobackup.backup

import io.github.poporpepopo.photobackup.smb.SmbGateway
import io.github.poporpepopo.photobackup.smb.SmbTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

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
        SmbGateway.withShare(target()) { Uploader(it).upload(f, dir) }
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

    @Test(timeout = 60_000) fun emptyFile() {
        assertEquals(UploadResult.Uploaded("$dir/empty.jpg", 0), upload(local(3, ByteArray(0), "empty.jpg")))
        assertEquals(UploadResult.AlreadyPresent("$dir/empty.jpg"), upload(local(3, ByteArray(0), "empty.jpg")))
    }
}
