package io.github.poporpepopo.photobackup.smb

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

    @Test fun anythingElseKeepsItsMessage() {
        assertEquals(SmbFailure.Other("size mismatch"), SmbFailure.classify(IOException("size mismatch")))
    }
}
