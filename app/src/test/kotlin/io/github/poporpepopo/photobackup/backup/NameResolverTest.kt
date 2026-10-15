package io.github.poporpepopo.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameResolverTest {

    /** name -> content; a null content marks a directory. */
    private class FakeDir(
        private val entries: Map<String, ByteArray?>,
        private val local: ByteArray,
    ) : RemoteDirectory {
        val compared = mutableListOf<String>()

        override fun sizeOf(name: String): Long? {
            if (name !in entries) return null
            return entries[name]?.size?.toLong() ?: -1L
        }

        override fun sameContentAsLocal(name: String): Boolean {
            compared += name
            return entries[name].contentEquals(local)
        }
    }

    private val photo = "photo bytes".toByteArray()

    @Test fun freeNameIsUsedAsIs() {
        val dir = FakeDir(emptyMap(), photo)
        assertEquals(NameResolution.Upload("IMG.jpg"), NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong()))
    }

    @Test fun identicalFileIsReportedAsAlreadyPresent() {
        val dir = FakeDir(mapOf("IMG.jpg" to photo.copyOf()), photo)
        assertEquals(NameResolution.AlreadyPresent("IMG.jpg"), NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong()))
    }

    @Test fun differentContentGetsANumberedName() {
        val dir = FakeDir(mapOf("IMG.jpg" to "other".toByteArray()), photo)
        assertEquals(NameResolution.Upload("IMG (1).jpg"), NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong()))
    }

    @Test fun sameSizeButDifferentBytesGetsANumberedName() {
        val sameSize = ByteArray(photo.size) { 'x'.code.toByte() }
        val dir = FakeDir(mapOf("IMG.jpg" to sameSize), photo)
        assertEquals(NameResolution.Upload("IMG (1).jpg"), NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong()))
    }

    @Test fun skipsOverSeveralTakenNames() {
        val dir = FakeDir(
            mapOf(
                "IMG.jpg" to "a".toByteArray(),
                "IMG (1).jpg" to "b".toByteArray(),
                "IMG (2).jpg" to "c".toByteArray(),
            ),
            photo,
        )
        assertEquals(NameResolution.Upload("IMG (3).jpg"), NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong()))
    }

    @Test fun findsAnIdenticalCopyUnderANumberedName() {
        val dir = FakeDir(mapOf("IMG.jpg" to "a".toByteArray(), "IMG (1).jpg" to photo.copyOf()), photo)
        assertEquals(NameResolution.AlreadyPresent("IMG (1).jpg"), NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong()))
    }

    @Test fun directoryWithTheSameNameIsSkipped() {
        val dir = FakeDir(mapOf("IMG.jpg" to null), photo)
        assertEquals(NameResolution.Upload("IMG (1).jpg"), NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong()))
    }

    @Test fun contentIsOnlyComparedWhenSizesMatch() {
        val dir = FakeDir(mapOf("IMG.jpg" to "different length".toByteArray()), photo)
        NameResolver.resolve(dir, "IMG.jpg", photo.size.toLong())
        assertTrue(dir.compared.isEmpty())
    }
}
