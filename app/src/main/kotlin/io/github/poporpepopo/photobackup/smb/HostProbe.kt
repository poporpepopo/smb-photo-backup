package io.github.poporpepopo.photobackup.smb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object HostProbe {
    const val SMB_PORT = 445
    const val DEFAULT_TIMEOUT_MS = 1500

    /**
     * True when a TCP connection to [host]:[port] succeeds within [timeoutMs]. Used as the
     * "are we at home?" check: on any other network the router's private IP is either
     * unroutable or belongs to someone else's box with 445 closed.
     */
    suspend fun isReachable(
        host: String,
        port: Int = SMB_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        }.isSuccess
    }
}
