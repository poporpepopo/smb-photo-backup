package io.github.poporpepopo.photobackup.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaAccess {
    /** Every photo (and video) is visible. */
    Full,

    /** Android 14+ "Select photos and videos": only what the user picked is visible. */
    Partial,

    None,
}

data class MediaPermissionState(
    val access: MediaAccess,
    /** ACCESS_MEDIA_LOCATION: needed to read originals with their GPS tags intact. */
    val location: Boolean,
) {
    val readyForBackup: Boolean get() = access == MediaAccess.Full && location
}

object MediaPermissions {

    /** What to ask for. Videos are always requested so the switch can be flipped later. */
    fun toRequest(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            // Declaring this opts in to the partial-access model on Android 14+, which lets
            // us tell "picked a few photos" apart from "allowed all".
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    fun state(context: Context, includeVideos: Boolean): MediaPermissionState {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

        val access = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
            val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
            when {
                images && (videos || !includeVideos) -> MediaAccess.Full
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
                // Images allowed but videos not, while videos are wanted: not everything.
                images -> MediaAccess.Partial
                else -> MediaAccess.None
            }
        } else {
            if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.Full else MediaAccess.None
        }
        return MediaPermissionState(access, granted(Manifest.permission.ACCESS_MEDIA_LOCATION))
    }
}
