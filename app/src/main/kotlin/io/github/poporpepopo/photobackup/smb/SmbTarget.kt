package io.github.poporpepopo.photobackup.smb

/** Where to connect and as whom. toString() never includes the password. */
class SmbTarget(
    val host: String,
    val share: String,
    val username: String,
    private val password: String,
    /** Always 445 in the app; tests point it at a local server on an unprivileged port. */
    val port: Int = HostProbe.SMB_PORT,
) {
    internal fun passwordChars(): CharArray = password.toCharArray()

    override fun toString() = "SmbTarget(//$host/$share as ${username.ifBlank { "guest" }})"
}
