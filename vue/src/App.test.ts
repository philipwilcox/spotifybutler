import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

const response = (body: unknown, status = 200) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const recipe = { schemaVersion: 1, shuffleAfterGeneration: false, source: { type: 'saved_tracks' }, predicate: { type: 'all' }, distinctness: { type: 'by', identity: 'SpotifyUri' }, selection: { target: null, quotas: [], rankBy: { type: 'seeded_random' } }, ordering: { type: 'seeded_random' } }
const definition = { definitionId: 'RECENT_LIKED_100', name: 'Recent liked', description: 'Saved tracks', kind: 'built_in', editable: false, enabled: true, recipe, sourceDependencies: [], destination: null }
const playlist = { spotifyPlaylistId: 'library-playlist', name: 'Library playlist', description: null, href: 'https://spotify.test/playlist', uri: 'spotify:playlist:library-playlist', displayUrl: null, declaredItemCount: 2, cachedPlayableTrackCount: 2, contentSourceKey: 'playlist:library-playlist', contentStatus: 'ready', sourceRevision: null, lastSyncedAt: null }
const library = {
  ownerSpotifyUserId: 'operator', status: 'ready',
  sources: [{ sourceKey: 'saved_tracks', resourceKind: 'track_list', status: 'ready', sourceRevision: null, lastSyncedAt: null, itemCount: 2, canRefresh: true, lastErrorCode: null, lastErrorAt: null }],
  definitions: [definition], playlists: [playlist],
}
const preview = { definitionId: definition.definitionId, status: 'ready', generatedTrackIds: ['known-track', 'missing-track'], generatedTrackCount: 2, seed: 'seed', recipeRevision: 'recipe', algorithmVersion: 'algorithm', sourceDependencies: [], generatedAt: '2026-01-01T00:00:00Z', unavailableReason: null }
const knownSong = { id: 'known-track', name: 'Known song', href: 'https://spotify.test/known-track', uri: 'spotify:track:known-track', album: { id: 'known-album', name: 'Known album', href: null, uri: null, releaseDate: null, imageUrl: 'https://example.invalid/known-art' }, artists: [{ id: null, name: 'Known artist', href: null, uri: null }], durationMs: 1000, explicit: false, available: true }

function frontendFetch(options: { libraryBody?: unknown; sessionStatus?: number } = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    if (path === '/api/v1/session') return response(options.sessionStatus === undefined ? { userId: 'operator', csrfToken: 'csrf', expiresAt: '2026-01-01T00:00:00Z' } : { code: 'unauthorized', message: 'Please connect Spotify', requestId: 'req-1', details: {} }, options.sessionStatus ?? 200)
    if (path === '/api/v1/library') return response(options.libraryBody ?? library, options.libraryBody instanceof Error ? 500 : 200)
    if (path.includes('/preview')) return response(preview)
    if (path.includes('/publish-plan')) return response({ definitionId: definition.definitionId, playlistName: definition.name, action: 'create', candidates: [], message: null, publishFlowId: 'flow-1' })
    if (path.includes('/publish')) return response({ definitionId: definition.definitionId, spotifyPlaylistId: 'managed', createdAt: '2026-01-01T00:00:00Z', lastSyncedAt: '2026-01-01T00:00:00Z', lastSeenSnapshotId: 'snap-1', canSync: true, managementStatus: 'butler_created' })
    if (path.includes('/current')) return response({ current: null })
    if (path.includes('/songs')) return response({ items: [knownSong], missingIds: ['missing-track'] })
    return response({})
  })
}

afterEach(() => { vi.unstubAllGlobals() })

