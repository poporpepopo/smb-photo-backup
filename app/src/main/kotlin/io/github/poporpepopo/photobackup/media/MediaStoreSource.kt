package io.github.poporpepopo.photobackup.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Read-only access to the phone's photos and videos. Nothing here opens a file for
 * writing or calls delete/update: the phone's copies are never touched.
 */
class MediaStoreSource(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Newest first (by date added). [limit] caps each of the two collections. */
    fun query(includeVideos: Boolean, limit: Int? = null): List<MediaItem> {
        val items = queryCollection(IMAGES, isVideo = false, limit)
        if (!includeVideos) return items
        return (items + queryCollection(VIDEOS, isVideo = true, limit))
            .sortedWith(compareByDescending<MediaItem> { it.dateAddedSeconds }.thenByDescending { it.id })
    }

    fun count(collection: Uri): Int =
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count } ?: 0

    fun countImages() = count(IMAGES)
    fun countVideos() = count(VIDEOS)

    /**
     * The URI that yields the original bytes, including the GPS tags MediaStore otherwise
     * redacts. Opening it needs ACCESS_MEDIA_LOCATION; without it the open throws, which is
     * what we want — uploading a silently redacted copy would lose the location for good.
     */
    private fun originalUri(item: MediaItem): Uri = MediaStore.setRequireOriginal(item.uri)

    /** The file's actual length, which can differ from MediaStore's cached SIZE. */
    fun originalSize(item: MediaItem): Long =
        resolver.openFileDescriptor(originalUri(item), "r")?.use { it.statSize }
            ?: throw FileNotFoundException(item.uri.toString())

    fun openOriginal(item: MediaItem): InputStream =
        resolver.openInputStream(originalUri(item))
            ?: throw FileNotFoundException(item.uri.toString())

    /**
     * The capture date written in the photo's EXIF, for photos MediaStore has no capture
     * time for. Opened read-only; the file is never written.
     */
    fun exifCaptureDate(item: MediaItem): String? =
        resolver.openFileDescriptor(item.uri, "r")?.use { fd ->
            val exif = ExifInterface(fd.fileDescriptor)
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        }

    private fun queryCollection(collection: Uri, isVideo: Boolean, limit: Int?): List<MediaItem> {
        val args = Bundle().apply {
            // MediaProvider ignores QUERY_ARG_SORT_COLUMNS (it silently returns _ID order),
            // so the order has to be given as SQL.
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
            )
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = mutableListOf<MediaItem>()
        resolver.query(collection, PROJECTION, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val itemId = c.getLong(id)
                out += MediaItem(
                    id = itemId,
                    uri = ContentUris.withAppendedId(collection, itemId),
                    isVideo = isVideo,
                    displayName = c.getString(name).orEmpty(),
                    size = c.getLong(size),
                    dateTakenMillis = if (c.isNull(taken)) null else c.getLong(taken),
                    dateAddedSeconds = c.getLong(added),
                    dateModifiedSeconds = c.getLong(modified),
                )
            }
        }
        return out
    }

    companion object {
        // VOLUME_EXTERNAL spans every external volume, SD cards included.
        val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}
