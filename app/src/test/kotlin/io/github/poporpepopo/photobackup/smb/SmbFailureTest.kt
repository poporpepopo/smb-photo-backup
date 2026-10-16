package io.github.poporpepopo.photobackup.smb

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mserref.NtStatus
import com.hierynomus.protocol.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException

class SmbFailureTest {

    @Test fun droppedConnectionDeepInTheCauseChain() {
        // The shape smbj produced when the server went away mid-upload.
        val e = TransportException(
            ExecutionException(RuntimeException(TransportException(EOFException("EOF while reading packet")))),
        )
        assertEquals(SmbFailure.ConnectionLost, SmbFailure.classify(e))
    }

    @Test fun timeoutWinsOverTheTransportWrapper() {
        assertEquals(SmbFailure.Timeout, SmbFailure.classify(TransportException(SocketTimeoutException())))
    }

    @Test fun refusedConnectionIsUnreachable() {
        assertEquals(SmbFailure.Unreachable, SmbFailure.classify(TransportException(ConnectException())))
    }

    @Test fun fullDiskIsItsOwnFailure() {
        val e = SMBApiException(NtStatus.STATUS_DISK_FULL.value, SMB2MessageCommandCode.SMB2_WRITE, null)
        assertEquals(SmbFailure.DiskFull, SmbFailure.classify(IOException("write failed", e)))
    }

    @Test fun anythingElseKeepsItsMessage() {
        assertEquals(SmbFailure.Other("size mismatch"), SmbFailure.classify(IOException("size mismatch")))
    }
}
