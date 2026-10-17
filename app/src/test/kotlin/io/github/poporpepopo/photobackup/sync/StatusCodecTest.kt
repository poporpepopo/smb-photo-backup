package io.github.poporpepopo.photobackup.sync

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.poporpepopo.photobackup.backup.ItemError
import io.github.poporpepopo.photobackup.backup.NotReadyReason
import io.github.poporpepopo.photobackup.backup.SyncOutcome
import io.github.poporpepopo.photobackup.backup.SyncSummary
import io.github.poporpepopo.photobackup.smb.SmbFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusCodecTest {

    private val p = mutablePreferencesOf()

    private fun record(at: Long, outcome: SyncOutcome) = StatusCodec.write(p, at, outcome)
    private val status get() = StatusCodec.read(p)

    @Test fun emptyStoreHasNothing() {
        assertEquals(SyncStatus(), status)
    }

    @Test fun everyOutcomeSurvivesTheRoundTrip() {
        val summary = SyncSummary(3, 1, 2, ItemError("a.jpg", "boom"))
        val outcomes = listOf(
            SyncOutcome.Finished(summary),
            SyncOutcome.Finished(SyncSummary()),
            SyncOutcome.NotHome,
            SyncOutcome.Aborted(SmbFailure.Other("size mismatch"), summary),
            SyncOutcome.Stopped(summary),
            SyncOutcome.Stopped(SyncSummary()),
        ) + listOf(
            SmbFailure.Unreachable, SmbFailure.Timeout, SmbFailure.ConnectionLost,
            SmbFailure.AuthFailed, SmbFailure.ShareNotFound, SmbFailure.AccessDenied, SmbFailure.WrongServer, SmbFailure.DiskFull,
        ).map { SyncOutcome.Aborted(it, SyncSummary(uploaded = 1)) } +
            NotReadyReason.entries.map { SyncOutcome.NotReady(it) }

        outcomes.forEachIndexed { i, o ->
            record(i.toLong(), o)
            assertEquals(RunRecord(i.toLong(), o), status.lastRun)
        }
    }

    @Test fun aStopIsNeitherASyncNorAnError() {
        record(100, SyncOutcome.Finished(SyncSummary(uploaded = 2)))
        record(200, SyncOutcome.Stopped(SyncSummary(uploaded = 1)))
        assertEquals(100L, status.lastSyncAt)
        assertEquals(null, status.lastError)
    }

    @Test fun finishedPassSetsLastSyncTime() {
        record(100, SyncOutcome.Finished(SyncSummary(uploaded = 2)))
        record(200, SyncOutcome.NotHome)
        record(300, SyncOutcome.Aborted(SmbFailure.ConnectionLost, SyncSummary()))
        assertEquals(100L, status.lastSyncAt)
    }

    @Test fun errorIsKeptUntilAnotherErrorReplacesIt() {
        val aborted = SyncOutcome.Aborted(SmbFailure.AuthFailed, SyncSummary())
        record(100, aborted)
        record(200, SyncOutcome.Finished(SyncSummary(uploaded = 1)))
        record(300, SyncOutcome.NotHome)
        assertEquals(RunRecord(100, aborted), status.lastError)

        val itemFailure = SyncOutcome.Finished(SyncSummary(failed = 1, lastItemError = ItemError("b.mp4", "x")))
        record(400, itemFailure)
        assertEquals(RunRecord(400, itemFailure), status.lastError)
    }

    @Test fun fieldsOfAnEarlierRunDoNotLeakIntoTheNextOne() {
        record(100, SyncOutcome.Finished(SyncSummary(1, 2, 3, ItemError("a.jpg", "boom"))))
        record(200, SyncOutcome.Finished(SyncSummary(uploaded = 4)))
        assertEquals(RunRecord(200, SyncOutcome.Finished(SyncSummary(uploaded = 4))), status.lastRun)
    }

    @Test fun notHomeIsNotAnError() {
        record(100, SyncOutcome.NotHome)
        assertNull(status.lastError)
        assertNull(status.lastSyncAt)
    }
}
