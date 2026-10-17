package io.github.poporpepopo.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class NameResolverTest {

    /** name -> size; -1 marks a directory. */
    private class FakeDir(private val entries: Map<String, Long>) : RemoteDirectory {
        override fun sizeOf(name: String): Long? = entries[name]
    }

    private val size = 1234L

    @Test fun freeNameIsUsedAsIs() {
        assertEquals(NameResolution.Upload("IMG.jpg"), NameResolver.resolve(FakeDir(emptyMap()), "IMG.jpg", size))
    }

    @Test fun sameNameAndSizeIsTakenAsAlreadyPresent() {
        val dir = FakeDir(mapOf("IMG.jpg" to size))
        assertEquals(NameResolution.AlreadyPresent("IMG.jpg"), NameResolver.resolve(dir, "IMG.jpg", size))
    }

    @Test fun anotherSizeGetsANumberedName() {
        val dir = FakeDir(mapOf("IMG.jpg" to size + 1))
        assertEquals(NameResolution.Upload("IMG (1).jpg"), NameResolver.resolve(dir, "IMG.jpg", size))
    }

    @Test fun skipsOverSeveralTakenNames() {
        val dir = FakeDir(mapOf("IMG.jpg" to 1L, "IMG (1).jpg" to 2L, "IMG (2).jpg" to 3L))
        assertEquals(NameResolution.Upload("IMG (3).jpg"), NameResolver.resolve(dir, "IMG.jpg", size))
    }

    @Test fun findsTheSameSizeUnderANumberedName() {
        val dir = FakeDir(mapOf("IMG.jpg" to 1L, "IMG (1).jpg" to size))
        assertEquals(NameResolution.AlreadyPresent("IMG (1).jpg"), NameResolver.resolve(dir, "IMG.jpg", size))
    }

    @Test fun directoryWithTheSameNameIsSkipped() {
        val dir = FakeDir(mapOf("IMG.jpg" to -1L))
        assertEquals(NameResolution.Upload("IMG (1).jpg"), NameResolver.resolve(dir, "IMG.jpg", size))
    }
}
