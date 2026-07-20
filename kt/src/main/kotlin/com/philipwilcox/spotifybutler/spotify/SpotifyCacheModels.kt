package com.philipwilcox.spotifybutler.spotify

data class SpotifyCacheSnapshot(
    val savedTracks: List<SavedTrack>,
    val topTracks: List<SpotifyTrack>,
    val topArtists: List<SpotifyArtist>,
    val playlists: List<SpotifyPlaylist>,
    val playlistTracks: List<PlaylistTrack>,
    val playlistItems: List<SpotifyPlaylistItem> = emptyList(),
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
    val albumId: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean? = null,
    val artistIds: List<String> = emptyList(),
    val albumName: String? = null,
    val albumHref: String? = null,
    val albumUri: String? = null,
    val available: Boolean = true,
    val artists: List<SpotifyArtistReference> = emptyList(),
)

data class SpotifyArtistReference(
    val id: String?,
    val name: String?,
    val href: String?,
    val uri: String?,
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
    val description: String? = null,
    val public: Boolean? = null,
    val collaborative: Boolean? = null,
    val ownerId: String? = null,
    val itemCount: Int? = null,
    val displayUrl: String? = null,
)

data class SpotifyPlaylistItem(
    val playlistId: String,
    val playlistName: String,
    val position: Int,
    val addedAt: String?,
    val addedById: String?,
    val isLocal: Boolean,
    val itemType: String?,
    val isPlayable: Boolean,
    val itemId: String?,
    val itemUri: String?,
    val status: String,
    val rawJson: String,
    val track: SpotifyTrack? = null,
)
