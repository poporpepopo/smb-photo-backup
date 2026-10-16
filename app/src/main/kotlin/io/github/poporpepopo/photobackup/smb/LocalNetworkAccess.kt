package io.github.poporpepopo.photobackup.smb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 puts the local network behind a runtime permission (in the "Nearby devices"
 * group). Without it, connections to private addresses such as the router's simply time
 * out, which would look exactly like "not at home".
 */
object LocalNetworkAccess {
    // As a string: the constant only exists in the API 37 SDK stubs.
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Earlier versions have no such permission and let every app reach the LAN. */
    val required: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun granted(context: Context): Boolean = !required ||
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
