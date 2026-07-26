import { describe, expect, it, vi } from 'vitest'
import { ButlerApiClient, ButlerApiError } from './api'
import { parseDestination, parseLibrary, parsePreview, parseSongs, parseSourceSnapshot, ContractValidationError } from './validation'

const response = (body: unknown, status = 200) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const session = { userId: 'operator', csrfToken: 'csrf-a', expiresAt: '2026-01-01T00:00:00Z' }
const song = (id: string) => ({ id, name: id, href: `https://spotify.test/${id}`, uri: `spotify:track:${id}`, album: { id: null, name: null, href: null, uri: null, releaseDate: null }, artists: [], durationMs: null, explicit: null, available: true })
const recipe = { schemaVersion: 1, source: { type: 'saved_tracks' }, predicate: { type: 'all' }, distinctness: { type: 'by', identity: 'SpotifyUri' }, selection: { target: null, quotas: [], rankBy: { type: 'seeded_random' } }, ordering: { type: 'seeded_random' } }
const definition = { definitionId: 'RECENT_LIKED_100', name: 'Recent liked', description: 'test', kind: 'built_in', editable: false, enabled: true, recipe, sourceDependencies: [], destination: null }
const destination = { definitionId: 'RECENT_LIKED_100', spotifyPlaylistId: 'managed', createdAt: '2026-01-01T00:00:00Z', lastSyncedAt: null, lastSeenSnapshotId: null, canSync: true, managementStatus: 'butler_created' }
const current = { current: { spotifyPlaylistId: 'managed', trackIds: ['a', 'b'], lastSyncedAt: null, lastSeenSnapshotId: 'snap-2' } }

describe('ButlerApiClient', () => {
  it('uses the documented paths, envelopes, bodies, and 204 handling for every client endpoint', async () => {
    const library = { ownerSpotifyUserId: 'operator', status: 'ready', sources: [], definitions: [definition], playlists: [] }
    const playlist = { summary: { spotifyPlaylistId: 'playlist/1', name: 'Library', description: null, href: 'https://spotify.test/p', uri: 'spotify:playlist:p', displayUrl: null, declaredItemCount: 2, cachedPlayableTrackCount: 2, contentSourceKey: 'playlist:p', contentStatus: 'ready', sourceRevision: null, lastSyncedAt: null }, trackIds: ['a', 'b'] }
    const preview = { definitionId: definition.definitionId, status: 'ready', generatedTrackIds: ['a'], generatedTrackCount: 1, seed: 'seed', recipeRevision: 'recipe', algorithmVersion: 'algorithm', sourceDependencies: [], generatedAt: '2026-01-01T00:00:00Z', unavailableReason: null }
    const oneTime = { spotifyPlaylistId: 'other', trackIds: ['a'], lastSeenSnapshotId: null, appliedAt: '2026-01-01T00:00:00Z', tracked: false }
    const bodies = [session, session, library, library, playlist, { items: [definition] }, definition, preview, destination, current, current, oneTime, { items: [song('a')], missingIds: [] }, undefined]
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => { const body = bodies.shift(); return body === undefined ? response(undefined, 204) : response(body) })
    const client = new ButlerApiClient(fetcher)
    await client.getSession()
    await client.refreshSession()
    await client.getLibrary()
    await client.refreshLibrary(['saved_tracks'])
    await client.getLibraryPlaylist('playlist/1')
    await client.listDefinitions()
    await client.getDefinition(definition.definitionId)
    await client.previewDefinition(definition.definitionId, 'seed value')
    await client.createDestination(definition.definitionId, { name: 'Managed' })
    await client.getCurrentDestination(definition.definitionId)
    await client.syncDestination(definition.definitionId, ['a', 'b'], 'snap-1')
    await client.oneTimeUpdate(definition.definitionId, 'other', ['a'])
    await client.getSongs(['a'])
    await client.deleteSession()

    expect(fetcher.mock.calls.map(([path]) => String(path))).toEqual([
      '/api/v1/session', '/api/v1/session/refresh', '/api/v1/library', '/api/v1/library/refresh',
      '/api/v1/library/playlists/playlist%2F1', '/api/v1/playlists', '/api/v1/playlists/RECENT_LIKED_100',
      '/api/v1/playlists/RECENT_LIKED_100/preview?seed=seed%20value', '/api/v1/playlists/RECENT_LIKED_100/destinations',
      '/api/v1/playlists/RECENT_LIKED_100/current', '/api/v1/playlists/RECENT_LIKED_100/syncs',
      '/api/v1/playlists/RECENT_LIKED_100/one-time-updates', '/api/v1/songs?ids=a', '/api/v1/session',
    ])
    expect(JSON.parse((fetcher.mock.calls[3][1] as RequestInit).body as string)).toEqual({ sourceKeys: ['saved_tracks'] })
    expect(JSON.parse((fetcher.mock.calls[10][1] as RequestInit).body as string)).toEqual({ trackIds: ['a', 'b'], expectedDestinationSnapshotId: 'snap-1' })
    expect(fetcher.mock.calls[13][1]).toMatchObject({ body: undefined })
    expect(client.csrf).toBeNull()
  })

  it('rotates CSRF tokens and sends credentials on mutations', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(response(session)).mockResolvedValueOnce(response({ ownerSpotifyUserId: 'operator', status: 'ready', sources: [], definitions: [], playlists: [] }))
    const client = new ButlerApiClient(fetcher)
    await client.getSession()
    await client.refreshLibrary()
    expect(fetcher.mock.calls[1][1]).toMatchObject({ credentials: 'include' })
    expect((fetcher.mock.calls[1][1] as RequestInit).headers).toMatchObject({ 'X-CSRF-Token': 'csrf-a', Origin: window.location.origin })
  })

  it('batches enrichment into ordered requests of at most 50 IDs', async () => {
    const ids = Array.from({ length: 101 }, (_, index) => `track-${index}`)
    const fetcher = vi.fn(async (path: RequestInfo | URL) => response({ items: [song(String(path).includes('track-0') ? 'track-0' : 'track-50')], missingIds: [] }))
    const client = new ButlerApiClient(fetcher)
    await client.getSongs(ids)
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(fetcher.mock.calls.every(([path]) => String(path).split('ids=')[1].split(',').length <= 50)).toBe(true)
  })

  it('omits blank song IDs and preserves duplicate requests without making an empty-ID call', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ items: [song('a'), song('a')], missingIds: ['missing'] }))
    const client = new ButlerApiClient(fetcher)
    expect(await client.getSongs(['a', '', '  ', 'a'])).toHaveLength(2)
    expect(fetcher).toHaveBeenCalledOnce()
    expect(String(fetcher.mock.calls[0][0])).toBe('/api/v1/songs?ids=a,a')
    await expect(client.getSongs(['', '  '])).resolves.toEqual([])
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('normalizes malformed non-JSON errors into a sanitized API error', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('<html>failure</html>', { status: 502, headers: { 'X-Request-Id': 'req-7' } }))
    const client = new ButlerApiClient(fetcher)
    await expect(client.getLibrary()).rejects.toMatchObject({ status: 502, error: { code: 'http_error', requestId: 'req-7' } })
  })

  it('clears session state on 401 and never retries a mutation', async () => {
    const unauthorized = vi.fn()
    const fetcher = vi.fn().mockResolvedValue(response({ code: 'unauthorized', message: 'no', requestId: 'r1', details: {} }, 401))
    const client = new ButlerApiClient(fetcher, unauthorized)
    await expect(client.refreshLibrary()).rejects.toBeInstanceOf(ButlerApiError)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(unauthorized).toHaveBeenCalledOnce()
    expect(client.csrf).toBeNull()
  })
})

