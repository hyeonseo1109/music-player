package com.hendo.hendomusic.library

/**
 * Android 11+ only requires a user write grant for media owned by another app.
 * Asking for a grant for our own MediaStore output can complete without granting a new token,
 * leaving the pending editor action stranded on some vendor builds.
 */
internal fun requiresMediaWriteApproval(
    sdkInt: Int,
    mediaStoreId: Long?,
    ownerPackageName: String?,
    appPackageName: String,
): Boolean = mediaStoreId != null && sdkInt >= 30 && ownerPackageName != appPackageName
