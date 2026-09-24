package com.hermesandroid.relay.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Manifest payload returned by dl.onepve.com/hermes-relay/version.json
 */
@Serializable
data class VersionManifest(
    @SerialName("version") val version: String,
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("versioned_download_url") val versionedDownloadUrl: String? = null,
    @SerialName("sha256") val sha256: String = "",
    @SerialName("md5") val md5: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("changelog") val changelog: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
)

/**
 * A parsed, UI-ready representation of an available update.
 */
data class AvailableUpdate(
    val latestVersion: String,
    val currentVersion: String,
    val releasePageUrl: String,
    val apkUrl: String?,
    val publishedAt: String?,
)

sealed class UpdateCheckResult {
    data object Idle : UpdateCheckResult()
    data object Checking : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Available(val update: AvailableUpdate) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}
