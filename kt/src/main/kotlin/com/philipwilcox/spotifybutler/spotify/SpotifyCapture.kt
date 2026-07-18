package com.philipwilcox.spotifybutler.spotify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

const val SPOTIFY_CAPTURE_EVENT_MARKER = "SPOTIFY_CAPTURE_EVENT"

@Serializable
data class SpotifyCaptureEvent(
    val event: String,
    val runId: String,
    val sequence: Long,
    val method: String,
    val path: String,
    val status: Int,
    val pageSequence: Int,
    val body: String,
)

internal class SpotifyCaptureLogger(
    private val runId: String =
        System.getenv("SPOTIFY_BUTLER_CAPTURE_RUN_ID")?.trim()?.takeIf(String::isNotEmpty)
            ?: "run-${UUID.randomUUID()}",
) {
    private val sequence = AtomicLong()
    private val json = Json { encodeDefaults = true }

    fun successfulResponse(
        method: String,
        uri: URI,
        status: Int,
        pageSequence: Int,
        body: String,
    ): String =
        json.encodeToString(
            SpotifyCaptureEvent(
                event = "spotify.response",
                runId = runId,
                sequence = sequence.incrementAndGet(),
                method = method,
                path = capturePath(uri),
                status = status,
                pageSequence = pageSequence,
                body = body,
            ),
        )
}

internal fun capturePath(uri: URI): String {
    val query =
        uri.rawQuery
            ?.split('&')
            ?.filter(String::isNotBlank)
            ?.filterNot { queryParameterName(it).isSensitiveCaptureParameter() }
            ?.joinToString("&")
            ?.takeIf(String::isNotEmpty)
    return buildString {
        append(uri.rawPath.orEmpty().ifBlank { "/" })
        query?.let {
            append('?')
            append(it)
        }
    }
}

private fun queryParameterName(rawParameter: String): String =
    URLDecoder.decode(rawParameter.substringBefore('='), StandardCharsets.UTF_8).lowercase()

private fun String.isSensitiveCaptureParameter(): Boolean =
    contains("token") ||
        contains("secret") ||
        contains("authorization") ||
        contains("code") ||
        contains("cookie")
