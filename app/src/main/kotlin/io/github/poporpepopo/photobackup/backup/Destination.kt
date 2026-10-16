package io.github.poporpepopo.photobackup.backup

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Where on the share a media item goes. Pure, so it can be unit tested on the JVM. */
object Destination {

    /**
     * "<base>/<yyyy>/<MM>". The month comes from, in order: MediaStore's capture time, the
     * capture month read from the file's EXIF ([exifMonth]; MediaStore drops capture times
     * that lack a time zone), and the time the file was added. Instants are read in [zone]
     * (the phone's time zone), which is what a person means by "the photos from October".
     */
    fun directory(
        baseFolder: String,
        dateTakenMillis: Long?,
        dateAddedSeconds: Long,
        zone: ZoneId,
        exifMonth: YearMonth? = null,
    ): String {
        val month = dateTakenMillis?.takeIf { it > 0 }
            ?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }
            ?: exifMonth
            ?: YearMonth.from(Instant.ofEpochSecond(dateAddedSeconds).atZone(zone))
        val yearMonth = "%04d/%02d".format(month.year, month.monthValue)
        return if (baseFolder.isEmpty()) yearMonth else "$baseFolder/$yearMonth"
    }

    /**
     * The year and month of an EXIF date ("yyyy:MM:dd HH:mm:ss", local wall-clock time at
     * the camera). Null for anything else, including the all-zero placeholder some cameras
     * write. No time zone is involved: the camera's own calendar month is the answer.
     */
    fun exifYearMonth(value: String?): YearMonth? {
        val m = EXIF_DATE.matchEntire(value?.trim() ?: return null) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (year !in 1900..2999 || month !in 1..12) return null
        return YearMonth.of(year, month)
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

    fun isTempName(name: String): Boolean = TEMP_NAME.matches(name)

    private val TEMP_NAME = Regex("""\.photobackup-\d+\.part""")

    private val EXIF_DATE = Regex("""(\d{4})[:-](\d{2})[:-]\d{2}[ T].*""")

    private val FORBIDDEN = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
}
