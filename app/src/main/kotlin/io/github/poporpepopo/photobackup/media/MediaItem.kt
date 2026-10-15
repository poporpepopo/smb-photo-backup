package io.github.poporpepopo.photobackup.media

import android.net.Uri

data class MediaItem(
    /** MediaStore _ID. Unique across images and videos (they share one table). */
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    /** As MediaStore reports it; the real length is read from the file at upload time. */
    val size: Long,
    /** Epoch millis, or null when MediaStore has no capture time. */
    val dateTakenMillis: Long?,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
)
