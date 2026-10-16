package io.github.poporpepopo.photobackup.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class DestinationTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun millis(y: Int, m: Int, d: Int, h: Int, zone: ZoneId = tokyo) =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun usesDateTaken() {
        val taken = millis(2026, 3, 14, 12)
        val added = millis(2026, 10, 1, 12) / 1000
        assertEquals("Backup/2026/03", Destination.directory("Backup", taken, added, tokyo))
    }

    @Test fun fallsBackToDateAddedWhenTakenIsMissing() {
        val added = millis(2025, 12, 31, 9) / 1000
        assertEquals("Backup/2025/12", Destination.directory("Backup", null, added, tokyo))
    }

    @Test fun treatsZeroOrNegativeDateTakenAsMissing() {
        val added = millis(2024, 7, 1, 9) / 1000
        assertEquals("B/2024/07", Destination.directory("B", 0, added, tokyo))
        assertEquals("B/2024/07", Destination.directory("B", -1, added, tokyo))
    }

    @Test fun monthBoundaryFollowsThePhonesTimeZone() {
        // 2026-01-01 00:30 in Tokyo is still 2025-12-31 in UTC.
        val taken = millis(2026, 1, 1, 0) + 30 * 60_000
        assertEquals("B/2026/01", Destination.directory("B", taken, 0, tokyo))
        assertEquals("B/2025/12", Destination.directory("B", taken, 0, ZoneId.of("UTC")))
    }

    @Test fun emptyBaseFolderMeansShareRoot() {
        val taken = millis(2026, 10, 15, 12)
        assertEquals("2026/10", Destination.directory("", taken, 0, tokyo))
    }

    @Test fun nestedBaseFolder() {
        val taken = millis(2026, 10, 15, 12)
        assertEquals("Backup/Pixel/2026/10", Destination.directory("Backup/Pixel", taken, 0, tokyo))
    }

    @Test fun exifMonthFillsInWhenMediaStoreHasNoCaptureTime() {
        val added = millis(2026, 10, 16, 9) / 1000
        val exif = YearMonth.of(2019, 8)
        assertEquals("B/2019/08", Destination.directory("B", null, added, tokyo, exif))
    }

    @Test fun mediaStoreCaptureTimeBeatsExif() {
        val taken = millis(2026, 3, 14, 12)
        assertEquals("B/2026/03", Destination.directory("B", taken, 0, tokyo, YearMonth.of(2019, 8)))
    }

    @Test fun exifYearMonthReadsTheCalendarMonthAsWritten() {
        assertEquals(YearMonth.of(2019, 8), Destination.exifYearMonth("2019:08:31 23:59:59"))
        // No time zone is applied: 1 January 00:10 stays in January wherever the phone is.
        assertEquals(YearMonth.of(2020, 1), Destination.exifYearMonth("2020:01:01 00:10:00"))
        assertEquals(YearMonth.of(2021, 5), Destination.exifYearMonth(" 2021-05-02T10:00:00 "))
    }

    @Test fun exifYearMonthRejectsPlaceholdersAndJunk() {
        assertNull(Destination.exifYearMonth(null))
        assertNull(Destination.exifYearMonth(""))
        assertNull(Destination.exifYearMonth("0000:00:00 00:00:00"))
        assertNull(Destination.exifYearMonth("    :  :     :  :  "))
        assertNull(Destination.exifYearMonth("2019:13:01 00:00:00"))
        assertNull(Destination.exifYearMonth("2019:08:31"))
    }

    @Test fun recognisesOnlyOurTempNames() {
        assertTrue(Destination.isTempName(Destination.tempName(1234)))
        assertFalse(Destination.isTempName("IMG.jpg"))
        assertFalse(Destination.isTempName(".photobackup-.part"))
        assertFalse(Destination.isTempName(".photobackup-12.part.jpg"))
        assertFalse(Destination.isTempName("x.photobackup-12.part"))
    }

    @Test fun sanitizeKeepsOrdinaryNames() {
        assertEquals("PXL_20261015_123456789.jpg", Destination.sanitizeFileName("PXL_20261015_123456789.jpg", "x"))
        assertEquals("写真 1.HEIC", Destination.sanitizeFileName("写真 1.HEIC", "x"))
    }

    @Test fun sanitizeReplacesForbiddenCharacters() {
        assertEquals("a_b_c_d_.jpg", Destination.sanitizeFileName("a:b*c?d|.jpg", "x"))
        assertEquals("a_b.png", Destination.sanitizeFileName("a\tb.png", "x"))
    }

    @Test fun sanitizeStripsTrailingDotsAndSpaces() {
        assertEquals("name", Destination.sanitizeFileName(" name. . ", "x"))
    }

    @Test fun sanitizeFallsBackWhenNothingIsLeft() {
        assertEquals("media_42", Destination.sanitizeFileName(" ..", "media_42"))
    }

    @Test fun candidateNames() {
        assertEquals("IMG_1.jpg", Destination.candidateName("IMG_1.jpg", 0))
        assertEquals("IMG_1 (1).jpg", Destination.candidateName("IMG_1.jpg", 1))
        assertEquals("IMG_1 (12).jpg", Destination.candidateName("IMG_1.jpg", 12))
        assertEquals("archive.tar (1).gz", Destination.candidateName("archive.tar.gz", 1))
        assertEquals("noext (2)", Destination.candidateName("noext", 2))
        assertEquals(".hidden (1)", Destination.candidateName(".hidden", 1))
    }
}
