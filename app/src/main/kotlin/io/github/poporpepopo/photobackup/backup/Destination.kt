package io.github.poporpepopo.photobackup.backup

import java.time.Instant
import java.time.ZoneId

/** Where on the share a media item goes. Pure, so it can be unit tested on the JVM. */
object Destination {

    /**
     * "<base>/<yyyy>/<MM>", using the capture time when MediaStore has one and the time the
     * file was added otherwise. The date is taken in [zone] (the phone's time zone), which
     * is what a person means by "the photos from October".
     */
    fun directory(
        baseFolder: String,
        dateTakenMillis: Long?,
        dateAddedSeconds: Long,
        zone: ZoneId,
    ): String {
        val instant = dateTakenMillis?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            ?: Instant.ofEpochSecond(dateAddedSeconds)
        val date = instant.atZone(zone).toLocalDate()
        val yearMonth = "%04d/%02d".format(date.year, date.monthValue)
        return if (baseFolder.isEmpty()) yearMonth else "$baseFolder/$yearMonth"
    }

    /**
     * Makes a MediaStore display name safe for SMB: no characters Windows/Samba reject, no
     * trailing dots or spaces (which Samba silently strips, turning "a." into "a").
     */
    fun sanitizeFileName(name: String, fallback: String): String {
        val cleaned = name
            .map { c -> if (c in FORBIDDEN || c.code < 0x20) '_' else c }
            .joinToString("")
            .trimEnd('.', ' ')
            .trimStart(' ')
        return cleaned.ifEmpty { fallback }
    }

    /** n = 0 is the name itself; n >= 1 inserts " (n)" before the extension. */
    fun candidateName(name: String, n: Int): String {
        if (n == 0) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        } else {
            "$name ($n)"
        }
    }

    /**
     * Temporary name used while uploading. Dot-prefixed so it is hidden in most browsers,
     * and keyed by the MediaStore ID so a leftover from an interrupted run is overwritten
     * by the retry instead of piling up.
     */
    fun tempName(mediaId: Long): String = ".photobackup-$mediaId.part"

    private val FORBIDDEN = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
}
