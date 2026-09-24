package com.hermesandroid.relay.update

import com.hermesandroid.relay.BuildConfig
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.CandidateBuild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cloudflare R2 Update Checker.
 *
 * Fetches the latest Android version manifest from https://dl.onepve.com/hermes-relay/version.json
 * and returns an [UpdateCheckResult].
 */
object UpdateChecker {
    private const val MANIFEST_URL = "https://dl.onepve.com/hermes-relay/version.json"
    private const val USER_AGENT = "hermes-relay-android"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!shouldQueryStableReleases(BuildFlavor.isSideload, CandidateBuild.isCandidate)) {
            return@withContext UpdateCheckResult.UpToDate
        }

        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "$USER_AGENT/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult.Error(
                        "dl.onepve.com returned HTTP ${resp.code}"
                    )
                }
                val body = resp.body.string()
                if (body.isBlank()) {
                    return@withContext UpdateCheckResult.Error("Empty response body")
                }
                val manifest = json.decodeFromString<VersionManifest>(body)

                val latest = manifest.version.removePrefix("v")
                val current = BuildConfig.VERSION_NAME.removePrefix("v")
                val cmp = compareVersions(current, latest)
                if (cmp >= 0) {
                    return@withContext UpdateCheckResult.UpToDate
                }

                return@withContext UpdateCheckResult.Available(
                    AvailableUpdate(
                        latestVersion = latest,
                        currentVersion = current,
                        releasePageUrl = manifest.versionedDownloadUrl ?: manifest.downloadUrl,
                        apkUrl = manifest.downloadUrl,
                        publishedAt = manifest.publishedAt,
                    )
                )
            }
        } catch (t: Throwable) {
            return@withContext UpdateCheckResult.Error(
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    internal fun shouldQueryStableReleases(
        isSideload: Boolean,
        isCandidate: Boolean,
    ): Boolean = isSideload && !isCandidate
}
