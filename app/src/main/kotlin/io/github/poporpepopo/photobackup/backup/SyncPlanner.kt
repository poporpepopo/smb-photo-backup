package io.github.poporpepopo.photobackup.backup

/** The parts of a MediaStore item that decide whether it has changed since upload. */
data class MediaVersion(val id: Long, val size: Long, val dateModifiedSeconds: Long)

object SyncPlanner {

    /**
     * True if [current] is already on the share as recorded by [record]. A different size
     * or modification time means the photo was edited after upload; the edited version
     * is sent again (and, since its bytes differ, lands next to the old one as "name (n)").
     */
    fun isUploaded(current: MediaVersion, record: MediaVersion?): Boolean =
        record != null &&
            record.id == current.id &&
            record.size == current.size &&
            record.dateModifiedSeconds == current.dateModifiedSeconds

    /** The items of [items] still to send, in their original order. */
    fun <T> pending(
        items: List<T>,
        records: Map<Long, MediaVersion>,
        version: (T) -> MediaVersion,
    ): List<T> = items.filterNot { item ->
        val v = version(item)
        isUploaded(v, records[v.id])
    }
}
