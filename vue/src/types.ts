export type SourceStatus = 'empty' | 'ready' | 'refreshing' | 'stale' | 'error'
export type PreviewStatus = 'ready' | 'empty' | 'partial' | 'unavailable'

export interface Session {
  readonly userId: string
  readonly csrfToken: string
  readonly expiresAt: string
}

export interface SourceDependency {
  readonly sourceKey: string
  readonly resourceKind: string
  readonly sourceRevision: string | null
  readonly lastSyncedAt: string | null
  readonly itemCount: number | null
  readonly usable: boolean
}

export interface SourceSnapshot {
  readonly sourceKey: string
  readonly resourceKind: string
  readonly sourceRevision: string | null
  readonly lastSyncedAt: string | null
  readonly itemCount: number | null
  readonly status: SourceStatus
  readonly canRefresh: boolean
  readonly lastErrorCode: string | null
  readonly lastErrorAt: string | null
}

export interface PlaylistRecipe {
  readonly schemaVersion: number
  readonly shuffleAfterGeneration: boolean
  readonly source: Record<string, unknown>
  readonly predicate: Record<string, unknown>
  readonly distinctness: Record<string, unknown>
  readonly selection: Record<string, unknown>
  readonly ordering: Record<string, unknown>
}

export interface Destination {
  readonly definitionId: string
  readonly spotifyPlaylistId: string
  readonly createdAt: string
  readonly lastSyncedAt: string | null
  readonly lastSeenSnapshotId: string | null
  readonly canSync: boolean
  readonly managementStatus: 'butler_created'
}

export interface Definition {
  readonly definitionId: string
  readonly name: string
  readonly description: string
  readonly kind: 'built_in' | 'owner'
  readonly editable: boolean
  readonly enabled: boolean
  readonly recipe: PlaylistRecipe
  readonly sourceDependencies: readonly SourceDependency[]
  readonly destination: Destination | null
}

export interface Library {
  readonly ownerSpotifyUserId: string
  readonly status: 'empty' | 'ready' | 'partial' | 'refreshing' | 'stale'
  readonly sources: readonly SourceSnapshot[]
  readonly definitions: readonly Definition[]
  readonly playlists: readonly LibraryPlaylist[]
}

export interface LibraryPlaylist {
  readonly spotifyPlaylistId: string
  readonly name: string
  readonly description: string | null
  readonly href: string
  readonly uri: string
  readonly displayUrl: string | null
  readonly declaredItemCount: number | null
  readonly cachedPlayableTrackCount: number
  readonly contentSourceKey: string
  readonly contentStatus: SourceStatus
  readonly sourceRevision: string | null
  readonly lastSyncedAt: string | null
}

export interface LibraryPlaylistDetail {
  readonly summary: LibraryPlaylist
  readonly trackIds: readonly string[]
}

export interface Preview {
  readonly definitionId: string
  readonly status: PreviewStatus
  readonly generatedTrackIds: readonly string[]
  readonly generatedTrackCount: number
  readonly seed: string
  readonly recipeRevision: string
  readonly algorithmVersion: string
  readonly sourceDependencies: readonly SourceDependency[]
  readonly generatedAt: string
  readonly unavailableReason: string | null
}

export interface CurrentDestination {
  readonly spotifyPlaylistId: string
  readonly trackIds: readonly string[]
  readonly lastSyncedAt: string | null
  readonly lastSeenSnapshotId: string | null
}

export interface Song {
  readonly id: string
  readonly name: string
  readonly href: string
  readonly uri: string
  readonly album: { readonly id: string | null; readonly name: string | null; readonly href: string | null; readonly uri: string | null; readonly releaseDate: string | null }
  readonly artists: readonly { readonly id: string | null; readonly name: string | null; readonly href: string | null; readonly uri: string | null }[]
  readonly durationMs: number | null
  readonly explicit: boolean | null
  readonly available: boolean
}

export type PublishPlanAction = 'create' | 'adopt' | 'choose' | 'blocked'

export interface PublishCandidate {
  readonly spotifyPlaylistId: string
  readonly name: string
  readonly description: string | null
  readonly itemCount: number | null
  readonly displayUrl: string | null
}

export interface PublishPlan {
  readonly definitionId: string
  readonly playlistName: string
  readonly action: PublishPlanAction
  readonly candidates: readonly PublishCandidate[]
  readonly message: string | null
  readonly publishFlowId: string
}

export interface ApiErrorDto {
  readonly code: string
  readonly message: string
  readonly requestId: string
  readonly details: Readonly<Record<string, string>>
}

export interface SelectionState {
  readonly source: 'preview' | 'current-fallback'
  readonly dirty: boolean
  readonly orderedIds: readonly string[]
  readonly enrichment: Readonly<Record<string, Song | undefined>>
}
