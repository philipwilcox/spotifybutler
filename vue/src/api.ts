import type { ApiErrorDto, CurrentDestination, Definition, Library, LibraryPlaylistDetail, Preview, PublishPlan, Session, Song } from './types'
import { parseCurrent, parseDefinition, parseDefinitionList, parseDestination, parseError, parseLibrary, parseLibraryPlaylistDetail, parsePreview, parsePublishPlan, parseSession, parseSongs } from './validation'

export type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

export class ButlerApiError extends Error {
  constructor(readonly error: ApiErrorDto, readonly status: number) {
    super(error.message)
    this.name = 'ButlerApiError'
  }
}

export interface ButlerApi {
  getSession(): Promise<Session>
  refreshSession(): Promise<Session>
  deleteSession(): Promise<void>
  getLibrary(): Promise<Library>
  refreshLibrary(sourceKeys?: readonly string[]): Promise<Library>
  getLibraryPlaylist(id: string): Promise<LibraryPlaylistDetail>
  listDefinitions(): Promise<Definition[]>
  getDefinition(id: string): Promise<Definition>
  previewDefinition(id: string, seed?: string): Promise<Preview>
  updateRecipeSettings(id: string, shuffleAfterGeneration: boolean): Promise<Definition>
  planPublish(id: string): Promise<PublishPlan>
  publishDestination(id: string, action: 'create' | 'adopt', trackIds: readonly string[], spotifyPlaylistId?: string, publishFlowId?: string): Promise<Definition['destination']>
  getCurrentDestination(id: string): Promise<CurrentDestination | null>
  syncDestination(id: string, trackIds: readonly string[], expectedDestinationSnapshotId?: string | null): Promise<CurrentDestination | null>
  getSongs(ids: readonly string[]): Promise<Song[]>
}

export class ButlerApiClient implements ButlerApi {
  private csrfToken: string | null = null

  constructor(private readonly fetcher: FetchLike = window.fetch.bind(window), private readonly onUnauthorized: () => void = () => {}) {}

  get csrf(): string | null { return this.csrfToken }

  async getSession(): Promise<Session> { return this.request('/api/v1/session', 'GET', undefined, parseSession) }

  async refreshSession(): Promise<Session> { return this.request('/api/v1/session/refresh', 'POST', undefined, parseSession) }

  async deleteSession(): Promise<void> { await this.request('/api/v1/session', 'DELETE', undefined, () => undefined) }

  async getLibrary(): Promise<Library> { return this.request('/api/v1/library', 'GET', undefined, parseLibrary) }

  async refreshLibrary(sourceKeys?: readonly string[]): Promise<Library> {
    return this.request('/api/v1/library/refresh', 'POST', sourceKeys === undefined ? undefined : { sourceKeys }, parseLibrary)
  }
  async getLibraryPlaylist(id: string): Promise<LibraryPlaylistDetail> { return this.request(`/api/v1/library/playlists/${encodeURIComponent(id)}`, 'GET', undefined, parseLibraryPlaylistDetail) }

  async listDefinitions(): Promise<Definition[]> { return this.request('/api/v1/playlists', 'GET', undefined, parseDefinitionList) }

  async getDefinition(id: string): Promise<Definition> { return this.request(`/api/v1/playlists/${encodeURIComponent(id)}`, 'GET', undefined, parseDefinition) }

  async previewDefinition(id: string, seed?: string): Promise<Preview> { return this.request(`/api/v1/playlists/${encodeURIComponent(id)}/preview${seed ? `?seed=${encodeURIComponent(seed)}` : ''}`, 'GET', undefined, parsePreview) }

  async updateRecipeSettings(id: string, shuffleAfterGeneration: boolean): Promise<Definition> {
    return this.request(`/api/v1/playlists/${encodeURIComponent(id)}/recipe-settings`, 'PUT', { shuffleAfterGeneration }, parseDefinition)
  }

  async planPublish(id: string): Promise<PublishPlan> { return this.request(`/api/v1/playlists/${encodeURIComponent(id)}/publish-plan`, 'POST', {}, parsePublishPlan) }

  async publishDestination(id: string, action: 'create' | 'adopt', trackIds: readonly string[], spotifyPlaylistId?: string, publishFlowId?: string): Promise<Definition['destination']> {
    return this.request(`/api/v1/playlists/${encodeURIComponent(id)}/publish`, 'POST', { action, spotifyPlaylistId: spotifyPlaylistId ?? null, trackIds, publishFlowId: publishFlowId ?? null }, parseDestination)
  }

  async getCurrentDestination(id: string): Promise<CurrentDestination | null> { return this.request(`/api/v1/playlists/${encodeURIComponent(id)}/current`, 'GET', undefined, parseCurrent) }

  async syncDestination(id: string, trackIds: readonly string[], expectedDestinationSnapshotId?: string | null): Promise<CurrentDestination | null> { return this.request(`/api/v1/playlists/${encodeURIComponent(id)}/syncs`, 'POST', { trackIds, expectedDestinationSnapshotId: expectedDestinationSnapshotId ?? null }, parseCurrent) }

  async getSongs(ids: readonly string[]): Promise<Song[]> {
    const requestedIds = [...new Set(ids.map(id => id.trim()).filter(id => id.length > 0))]
    if (requestedIds.length === 0) return []
    if (requestedIds.length > 10_000) throw new Error('Too many tracks to enrich in one request')
    const response = await this.request('/api/v1/songs/bulk', 'POST', { trackIds: requestedIds }, parseSongs)
    return response.items
  }

  private async request<T>(path: string, method: string, body: unknown, parse: (value: unknown) => T): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') headers['Origin'] = window.location.origin
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (method !== 'GET' && this.csrfToken) headers['X-CSRF-Token'] = this.csrfToken
    const response = await this.fetcher(path, { method, headers, credentials: 'include', cache: 'no-store', body: body === undefined ? undefined : JSON.stringify(body) })
    if (response.status === 401) { this.csrfToken = null; this.onUnauthorized() }
    if (!response.ok) {
      let error: ApiErrorDto
      try { error = parseError(await response.json()) } catch { error = { code: 'http_error', message: `Request failed (${response.status})`, requestId: response.headers.get('X-Request-Id') ?? 'unknown', details: {} } }
      throw new ButlerApiError(error, response.status)
    }
    if (response.status === 204) {
      if (path === '/api/v1/session' && method === 'DELETE') this.csrfToken = null
      return parse(undefined)
    }
    const value = parse(await response.json())
    if (method === 'GET' && path === '/api/v1/session') this.csrfToken = (value as Session).csrfToken
    if (path === '/api/v1/session/refresh') this.csrfToken = (value as Session).csrfToken
    return value
  }
}
