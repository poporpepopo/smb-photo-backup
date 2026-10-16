package io.github.poporpepopo.photobackup.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens one connection per call. A backup run is a short burst every 15 minutes at most,
 * so there is nothing to gain from keeping a session alive between runs — and consumer
 * routers drop idle sessions anyway.
 */
object SmbGateway {

    private val config: SmbConfig = SmbConfig.builder()
        .withSecurityProvider(BCSecurityProvider())
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(60, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .build()

    /**
     * Connects, authenticates, opens the share and runs [block] on an IO thread. Any
     * failure is rethrown as [SmbFailureException].
     *
     * Cancellation interrupts [block] and force-closes the socket, so a call stuck in a
     * socket write (which an interrupt alone does not end) returns promptly too.
     */
    suspend fun <T> withShare(target: SmbTarget, block: (OpenShare) -> T): T =
        try {
            coroutineScope {
                val open = AtomicReference<Connection?>()
                val finished = AtomicBoolean(false)
                // The IO thread running [block], while it does. Guarded by itself, so it is
                // never interrupted after it has gone back to the pool.
                val worker = Worker()
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) {
                            runCatching { open.get()?.close(true) }
                            // One interrupt is used up by the first blocking call it ends.
                            // smbj's clean-up after that (closing the file, the share, the
                            // session) waits up to 30 s for replies that cannot come over
                            // the closed socket, so keep interrupting until it is done.
                            withContext(NonCancellable) {
                                while (!finished.get()) {
                                    worker.interrupt()
                                    delay(INTERRUPT_EVERY_MS)
                                }
                            }
                        }
                    }
                }
                try {
                    runInterruptible(Dispatchers.IO) {
                        worker.enter()
                        try {
                            connectAndRun(target, open, block)
                        } finally {
                            worker.leave()
                        }
                    }
                } finally {
                    finished.set(true)
                    closer.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SmbFailureException) {
            throw e
        } catch (e: Exception) {
            // An interrupted smbj call surfaces as its own exception type; if we were
            // cancelled, report that rather than a made-up SMB error.
            currentCoroutineContext().ensureActive()
            throw SmbFailureException(SmbFailure.classify(e), e)
        }

    private const val INTERRUPT_EVERY_MS = 100L

    private class Worker {
        private var thread: Thread? = null

        @Synchronized fun enter() {
            thread = Thread.currentThread()
        }

        @Synchronized fun leave() {
            thread = null
            Thread.interrupted() // drop a late interrupt so the pool thread starts clean
        }

        @Synchronized fun interrupt() {
            thread?.interrupt()
        }
    }

    private fun <T> connectAndRun(
        target: SmbTarget,
        open: AtomicReference<Connection?>,
        block: (OpenShare) -> T,
    ): T =
        SMBClient(config).use { client ->
            client.connect(target.host, target.port).use { conn ->
                open.set(conn)
                val serverId = serverIdOf(conn)
                val expected = target.expectedServerId
                if (expected != null && serverId != expected) {
                    throw SmbFailureException(SmbFailure.WrongServer)
                }
                val auth = if (target.username.isBlank()) {
                    AuthenticationContext.guest()
                } else {
                    AuthenticationContext(target.username, target.passwordChars(), null)
                }
                val session = conn.authenticate(auth)
                try {
                    (session.connectShare(target.share) as DiskShare).use { disk ->
                        block(OpenShare(disk, conn, serverId))
                    }
                } finally {
                    runCatching { session.close() }
                }
            }
        }

    /**
     * The server GUID from the negotiate reply, which arrives before we authenticate.
     * Null when the server sends all zeros, which identifies nothing.
     */
    private fun serverIdOf(conn: Connection): UUID? =
        conn.connectionContext.serverGuid?.takeUnless { it == UUID(0, 0) }

    /** An open share plus a way to tell a dead connection from a one-file failure. */
    class OpenShare(
        val disk: DiskShare,
        private val connection: Connection,
        /** See [SmbTarget.expectedServerId]. */
        val serverId: UUID?,
    ) {
        val isAlive: Boolean get() = connection.isConnected && disk.isConnected
    }

    data class ShareListing(
        val folders: List<String>,
        val destinationExists: Boolean,
        val serverId: UUID?,
        /** Free and total bytes on the share's disk; null if the server will not say. */
        val space: Pair<Long, Long>?,
    )

    /** The connection test: list the share root and check the destination folder. */
    suspend fun probeShare(target: SmbTarget, folder: String): ShareListing =
        withShare(target) { open ->
            val share = open.disk
            val folders = share.list("")
                .filter { it.fileName != "." && it.fileName != ".." }
                .filter {
                    EnumWithValue.EnumUtils.isSet(
                        it.fileAttributes,
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
                    )
                }
                .map { it.fileName }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            val exists = folder.isEmpty() || share.stat(RemotePath.toSmb(folder))?.isDirectory == true
            val space = runCatching { share.shareInformation }.getOrNull()
                ?.let { it.callerFreeSpace to it.totalSpace }
            ShareListing(folders, exists, open.serverId, space)
        }
}
