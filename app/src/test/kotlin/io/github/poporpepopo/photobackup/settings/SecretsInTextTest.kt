package io.github.poporpepopo.photobackup.settings

import io.github.poporpepopo.photobackup.smb.SmbTarget
import io.github.poporpepopo.photobackup.ui.SettingsForm
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The objects that carry the password must not reveal it when printed: string templates,
 * exception messages and crash reports all go through toString().
 */
class SecretsInTextTest {
    private val secret = "Marker-Pw-7319"

    @Test fun smbTarget() {
        val text = SmbTarget("192.168.50.1", "USB", "me", secret).toString()
        assertFalse(text, text.contains(secret))
    }

    @Test fun storedPassword() {
        val text = StoredPassword.Available(secret).toString()
        assertFalse(text, text.contains(secret))
    }

    @Test fun settingsForm() {
        val text = SettingsForm(host = "h", share = "s", newPassword = secret).toString()
        assertFalse(text, text.contains(secret))
    }
}
