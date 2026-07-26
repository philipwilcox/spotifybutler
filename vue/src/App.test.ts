import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

const response = (body: unknown, status = 200) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const recipe = { schemaVersion: 1, source: { type: 'saved_tracks' }, predicate: { type: 'all' }, distinctness: { type: 'by', identity: 'SpotifyUri' }, selection: { target: null, quotas: [], rankBy: { type: 'seeded_random' } }, ordering: { type: 'seeded_random' } }
const definition = { definitionId: 'RECENT_LIKED_100', name: 'Recent liked', description: 'Saved tracks', kind: 'built_in', editable: false, enabled: true, recipe, sourceDependencies: [], destination: null }
const playlist = { spotifyPlaylistId: 'library-playlist', name: 'Library playlist', description: null, href: 'https://spotify.test/playlist', uri: 'spotify:playlist:library-playlist', displayUrl: null, declaredItemCount: 2, cachedPlayableTrackCount: 2, contentSourceKey: 'playlist:library-playlist', contentStatus: 'ready', sourceRevision: null, lastSyncedAt: null }
const library = {
  ownerSpotifyUserId: 'operator', status: 'ready',
  sources: [{ sourceKey: 'saved_tracks', resourceKind: 'track_list', status: 'ready', sourceRevision: null, lastSyncedAt: null, itemCount: 2, canRefresh: true, lastErrorCode: null, lastErrorAt: null }],
  definitions: [definition], playlists: [playlist],
}
const preview = { definitionId: definition.definitionId, status: 'ready', generatedTrackIds: ['known-track', 'missing-track'], generatedTrackCount: 2, seed: 'seed', recipeRevision: 'recipe', algorithmVersion: 'algorithm', sourceDependencies: [], generatedAt: '2026-01-01T00:00:00Z', unavailableReason: null }
const knownSong = { id: 'known-track', name: 'Known song', href: 'https://spotify.test/known-track', uri: 'spotify:track:known-track', album: { id: null, name: 'Known album', href: null, uri: null, releaseDate: null }, artists: [{ id: null, name: 'Known artist', href: null, uri: null }], durationMs: 1000, explicit: false, available: true }

function frontendFetch(options: { libraryBody?: unknown; sessionStatus?: number; oneTime?: boolean } = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path === '/api/v1/session') return response(options.sessionStatus === undefined ? { userId: 'operator', csrfToken: 'csrf', expiresAt: '2026-01-01T00:00:00Z' } : { code: 'unauthorized', message: 'Please connect Spotify', requestId: 'req-1', details: {} }, options.sessionStatus ?? 200)
    if (path === '/api/v1/library') return response(options.libraryBody ?? library, options.libraryBody instanceof Error ? 500 : 200)
    if (path.includes('/preview')) return response(preview)
    if (path.includes('/current')) return response({ current: null })
    if (path.includes('/songs')) return response({ items: [knownSong], missingIds: ['missing-track'] })
    if (path.includes('/one-time-updates')) return response(options.oneTime ? { spotifyPlaylistId: 'other', trackIds: ['known-track'], lastSeenSnapshotId: null, appliedAt: '2026-01-01T00:00:00Z', tracked: false } : {})
    return response({})
  })
}

afterEach(() => { vi.unstubAllGlobals() })

describe('App', () => {
  it('renders sanitized source snapshots, library counts, and missing-song fallback text', async () => {
    const fetcher = frontendFetch()
    vi.stubGlobal('fetch', fetcher)
    Object.defineProperty(window, 'fetch', { configurable: true, value: fetcher })
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.text()).toContain('saved_tracks')
    expect(wrapper.text()).toContain('DEFINED PLAYLISTS')
    expect(wrapper.text()).toContain('LIBRARY PLAYLISTS')
    expect(wrapper.text()).toContain('Known song')
    expect(wrapper.text()).toContain('Enrichment pending')
    expect(wrapper.find('[aria-label="Refresh saved_tracks"]').exists()).toBe(true)
  })

  it('shows the authentication-required state and visible library errors', async () => {
    const unauthenticated = frontendFetch({ sessionStatus: 401 })
    vi.stubGlobal('fetch', unauthenticated)
    Object.defineProperty(window, 'fetch', { configurable: true, value: unauthenticated })
    const locked = mount(App)
    await flushPromises()
    expect(locked.text()).toContain('AUTHENTICATION REQUIRED')
    expect(locked.text()).toContain('Connect Spotify to open the studio.')
    locked.unmount()

    const libraryFailure = frontendFetch({ libraryBody: new Error('offline') })
    vi.stubGlobal('fetch', libraryFailure)
    Object.defineProperty(window, 'fetch', { configurable: true, value: libraryFailure })
    const failed = mount(App)
    await flushPromises()
    expect(failed.text()).toContain('LIBRARY LOAD FAILED')
    expect(failed.text()).toContain('Request failed (500)')
  })

  it('opens the one-time dialog, submits a trimmed ID, and announces success', async () => {
    const fetcher = frontendFetch({ oneTime: true })
    vi.stubGlobal('fetch', fetcher)
    Object.defineProperty(window, 'fetch', { configurable: true, value: fetcher })
    const wrapper = mount(App)
    await flushPromises()
    const updateButton = wrapper.findAll('button').find(button => button.text() === 'ONE-TIME UPDATE')
    await updateButton?.trigger('click')
    const input = wrapper.find('#one-time-playlist-id')
    await input.setValue(' other ')
    await wrapper.find('form[aria-labelledby="one-time-title"]').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Updated other')
    expect(fetcher.mock.calls.some(([path, requestInit]) => String(path).includes('/one-time-updates') && JSON.parse(requestInit?.body as string).spotifyPlaylistId === 'other')).toBe(true)
  })
})