describe('App', () => {
  it('renders album art with public Spotify links in the header and track row', async () => {
    const fetcher = frontendFetch()
    vi.stubGlobal('fetch', fetcher)
    Object.defineProperty(window, 'fetch', { configurable: true, value: fetcher })
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('.art-header').attributes('alt')).toBe('Selected album artwork')
    expect(wrapper.find('.art-header').element.parentElement?.getAttribute('href')).toBe('https://open.spotify.com/album/known-album')
    expect(wrapper.find('.art-track').attributes('alt')).toBe('Album artwork')
    expect(wrapper.find('.art-track').element.parentElement?.getAttribute('href')).toBe('https://open.spotify.com/album/known-album')
  })

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
    expect(wrapper.find('.enrichment-pending').attributes('aria-label')).toBe('Track ID enrichment pending')
    expect(wrapper.find('[aria-label="Refresh saved_tracks"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Shuffle staged track sequence"]').exists()).toBe(true)
    expect((wrapper.find('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(false)
  })

  it('consolidates definition metadata and destination actions into the playlist information panel', async () => {
    const fetcher = frontendFetch()
    vi.stubGlobal('fetch', fetcher)
    Object.defineProperty(window, 'fetch', { configurable: true, value: fetcher })
    const wrapper = mount(App)
    await flushPromises()

    const playlistInfo = wrapper.find('.playlist-info')
    const selection = wrapper.find('.selection')
    const infoActions = playlistInfo.findAll('button').map(button => button.text())
    const selectionActions = selection.findAll('button').map(button => button.text())

    expect(wrapper.find('.hud h1').text()).toBe('BUTLER // PLAYLIST STUDIO')
    expect(wrapper.find('.hud > .eyebrow').exists()).toBe(false)
    expect(wrapper.findAll('.playlist-info')).toHaveLength(1)
    expect(wrapper.find('.telemetry').exists()).toBe(false)
    expect(wrapper.find('.destination').exists()).toBe(false)
    expect(playlistInfo.text()).toContain('SEED')
    expect(playlistInfo.text()).toContain('RECIPE REVISION')
    expect(playlistInfo.text()).toContain('GENERATED')
    expect(playlistInfo.text()).toContain('NO BUTLER DESTINATION')
    expect(playlistInfo.find('.seed-value').attributes('title')).toBe('seed')
    expect(playlistInfo.find('.tag').exists()).toBe(false)
    expect(playlistInfo.find('.playlist-info-action-panel').exists()).toBe(true)
    expect(infoActions).toContain('REROLL PREVIEW')
    expect(infoActions).toContain('PUBLISH')
    expect(selectionActions).not.toContain('REROLL PREVIEW')
    expect(selectionActions).not.toContain('PUBLISH')
    expect(selectionActions).toContain('SHUFFLE')
    expect(selection.text()).not.toContain('Preview IDs are authoritative')
    expect(selection.find('.section-title .counter').text()).toBe('2')
    const sidebarMissions = wrapper.findAll('.sidebar .mission')
    expect(sidebarMissions).toHaveLength(2)
    expect(sidebarMissions.find(mission => mission.text().includes(definition.name))?.classes()).toContain('definition-mission')
    expect(sidebarMissions.find(mission => mission.text().includes(playlist.name))?.classes()).not.toContain('definition-mission')
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

  it('offers Publish without legacy one-time or editable destination controls', async () => {
    const fetcher = frontendFetch()
    vi.stubGlobal('fetch', fetcher)
    Object.defineProperty(window, 'fetch', { configurable: true, value: fetcher })
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.text()).toContain('PUBLISH')
    expect(wrapper.text()).not.toContain('ONE-TIME UPDATE')
    expect(wrapper.text()).not.toContain('CREATE DESTINATION')
    await wrapper.findAll('button').find(button => button.text() === 'PUBLISH')?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Create a new playlist?')
    await wrapper.find('form[aria-labelledby="publish-title"]').trigger('submit')
    await flushPromises()
    expect(fetcher.mock.calls.some(([path]) => String(path).includes('/publish'))).toBe(true)
  })

  it('keeps the top-level progress indicator visible through pending publish and library loading', async () => {
    let releasePublish!: (value: Response) => void
    let releaseLibraryReload!: (value: Response) => void
    let libraryCalls = 0
    const publishResponse = new Promise<Response>(resolve => { releasePublish = resolve })
    const libraryReloadResponse = new Promise<Response>(resolve => { releaseLibraryReload = resolve })
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/library') {
        libraryCalls += 1
        if (libraryCalls === 2) return libraryReloadResponse
      }
      if (path.includes('/publish') && !path.includes('/publish-plan')) return publishResponse
      return frontendFetch()(input, init)
    })
    vi.stubGlobal('fetch', fetcher)
    Object.defineProperty(window, 'fetch', { configurable: true, value: fetcher })
    const wrapper = mount(App)
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === 'PUBLISH')?.trigger('click')
    await flushPromises()
    await wrapper.find('form[aria-labelledby="publish-title"]').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.hud .top-progress').exists()).toBe(false)
    expect(wrapper.find('.top-progress').exists()).toBe(true)
    expect(wrapper.find('.dialog-backdrop').exists()).toBe(true)
    expect(wrapper.find('.top-progress').element.parentElement).toBe(wrapper.find('.hud').element.parentElement)

    releasePublish(response({ definitionId: definition.definitionId, spotifyPlaylistId: 'managed', createdAt: '2026-01-01T00:00:00Z', lastSyncedAt: '2026-01-01T00:00:00Z', lastSeenSnapshotId: 'snap-1', canSync: true, managementStatus: 'butler_created' }))
    await flushPromises()
    expect(wrapper.find('.top-progress').exists()).toBe(true)

    releaseLibraryReload(response(library))
    await flushPromises()
    expect(wrapper.find('.top-progress').exists()).toBe(false)
  })

  it('shows the top-level progress indicator while track IDs are being enriched', async () => {
    let releaseSongs!: (value: Response) => void
    const songsResponse = new Promise<Response>(resolve => { releaseSongs = resolve })
    const baseFetcher = frontendFetch()
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/songs')) return songsResponse
      return baseFetcher(input, init)
    })
    vi.stubGlobal('fetch', fetcher)
    Object.defineProperty(window, 'fetch', { configurable: true, value: fetcher })
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('.top-progress').exists()).toBe(true)
    expect(wrapper.find('.enrichment-pending').exists()).toBe(true)

    releaseSongs(response({ items: [knownSong], missingIds: ['missing-track'] }))
    await flushPromises()
    expect(wrapper.find('.top-progress').exists()).toBe(false)
  })
})
