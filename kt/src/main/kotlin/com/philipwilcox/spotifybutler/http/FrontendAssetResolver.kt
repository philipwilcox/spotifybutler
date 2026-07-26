package com.philipwilcox.spotifybutler.http

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class FrontendAsset(
    val bytes: ByteArray,
    val contentType: String,
    val cacheControl: String,
)

class FrontendAssetResolver(
    directory: Path,
) {
    private val root = directory.toAbsolutePath().normalize()

    fun resolve(rawPath: String): FrontendAsset? {
        val relativePath = safeRelativePath(rawPath) ?: return null
        val candidate = root.resolve(relativePath).normalize()
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        return FrontendAsset(
            bytes = Files.readAllBytes(candidate),
            contentType = contentType(candidate),
            cacheControl = if (candidate.fileName.toString() == "index.html") "no-cache" else "public, max-age=3600",
        )
    }

    fun isUnsafe(rawPath: String): Boolean =
        rawPath.contains('\\') || rawPath.contains('%') || rawPath.split('/').any { it == ".." || it == "." }

    private fun safeRelativePath(rawPath: String): String? {
        if (rawPath.isBlank() || rawPath.contains('\\') || rawPath.contains('%')) return null
        val path = if (rawPath == "/") "index.html" else rawPath.removePrefix("/")
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        val exact = root.resolve(path).normalize()
        if (exact.startsWith(root) && Files.isRegularFile(exact, LinkOption.NOFOLLOW_LINKS)) return path
        return if (!path.substringAfterLast('/').contains('.')) "index.html" else path
    }

    private fun contentType(path: Path): String =
        when (
            path.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        ) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> Files.probeContentType(path) ?: "application/octet-stream"
        }
}
