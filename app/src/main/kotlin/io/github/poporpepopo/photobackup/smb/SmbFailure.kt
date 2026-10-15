package io.github.poporpepopo.photobackup.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/** What went wrong, at the granularity the user can act on. */
sealed interface SmbFailure {
    data object Unreachable : SmbFailure
    data object Timeout : SmbFailure
    /** The connection dropped mid-way (router rebooted, Wi-Fi lost, ...). */
    data object ConnectionLost : SmbFailure
    data object AuthFailed : SmbFailure
    data object ShareNotFound : SmbFailure
    data object AccessDenied : SmbFailure
    data class Other(val detail: String) : SmbFailure

    companion object {
        fun classify(e: Throwable): SmbFailure {
            // smbj wraps the interesting exception a few levels deep (TransportException,
            // ExecutionException, ...), so look through the whole cause chain.
            for (t in generateSequence(e) { it.cause }) {
                when (t) {
                    is SMBApiException -> return fromStatus(t.status) ?: Other(t.status.name)
                    is SocketTimeoutException, is TimeoutException -> return Timeout
                    is java.net.ConnectException, is java.net.NoRouteToHostException -> return Unreachable
                }
            }
            // Checked after the loop so a more specific cause (timeout, refused) wins.
            for (t in generateSequence(e) { it.cause }) {
                if (t is EOFException || t is SocketException || t is TransportException) {
                    return ConnectionLost
                }
            }
            return Other(e.message ?: e.javaClass.simpleName)
        }

        private fun fromStatus(s: NtStatus): SmbFailure? = when (s) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_PASSWORD_EXPIRED -> AuthFailed
            NtStatus.STATUS_BAD_NETWORK_NAME -> ShareNotFound
            NtStatus.STATUS_ACCESS_DENIED -> AccessDenied
            else -> null
        }
    }
}

class SmbFailureException(val failure: SmbFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)
