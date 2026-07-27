package com.philipwilcox.spotifybutler.config

data class SpotifyRetryConfig(
    val maxRetries: Int,
    val initialDelaySeconds: Int,
    val backoffMultiplier: Double,
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 4
        const val DEFAULT_INITIAL_DELAY_SECONDS = 1
        const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
    }
}
