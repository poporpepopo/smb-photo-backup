package io.github.poporpepopo.photobackup.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.poporpepopo.photobackup.R
import io.github.poporpepopo.photobackup.smb.SmbFailure

/** Everything a screen can report, at the granularity the user can act on. */
sealed interface UiError {
    data object HostRequired : UiError
    data object ShareRequired : UiError
    data object NotConfigured : UiError
    data object PasswordUnreadable : UiError
    data object NoPermission : UiError
    data object NoMedia : UiError
    data class Smb(val failure: SmbFailure) : UiError
}

@Composable
fun errorText(e: UiError, host: String): String = when (e) {
    UiError.HostRequired -> stringResource(R.string.error_host_required)
    UiError.ShareRequired -> stringResource(R.string.error_share_required)
    UiError.NotConfigured -> stringResource(R.string.error_not_configured)
    UiError.PasswordUnreadable -> stringResource(R.string.error_password_unreadable)
    UiError.NoPermission -> stringResource(R.string.error_no_permission)
    UiError.NoMedia -> stringResource(R.string.error_no_media)
    is UiError.Smb -> when (val f = e.failure) {
        SmbFailure.Unreachable -> stringResource(R.string.error_unreachable, host)
        SmbFailure.Timeout -> stringResource(R.string.error_timeout)
        SmbFailure.AuthFailed -> stringResource(R.string.error_auth)
        SmbFailure.ShareNotFound -> stringResource(R.string.error_share_not_found)
        SmbFailure.AccessDenied -> stringResource(R.string.error_access_denied)
        is SmbFailure.Other -> stringResource(R.string.error_other, f.detail)
    }
}
