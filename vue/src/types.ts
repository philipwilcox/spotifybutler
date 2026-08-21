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
  readonly editable: boolean
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
  readonly album: { readonly id: string | null; readonly name: string | null; readonly href: string | null; readonly uri: string | null; readonly releaseDate: string | null; readonly imageUrl: string | null }
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

export type BulkRepublishPlanAction = 'sync' | 'create' | 'adopt' | 'choose' | 'skipped'
export type BulkRepublishItemPhase = 'queued' | 'generating' | 'publishing' | 'succeeded' | 'failed' | 'skipped'
export interface BulkRepublishPlanItem { readonly definitionId: string; readonly name: string; readonly action: BulkRepublishPlanAction; readonly candidates: readonly PublishCandidate[]; readonly message: string | null }
export interface BulkRepublishPlan { readonly items: readonly BulkRepublishPlanItem[] }
export interface BulkRepublishChoice { readonly definitionId: string; readonly action: 'sync' | 'create' | 'adopt'; readonly spotifyPlaylistId?: string | null }
export interface BulkRepublishItem { readonly definitionId: string; readonly name: string; readonly phase: BulkRepublishItemPhase; readonly trackCount: number | null; readonly completedSteps: number | null; readonly totalSteps: number | null; readonly message: string | null }
export interface BulkRepublishProgress { readonly completedItems: number; readonly totalItems: number; readonly items: readonly BulkRepublishItem[] }

export interface ApiErrorDto {
  readonly code: string
  readonly message: string
  readonly requestId: string
  readonly details: Readonly<Record<string, string>>
}

export type OperationKind = 'library_refresh' | 'library_playlist_publish' | 'publish_plan' | 'publish_create' | 'publish_adopt' | 'destination_sync' | 'bulk_republish_plan' | 'bulk_republish'
export type OperationPhase = 'queued' | 'running' | 'succeeded' | 'failed'
export interface OperationAccepted { readonly operationId: string; readonly kind: OperationKind }
export interface OperationFailure { readonly code: string; readonly message: string }
export interface LibraryRefreshProgress {
  readonly completedSources: number
  readonly totalSources: number
  readonly activeSourceCompletedPages: number | null
  readonly activeSourceTotalPages: number | null
}
export type OperationResult =
  | { readonly type: 'library_refresh'; readonly library: Library }
  | { readonly type: 'publish_plan'; readonly plan: PublishPlan }
  | { readonly type: 'publish_destination'; readonly destination: Destination }
  | { readonly type: 'destination_sync'; readonly current: CurrentDestination | null }
  | { readonly type: 'library_playlist_publish'; readonly playlist: LibraryPlaylistDetail }
  | { readonly type: 'bulk_republish_plan'; readonly plan: BulkRepublishPlan }
  | { readonly type: 'bulk_republish'; readonly library: Library; readonly items: readonly BulkRepublishItem[] }
export interface OperationStatus {
  readonly operationId: string
  readonly kind: OperationKind
  readonly phase: OperationPhase
  readonly action: string
  readonly completedSteps: number
  readonly totalSteps: number | null
  readonly result: OperationResult | null
  readonly error: OperationFailure | null
  readonly libraryRefreshProgress: LibraryRefreshProgress | null
  readonly bulkRepublishProgress?: BulkRepublishProgress | null
}

export interface SelectionState {
  readonly source: 'preview' | 'current-fallback' | 'library-playlist'
  readonly dirty: boolean
  readonly baselineIds: readonly string[]
  readonly orderedIds: readonly string[]
  readonly enrichment: Readonly<Record<string, Song | undefined>>
}
