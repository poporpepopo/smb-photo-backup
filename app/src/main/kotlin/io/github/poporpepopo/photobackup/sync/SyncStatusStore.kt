package io.github.poporpepopo.photobackup.sync

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.poporpepopo.photobackup.backup.ItemError
import io.github.poporpepopo.photobackup.backup.NotReadyReason
import io.github.poporpepopo.photobackup.backup.SyncOutcome
import io.github.poporpepopo.photobackup.backup.SyncSummary
import io.github.poporpepopo.photobackup.smb.SmbFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RunRecord(val atMillis: Long, val outcome: SyncOutcome)

data class SyncStatus(
    /** The last pass that reached the share and tried every pending item. */
    val lastSyncAt: Long? = null,
    /** The most recent pass, including "not at home". */
    val lastRun: RunRecord? = null,
    /** The most recent pass that ended with an error; a clean pass does not erase it. */
    val lastError: RunRecord? = null,
)

val SyncOutcome.hasError: Boolean
    get() = when (this) {
        is SyncOutcome.Finished -> summary.failed > 0
        is SyncOutcome.Aborted, is SyncOutcome.NotReady -> true
        SyncOutcome.NotHome, is SyncOutcome.Stopped -> false
    }

private val Context.statusStore by preferencesDataStore("sync_status")

/** Outcomes of background passes, kept across process restarts for the home screen. */
class SyncStatusStore(context: Context) {
    private val store = context.applicationContext.statusStore

    val status: Flow<SyncStatus> = store.data.map(StatusCodec::read)

    suspend fun record(atMillis: Long, outcome: SyncOutcome) {
        store.edit { StatusCodec.write(it, atMillis, outcome) }
    }
}

/** Flattens outcomes into preference keys. Each record has its own key prefix. */
internal object StatusCodec {
    private const val RUN = "run"
    private const val ERROR = "error"
    private val SYNC_AT = longPreferencesKey("sync_at")

    fun write(p: MutablePreferences, atMillis: Long, outcome: SyncOutcome) {
        writeRun(p, RUN, atMillis, outcome)
        if (outcome is SyncOutcome.Finished) p[SYNC_AT] = atMillis
        if (outcome.hasError) writeRun(p, ERROR, atMillis, outcome)
    }

    fun read(p: Preferences) = SyncStatus(
        lastSyncAt = p[SYNC_AT],
        lastRun = readRun(p, RUN),
        lastError = readRun(p, ERROR),
    )

    private class Keys(prefix: String) {
        val at = longPreferencesKey("${prefix}_at")
        val kind = stringPreferencesKey("${prefix}_kind")
        val uploaded = intPreferencesKey("${prefix}_uploaded")
        val present = intPreferencesKey("${prefix}_present")
        val failed = intPreferencesKey("${prefix}_failed")
        val itemName = stringPreferencesKey("${prefix}_item_name")
        val itemDetail = stringPreferencesKey("${prefix}_item_detail")
        val code = stringPreferencesKey("${prefix}_code")
        val detail = stringPreferencesKey("${prefix}_detail")

        val all = listOf(at, kind, uploaded, present, failed, itemName, itemDetail, code, detail)
    }

    private fun writeRun(p: MutablePreferences, prefix: String, atMillis: Long, outcome: SyncOutcome) {
        val k = Keys(prefix)
        k.all.forEach { p.remove(it) }
        p[k.at] = atMillis
        fun summary(s: SyncSummary) {
            p[k.uploaded] = s.uploaded
            p[k.present] = s.alreadyPresent
            p[k.failed] = s.failed
            s.lastItemError?.let {
                p[k.itemName] = it.name
                p[k.itemDetail] = it.detail
            }
        }
        when (outcome) {
            is SyncOutcome.Finished -> {
                p[k.kind] = "finished"
                summary(outcome.summary)
            }
            is SyncOutcome.Aborted -> {
                p[k.kind] = "aborted"
                summary(outcome.summary)
                p[k.code] = outcome.failure.code
                if (outcome.failure is SmbFailure.Other) p[k.detail] = outcome.failure.detail
            }
            is SyncOutcome.Stopped -> {
                p[k.kind] = "stopped"
                summary(outcome.summary)
            }
            SyncOutcome.NotHome -> p[k.kind] = "not_home"
            is SyncOutcome.NotReady -> {
                p[k.kind] = "not_ready"
                p[k.code] = outcome.reason.name
            }
        }
    }

    private fun readRun(p: Preferences, prefix: String): RunRecord? {
        val k = Keys(prefix)
        val at = p[k.at] ?: return null
        val summary = SyncSummary(
            uploaded = p[k.uploaded] ?: 0,
            alreadyPresent = p[k.present] ?: 0,
            failed = p[k.failed] ?: 0,
            lastItemError = p[k.itemName]?.let { ItemError(it, p[k.itemDetail].orEmpty()) },
        )
        val outcome = when (p[k.kind]) {
            "finished" -> SyncOutcome.Finished(summary)
            "aborted" -> SyncOutcome.Aborted(failureOf(p[k.code], p[k.detail]), summary)
            "stopped" -> SyncOutcome.Stopped(summary)
            "not_home" -> SyncOutcome.NotHome
            "not_ready" -> SyncOutcome.NotReady(
                NotReadyReason.entries.firstOrNull { it.name == p[k.code] } ?: return null,
            )
            else -> return null
        }
        return RunRecord(at, outcome)
    }

    private val SmbFailure.code: String
        get() = when (this) {
            SmbFailure.Unreachable -> "unreachable"
            SmbFailure.Timeout -> "timeout"
            SmbFailure.ConnectionLost -> "connection_lost"
            SmbFailure.AuthFailed -> "auth"
            SmbFailure.ShareNotFound -> "share_not_found"
            SmbFailure.AccessDenied -> "access_denied"
            SmbFailure.WrongServer -> "wrong_server"
            SmbFailure.DiskFull -> "disk_full"
            is SmbFailure.Other -> "other"
        }

    private fun failureOf(code: String?, detail: String?): SmbFailure = when (code) {
        "unreachable" -> SmbFailure.Unreachable
        "timeout" -> SmbFailure.Timeout
        "connection_lost" -> SmbFailure.ConnectionLost
        "auth" -> SmbFailure.AuthFailed
        "share_not_found" -> SmbFailure.ShareNotFound
        "access_denied" -> SmbFailure.AccessDenied
        "wrong_server" -> SmbFailure.WrongServer
        "disk_full" -> SmbFailure.DiskFull
        else -> SmbFailure.Other(detail ?: code.orEmpty())
    }
}
