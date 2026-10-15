package io.github.poporpepopo.photobackup.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
     */
    suspend fun <T> withShare(target: SmbTarget, block: (OpenShare) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                SMBClient(config).use { client ->
                    client.connect(target.host, target.port).use { conn ->
                        val auth = if (target.username.isBlank()) {
                            AuthenticationContext.guest()
                        } else {
                            AuthenticationContext(target.username, target.passwordChars(), null)
                        }
                        val session = conn.authenticate(auth)
                        try {
                            (session.connectShare(target.share) as DiskShare).use { disk ->
                                block(OpenShare(disk, conn))
                            }
                        } finally {
                            runCatching { session.close() }
                        }
                    }
                }
            } catch (e: SmbFailureException) {
                throw e
            } catch (e: Exception) {
                throw SmbFailureException(SmbFailure.classify(e), e)
            }
        }

    /** An open share plus a way to tell a dead connection from a one-file failure. */
    class OpenShare(val disk: DiskShare, private val connection: Connection) {
        val isAlive: Boolean get() = connection.isConnected && disk.isConnected
    }

    data class ShareListing(val folders: List<String>, val destinationExists: Boolean)

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
            ShareListing(folders, exists)
        }
}
