package io.github.poporpepopo.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSampleTest {

    @Test fun photosAreComparedInFull() {
        assertEquals(listOf(0L to 5_000_000L), ContentSample.ranges(5_000_000))
        assertEquals(listOf(0L to ContentSample.FULL_COMPARE_MAX), ContentSample.ranges(ContentSample.FULL_COMPARE_MAX))
        assertEquals(listOf(0L to 0L), ContentSample.ranges(0))
        assertFalse(ContentSample.isPartial(ContentSample.FULL_COMPARE_MAX))
    }

    @Test fun largeFilesAreComparedAtTheEdgesAndEightPlacesBetween() {
        for (size in listOf(ContentSample.FULL_COMPARE_MAX + 1, 300L shl 20, 3_345_247_797L)) {
            assertTrue(ContentSample.isPartial(size))
            val ranges = ContentSample.ranges(size)
            assertEquals(10, ranges.size)
            assertEquals(0L, ranges.first().first)
            assertEquals(size, ranges.last().second)
            ranges.zipWithNext { a, b -> assertTrue("$a then $b", a.second < b.first) }
            // About 4 MB whatever the size.
            assertEquals(4L shl 20, ranges.sumOf { (start, end) -> end - start })
        }
    }
}
