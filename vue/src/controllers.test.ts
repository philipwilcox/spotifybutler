import { describe, expect, it, vi } from 'vitest'
import type { ButlerApi } from './api'
import { LibraryController, SessionController, StudioController } from './controllers'
import type { Definition } from './types'

const definition: Definition = {
  definitionId: 'RECENT_LIKED_100', name: 'Recent liked', description: 'test', kind: 'built_in', editable: false, enabled: true,
  recipe: { schemaVersion: 1, source: {}, predicate: {}, distinctness: {}, selection: {}, ordering: {} }, sourceDependencies: [], destination: null,
}
const preview = (ids: string[], seed = 'seed-a') => ({ definitionId: definition.definitionId, status: 'ready' as const, generatedTrackIds: ids, generatedTrackCount: ids.length, seed, recipeRevision: 'recipe', algorithmVersion: 'algorithm', sourceDependencies: [], generatedAt: '2026-01-01T00:00:00Z', unavailableReason: null })
const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => { resolve = resolvePromise; reject = rejectPromise })
  return { promise, resolve, reject }
}

function fakeApi(overrides: Partial<ButlerApi> = {}): ButlerApi {
  return {
    getSession: vi.fn(), refreshSession: vi.fn(), deleteSession: vi.fn(), getLibrary: vi.fn(), refreshLibrary: vi.fn(), listDefinitions: vi.fn(),
    getDefinition: vi.fn().mockResolvedValue(definition), previewDefinition: vi.fn().mockResolvedValue(preview(['one', 'two'])), getCurrentDestination: vi.fn().mockResolvedValue(null),
    getSongs: vi.fn().mockResolvedValue([]), getLibraryPlaylist: vi.fn(), createDestination: vi.fn(), syncDestination: vi.fn(), oneTimeUpdate: vi.fn(), ...overrides,
  }
}

describe('StudioController', () => {
  it('falls back to the current destination for unavailable previews and tolerates missing enrichment', async () => {
    const current = { spotifyPlaylistId: 'managed', trackIds: ['missing', 'known'], lastSyncedAt: null, lastSeenSnapshotId: 'snap-1' }
    const api = fakeApi({
      previewDefinition: vi.fn().mockResolvedValue({ ...preview([], 'seed-a'), status: 'unavailable', unavailableReason: 'source unavailable' }),
      getCurrentDestination: vi.fn().mockResolvedValue(current),
      getSongs: vi.fn().mockResolvedValue([{ id: 'known', name: 'Known', href: 'href', uri: 'uri', album: { id: null, name: 'Album', href: null, uri: null, releaseDate: null }, artists: [], durationMs: null, explicit: null, available: true }]),
    })
    const studio = new StudioController(api)
    await studio.load(definition)
    expect(studio.state.selection).toMatchObject({ source: 'current-fallback', orderedIds: ['missing', 'known'] })
    expect(studio.state.selection.enrichment.missing).toBeUndefined()
    expect(studio.state.selection.enrichment.known?.name).toBe('Known')
  })

  it('does not let a late selection overwrite the latest selection', async () => {
    const first = { ...definition, definitionId: 'first', name: 'First' }
    const second = { ...definition, definitionId: 'second', name: 'Second' }
    const firstPreview = deferred<ReturnType<typeof preview>>()
    const secondPreview = deferred<ReturnType<typeof preview>>()
    const api = fakeApi({
      previewDefinition: vi.fn((id: string) => id === 'first' ? firstPreview.promise : secondPreview.promise),
      getCurrentDestination: vi.fn().mockResolvedValue(null),
    })
    const studio = new StudioController(api)
    const firstSelection = studio.selectDefinition(first)
    const secondSelection = studio.selectDefinition(second)
    secondPreview.resolve({ ...preview(['second']), definitionId: 'second' })
    await secondSelection
    firstPreview.resolve({ ...preview(['first']), definitionId: 'first' })
    await firstSelection
    expect(studio.state.definition?.definitionId).toBe('second')
    expect(studio.state.selection.orderedIds).toEqual(['second'])
  })

  it('uses exact preview IDs and injected seeds for rerolls', async () => {
    const api = fakeApi({ previewDefinition: vi.fn().mockResolvedValueOnce(preview(['one', 'two'])).mockResolvedValueOnce(preview(['three'], 'seed-b')) })
    const studio = new StudioController(api, () => 'seed-b')
    await studio.load(definition)
    expect(studio.state.selection.orderedIds).toEqual(['one', 'two'])
    await studio.reroll()
    expect(studio.state.selection.orderedIds).toEqual(['three'])
    expect(api.previewDefinition).toHaveBeenLastCalledWith(definition.definitionId, 'seed-b')
  })

  it('rejects recurring sync without a destination and preserves staged data on conflict', async () => {
    const api = fakeApi({ syncDestination: vi.fn().mockRejectedValue(Object.assign(new Error('changed'), { status: 409 })) })
    const studio = new StudioController(api)
    await studio.load(definition)
    studio.moveTrack(1, -1)
    expect(await studio.sync()).toBe(false)
    expect(studio.state.error).toContain('Create a Butler destination')
    studio.state.definition = { ...definition, destination: { definitionId: definition.definitionId, spotifyPlaylistId: 'managed', createdAt: 'now', lastSyncedAt: null, lastSeenSnapshotId: 'snap-a', canSync: true, managementStatus: 'butler_created' } }
    expect(await studio.sync()).toBe(false)
    expect(studio.state.selection.orderedIds).toEqual(['two', 'one'])
    expect(studio.state.conflict).toBe(true)
    expect(api.syncDestination).toHaveBeenCalledOnce()
  })

  it('updates the displayed destination snapshot after sync and reports unmanaged one-time updates', async () => {
    const managed = { definitionId: definition.definitionId, spotifyPlaylistId: 'managed', createdAt: 'now', lastSyncedAt: null, lastSeenSnapshotId: 'snap-a', canSync: true, managementStatus: 'butler_created' as const }
    const api = fakeApi({
      getCurrentDestination: vi.fn().mockResolvedValue({ spotifyPlaylistId: 'managed', trackIds: ['one', 'two'], lastSyncedAt: null, lastSeenSnapshotId: 'snap-a' }),
      syncDestination: vi.fn().mockResolvedValue({ spotifyPlaylistId: 'managed', trackIds: ['two', 'one'], lastSyncedAt: '2026-02-01T00:00:00Z', lastSeenSnapshotId: 'snap-b' }),
      oneTimeUpdate: vi.fn().mockResolvedValue({ spotifyPlaylistId: 'other', trackIds: ['two'], lastSeenSnapshotId: null, appliedAt: '2026-02-01T00:00:00Z', tracked: false }),
    })
    const studio = new StudioController(api)
    await studio.load({ ...definition, destination: managed })
    studio.moveTrack(1, -1)
    expect(await studio.sync()).toBe(true)
    expect(studio.state.definition?.destination?.lastSeenSnapshotId).toBe('snap-b')
    expect(studio.state.selection.dirty).toBe(false)
    expect(await studio.oneTimeUpdate(' other ')).toBe(true)
    expect(studio.state.oneTimeResult).toContain('tracked=false')
    expect(api.oneTimeUpdate).toHaveBeenCalledWith(definition.definitionId, 'other', ['two', 'one'])
  })
})