describe('runtime contracts', () => {
  it('accepts library source snapshots without dependency-only usability fields', () => {
    const library = parseLibrary({
      ownerSpotifyUserId: 'operator', status: 'ready',
      sources: [{ sourceKey: 'saved_tracks', resourceKind: 'track_list', status: 'ready', sourceRevision: null, lastSyncedAt: null, itemCount: 1, canRefresh: true, lastErrorCode: null, lastErrorAt: null }],
      definitions: [], playlists: [],
    })
    expect(library.sources[0]).not.toHaveProperty('usable')
    expect(library.sources[0].itemCount).toBe(1)
  })

  it('rejects malformed preview discriminators and missing fields', () => {
    expect(() => parsePreview({ definitionId: 'x', status: 'ready', generatedTrackIds: [], generatedTrackCount: 0, seed: 's', recipeRevision: 'r', algorithmVersion: 'a', sourceDependencies: [], generatedAt: 'now', unavailableReason: null })).not.toThrow()
    expect(() => parsePreview({ definitionId: 'x', status: 'ready', generatedTrackIds: [], generatedTrackCount: 0, seed: 's', recipeRevision: 'r', algorithmVersion: 'a', sourceDependencies: [{ sourceKey: 'saved_tracks', resourceKind: 'track_list', sourceRevision: null, lastSyncedAt: null, itemCount: null, usable: true }], generatedAt: 'now' })).toThrow(ContractValidationError)
  })

  it('enforces enum and non-negative integer contract boundaries', () => {
    expect(() => parseSourceSnapshot({ sourceKey: 'x', resourceKind: 'track_list', status: 'ready', sourceRevision: null, lastSyncedAt: null, itemCount: -1, canRefresh: true, lastErrorCode: null, lastErrorAt: null })).toThrow(ContractValidationError)
    expect(() => parseDestination({ ...destination, managementStatus: 'owner_managed' })).toThrow(ContractValidationError)
    expect(() => parseSongs({ items: [song('a',)], missingIds: [] })).not.toThrow()
    expect(() => parsePreview({ definitionId: 'x', status: 'ready', generatedTrackIds: [], generatedTrackCount: -1, seed: 's', recipeRevision: 'r', algorithmVersion: 'a', sourceDependencies: [], generatedAt: 'now', unavailableReason: null })).toThrow(ContractValidationError)
  })
})
