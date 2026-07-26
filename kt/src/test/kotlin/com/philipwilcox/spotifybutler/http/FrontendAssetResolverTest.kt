package com.philipwilcox.spotifybutler.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrontendAssetResolverTest {
    @Test
    fun servesIndexAndTypedAssetsWithoutEscapingRoot() {
        val root = Files.createTempDirectory("frontend-assets-")
        Files.writeString(root.resolve("index.html"), "<html />")
        Files.createDirectories(root.resolve("assets"))
        Files.writeString(root.resolve("assets/app.css"), "body {}")
        Files.writeString(root.parent.resolve("outside.txt"), "private")
        val resolver = FrontendAssetResolver(root)

        assertEquals("text/html; charset=utf-8", resolver.resolve("/")?.contentType)
        assertEquals("text/css; charset=utf-8", resolver.resolve("/assets/app.css")?.contentType)
        assertEquals("no-cache", resolver.resolve("/")?.cacheControl)
        assertTrue(resolver.resolve("/studio/mission")?.bytes?.contentEquals("<html />".toByteArray()) == true)
        assertTrue(resolver.isUnsafe("/../outside.txt"))
        assertTrue(resolver.isUnsafe("/assets\\app.css"))
        assertNull(resolver.resolve("/../outside.txt"))
    }
}
