package com.philipwilcox.spotifybutler.db

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protects refresh tokens before they cross the SQLite boundary.
 *
 * The deployment supplies the Spotify client secret as the key material. SQLite therefore never contains a raw
 * refresh token; the database file and the secrets file must still be protected by the operating system.
 */
class RefreshTokenProtector(
    keyMaterial: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key =
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray(StandardCharsets.UTF_8)),
            "AES",
        )

    fun protect(refreshToken: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(refreshToken.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            FORMAT,
            Base64.getUrlEncoder().withoutPadding().encodeToString(iv),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
        ).joinToString(":")
    }

    fun reveal(protectedRefreshToken: String): String {
        val parts = protectedRefreshToken.split(':')
        require(parts.size == PROTECTED_TOKEN_PARTS && parts[0] == FORMAT) {
            "Unsupported protected refresh-token format"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, Base64.getUrlDecoder().decode(parts[1])),
        )
        return cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])).toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val FORMAT = "v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val PROTECTED_TOKEN_PARTS = 3
    }
}
