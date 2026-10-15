package io.github.poporpepopo.photobackup.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathTest {

    @Test fun blankMeansShareRoot() {
        assertEquals("", RemotePath.normalizeFolder(""))
        assertEquals("", RemotePath.normalizeFolder("  /  "))
    }

    @Test fun stripsLeadingAndTrailingSeparators() {
        assertEquals("Backup/Pixel", RemotePath.normalizeFolder("/Backup/Pixel/"))
    }

    @Test fun acceptsBackslashesAndCollapsesRepeats() {
        assertEquals("Backup/Pixel", RemotePath.normalizeFolder("\\Backup\\\\Pixel"))
        assertEquals("Backup/Pixel", RemotePath.normalizeFolder("Backup//./Pixel"))
    }

    @Test fun trimsEachSegment() {
        assertEquals("My Photos/2026", RemotePath.normalizeFolder(" My Photos / 2026 "))
    }

    @Test fun toSmbUsesBackslashes() {
        assertEquals("Backup\\Pixel\\2026", RemotePath.toSmb("Backup/Pixel/2026"))
    }
}