describe('SessionController and LibraryController', () => {
  it('keeps the newest session response and clears local state when sign-out is unauthorized', async () => {
    const oldSession = deferred<typeof session>()
    const newSession = deferred<typeof session>()
    const api = fakeApi({ getSession: vi.fn().mockReturnValueOnce(oldSession.promise), refreshSession: vi.fn().mockReturnValueOnce(newSession.promise), deleteSession: vi.fn().mockRejectedValue({ status: 401 }) })
    const sessions = new SessionController(api)
    const loading = sessions.load()
    const refreshing = sessions.refresh()
    newSession.resolve({ userId: 'new', csrfToken: 'new-csrf', expiresAt: 'later' })
    await refreshing
    oldSession.resolve({ userId: 'old', csrfToken: 'old-csrf', expiresAt: 'old' })
    await loading
    expect(sessions.state.session?.userId).toBe('new')
    await sessions.signOut()
    expect(sessions.state.session).toBeNull()
  })

  it('preserves the last library and ignores stale refresh responses', async () => {
    const oldLibrary = deferred<ReturnType<typeof libraryFixture>>()
    const newLibrary = deferred<ReturnType<typeof libraryFixture>>()
    const api = fakeApi({ getLibrary: vi.fn().mockReturnValueOnce(oldLibrary.promise), refreshLibrary: vi.fn().mockReturnValueOnce(newLibrary.promise) })
    const libraries = new LibraryController(api)
    const loading = libraries.load()
    const refreshing = libraries.refreshAll()
    newLibrary.resolve(libraryFixture('new'))
    await refreshing
    oldLibrary.resolve(libraryFixture('old'))
    await loading
    expect(libraries.state.library?.ownerSpotifyUserId).toBe('new')
    api.getLibrary = vi.fn().mockRejectedValue(new Error('offline'))
    expect(await libraries.load()).toBeNull()
    expect(libraries.state.library?.ownerSpotifyUserId).toBe('new')
    expect(libraries.state.error).toBe('offline')
  })
})

function libraryFixture(ownerSpotifyUserId: string) {
  return { ownerSpotifyUserId, status: 'ready' as const, sources: [], definitions: [], playlists: [] }
}

const session = { userId: 'operator', csrfToken: 'csrf', expiresAt: '2026-01-01T00:00:00Z' }
