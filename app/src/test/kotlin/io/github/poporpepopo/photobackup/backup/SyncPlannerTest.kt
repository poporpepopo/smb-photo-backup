package io.github.poporpepopo.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private val photo = MediaVersion(id = 10, size = 2_000_000, dateModifiedSeconds = 1_790_000_000)

    @Test fun neverUploaded() {
        assertFalse(SyncPlanner.isUploaded(photo, null))
    }

    @Test fun unchangedSinceUpload() {
        assertTrue(SyncPlanner.isUploaded(photo, photo.copy()))
    }

    @Test fun editedSoTheSizeChanged() {
        assertFalse(SyncPlanner.isUploaded(photo.copy(size = 2_100_000), photo))
    }

    @Test fun editedButSameSize() {
        assertFalse(SyncPlanner.isUploaded(photo.copy(dateModifiedSeconds = photo.dateModifiedSeconds + 60), photo))
    }

    @Test fun recordForAnotherItemDoesNotCount() {
        assertFalse(SyncPlanner.isUploaded(photo, photo.copy(id = 11)))
    }

    @Test fun pendingKeepsOrderAndSkipsUploaded() {
        val a = MediaVersion(1, 100, 1000)
        val b = MediaVersion(2, 200, 2000)
        val c = MediaVersion(3, 300, 3000)
        val d = MediaVersion(4, 400, 4000)
        val records = mapOf(
            2L to b,                               // unchanged
            3L to c.copy(size = 299),              // edited since
            9L to MediaVersion(9, 1, 1),           // deleted from the phone; ignored
        )
        assertEquals(listOf(a, c, d), SyncPlanner.pending(listOf(a, b, c, d), records) { it })
    }

    @Test fun nothingPendingWhenAllUploaded() {
        val items = (1L..50L).map { MediaVersion(it, it * 10, it * 100) }
        val records = items.associateBy { it.id }
        assertTrue(SyncPlanner.pending(items, records) { it }.isEmpty())
    }

    @Test fun recordFromTheDatabaseMapsToTheSameVersion() {
        val r = UploadedRecord(10, 2_000_000, 1_790_000_000, "B/2026/10/a.jpg", 0)
        assertTrue(SyncPlanner.isUploaded(photo, r.version))
    }
}
