package com.philipwilcox.spotifybutler.spotify

data class SpotifyCacheSnapshot(
    val savedTracks: List<SavedTrack>,
    val topTracks: List<SpotifyTrack>,
    val topArtists: List<SpotifyArtist>,
    val playlists: List<SpotifyPlaylist>,
    val playlistTracks: List<PlaylistTrack>,
)

data class SpotifyCurrentUser(
    val displayName: String?,
    val id: String,
)

data class SavedTrack(
    val addedAt: String?,
    val track: SpotifyTrack,
)

data class PlaylistTrack(
    val playlistName: String,
    val addedAt: String?,
    val track: SpotifyTrack,
)

data class SpotifyTrack(
    val name: String,
    val id: String,
    val href: String,
    val uri: String,
    val releaseDate: String?,
    val primaryArtistId: String?,
    val rawJson: String,
)

data class SpotifyArtist(
    val name: String,
    val id: String,
    val href: String,
    val uri: String,
)

data class SpotifyPlaylist(
    val name: String,
    val id: String,
    val href: String,
    val uri: String,
    val tracksHref: String,
    val snapshotId: String?,
)
