package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

@Serializable
data class PlaylistRecipe(
    val schemaVersion: Int = 1,
    val source: CandidateSource,
    val predicate: TrackPredicate = TrackPredicate.All,
    val distinctness: DistinctnessPolicy = DistinctnessPolicy.By(CandidateIdentity.SpotifyUri),
    val selection: SelectionPolicy,
    val ordering: OrderingPolicy,
)

@Serializable
sealed interface CandidateSource {
    @Serializable
    @SerialName("saved_tracks")
    data object SavedTracks : CandidateSource

    @Serializable
    @SerialName("top_tracks")
    data object TopTracks : CandidateSource

    @Serializable
    @SerialName("playlist_items")
    data class PlaylistItems(
        val playlistName: String,
    ) : CandidateSource

    @Serializable
    @SerialName("union")
    data class Union(
        val sources: List<CandidateSource>,
    ) : CandidateSource

    @Serializable
    @SerialName("difference")
    data class Difference(
        val left: CandidateSource,
        val right: CandidateSource,
    ) : CandidateSource

    @Serializable
    @SerialName("filtered")
    data class Filtered(
        val source: CandidateSource,
        val predicate: TrackPredicate,
    ) : CandidateSource
}

@Serializable
sealed interface TrackPredicate {
    @Serializable
    @SerialName("all")
    data object All : TrackPredicate

    @Serializable
    @SerialName("and")
    data class And(
        val predicates: List<TrackPredicate>,
    ) : TrackPredicate

    @Serializable
    @SerialName("or")
    data class Or(
        val predicates: List<TrackPredicate>,
    ) : TrackPredicate

    @Serializable
    @SerialName("not")
    data class Not(
        val predicate: TrackPredicate,
    ) : TrackPredicate

    @Serializable
    @SerialName("release_year_range")
    data class ReleaseYearRange(
        val minInclusive: Int? = null,
        val maxExclusive: Int? = null,
    ) : TrackPredicate

    @Serializable
    @SerialName("duration_range")
    data class DurationRange(
        val minMsInclusive: Long? = null,
        val maxMsExclusive: Long? = null,
    ) : TrackPredicate

    @Serializable
    @SerialName("album_id_in")
    data class AlbumIdIn(
        val albumIds: List<String>,
    ) : TrackPredicate

    @Serializable
    @SerialName("artist_id_in")
    data class ArtistIdIn(
        val artistIds: List<String>,
    ) : TrackPredicate

    @Serializable
    @SerialName("added_at_range")
    data class AddedAtRange(
        val minInclusive: String? = null,
        val maxExclusive: String? = null,
    ) : TrackPredicate

    @Serializable
    @SerialName("explicitness")
    data class Explicitness(
        val value: Boolean,
    ) : TrackPredicate

    @Serializable
    @SerialName("not_in_top_artists")
    data object NotInTopArtists : TrackPredicate

    @Serializable
    @SerialName("not_in_top_tracks")
    data object NotInTopTracks : TrackPredicate
}

@Serializable
sealed interface DistinctnessPolicy {
    @Serializable
    @SerialName("by")
    data class By(
        val identity: CandidateIdentity,
    ) : DistinctnessPolicy

    @Serializable
    @SerialName("keep_all")
    data object KeepAll : DistinctnessPolicy
}

@Serializable
enum class CandidateIdentity {
    SpotifyUri,
}

@Serializable
data class SelectionPolicy(
    val target: Int? = null,
    val quotas: List<Quota> = emptyList(),
    val rankBy: RankingStrategy,
)

@Serializable
data class Quota(
    val dimension: CandidateDimension,
    val maximum: Int,
)

@Serializable
enum class CandidateDimension {
    PrimaryArtistId,
    AlbumId,
}

@Serializable
sealed interface RankingStrategy {
    @Serializable
    @SerialName("seeded_random")
    data object SeededRandom : RankingStrategy

    @Serializable
    @SerialName("added_at_ascending")
    data object AddedAtAscending : RankingStrategy

    @Serializable
    @SerialName("added_at_descending")
    data object AddedAtDescending : RankingStrategy

    @Serializable
    @SerialName("release_date_ascending")
    data object ReleaseDateAscending : RankingStrategy

    @Serializable
    @SerialName("release_date_descending")
    data object ReleaseDateDescending : RankingStrategy
}

@Serializable
sealed interface OrderingPolicy {
    @Serializable
    @SerialName("seeded_random")
    data object SeededRandom : OrderingPolicy

    @Serializable
    @SerialName("added_at_ascending")
    data object AddedAtAscending : OrderingPolicy

    @Serializable
    @SerialName("added_at_descending")
    data object AddedAtDescending : OrderingPolicy

    @Serializable
    @SerialName("release_date_ascending")
    data object ReleaseDateAscending : OrderingPolicy

    @Serializable
    @SerialName("release_date_descending")
    data object ReleaseDateDescending : OrderingPolicy
}

data class CandidateTrack(
    val track: SpotifyTrack,
    val addedAt: String?,
    val sourceOrdinal: Int,
) {
    val identity: String get() = track.uri

    fun dimensionValues(dimension: CandidateDimension): List<String> =
        when (dimension) {
            CandidateDimension.PrimaryArtistId -> listOf(track.primaryArtistId ?: UNKNOWN_DIMENSION)
            CandidateDimension.AlbumId -> listOf(track.albumId ?: UNKNOWN_DIMENSION)
        }

    companion object {
        const val UNKNOWN_DIMENSION = "<unknown>"
    }
}

data class RecipeExecutionContext(
    val topArtistIds: Set<String> = emptySet(),
    val topTrackIds: Set<String> = emptySet(),
)

data class PlaylistGenerationResult(
    val candidates: List<CandidateTrack>,
    val distinctCandidates: List<CandidateTrack>,
    val selected: List<CandidateTrack>,
    val rejectedByQuota: List<CandidateTrack>,
)

object PlaylistRecipeCodec {
    val json =
        Json {
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    fun encode(recipe: PlaylistRecipe): String = json.encodeToString(canonicalize(recipe))

    fun decode(value: String): PlaylistRecipe = json.decodeFromString(value)

    fun canonicalize(recipe: PlaylistRecipe): PlaylistRecipe {
        PlaylistRecipeValidator.validate(recipe)
        return recipe
    }

    fun revision(recipe: PlaylistRecipe): String = sha256Hex(encode(recipe).encodeToByteArray())
}

internal fun selectionRank(
    seed: ByteArray,
    recipeRevision: String,
    candidate: CandidateTrack,
): ByteArray = digest("select-v1", seed, recipeRevision, candidate.identity)

internal fun orderingRank(
    seed: ByteArray,
    recipeRevision: String,
    candidate: CandidateTrack,
): ByteArray = digest("order-v1", seed, recipeRevision, candidate.identity)

private fun digest(
    domain: String,
    seed: ByteArray,
    recipeRevision: String,
    candidateIdentity: String,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(domain.encodeToByteArray(), seed, recipeRevision.encodeToByteArray(), candidateIdentity.encodeToByteArray())
        .forEach { part ->
            digest.update((part.size.toLong()).toString().encodeToByteArray())
            digest.update(0)
            digest.update(part)
        }
    return digest.digest()
}

private fun sha256Hex(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { byte -> "%02x".format(byte) }

internal fun String?.parseInstantOrNull(): Instant? = this?.let(Instant::parse)
