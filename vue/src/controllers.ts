import type { ButlerApi } from './api'
import { reactive } from 'vue'
import type { CurrentDestination, Definition, Library, LibraryPlaylist, Preview, SelectionState, Session, Song } from './types'

type ErrorLike = { status?: number }

const errorMessage = (error: unknown, fallback: string): string => error instanceof Error ? error.message : fallback

export class SessionController {
  state = reactive<{ session: Session | null; loading: boolean; error: string | null }>({ session: null, loading: false, error: null })
  private requestSerial = 0
  private activeRequests = 0

  constructor(private readonly api: ButlerApi) {}

  async load(): Promise<Session | null> { return this.run(() => this.api.getSession()) }
  async refresh(): Promise<Session | null> { return this.run(() => this.api.refreshSession()) }

  async signOut(): Promise<void> {
    const serial = ++this.requestSerial
    this.begin()
    this.state.error = null
    try {
      await this.api.deleteSession()
      if (serial === this.requestSerial) this.state.session = null
    } catch (error) {
      if (serial === this.requestSerial) {
        if ((error as ErrorLike).status === 401) this.state.session = null
        else this.state.error = errorMessage(error, 'Sign-out failed')
      }
    } finally {
      this.finish()
    }
  }

  startOAuth(): void { window.location.assign(`/start?returnTo=${encodeURIComponent(window.location.pathname || '/')}`) }

  private async run(operation: () => Promise<Session>): Promise<Session | null> {
    const serial = ++this.requestSerial
    this.begin()
    this.state.error = null
    try {
      const session = await operation()
      if (serial !== this.requestSerial) return null
      this.state.session = session
      return session
    } catch (error) {
      if (serial !== this.requestSerial) return null
      if ((error as ErrorLike).status === 401) this.state.session = null
      else this.state.error = errorMessage(error, 'Session request failed')
      return null
    } finally {
      this.finish()
    }
  }

  private begin(): void { this.activeRequests += 1; this.state.loading = true }
  private finish(): void { this.activeRequests -= 1; this.state.loading = this.activeRequests > 0 }
}

export class LibraryController {
  state = reactive<{ library: Library | null; definitions: Definition[]; loading: boolean; error: string | null }>({ library: null, definitions: [], loading: false, error: null })
  private requestSerial = 0
  private activeRequests = 0

  constructor(private readonly api: ButlerApi) {}

  async load(): Promise<Library | null> { return this.replace(() => this.api.getLibrary(), 'Library request failed') }
  async refreshAll(): Promise<Library | null> { return this.replace(() => this.api.refreshLibrary(), 'Library refresh failed') }
  async refreshSources(sourceKeys: readonly string[]): Promise<Library | null> { return this.replace(() => this.api.refreshLibrary(sourceKeys), 'Library refresh failed') }

  private async replace(operation: () => Promise<Library>, fallback: string): Promise<Library | null> {
    const serial = ++this.requestSerial
    this.begin()
    this.state.error = null
    try {
      const library = await operation()
      if (serial !== this.requestSerial) return null
      this.state.library = library
      this.state.definitions = [...library.definitions]
      return library
    } catch (error) {
      if (serial === this.requestSerial) this.state.error = errorMessage(error, fallback)
      return null
    } finally {
      this.finish()
    }
  }

  private begin(): void { this.activeRequests += 1; this.state.loading = true }
  private finish(): void { this.activeRequests -= 1; this.state.loading = this.activeRequests > 0 }
}

export class StudioController {
  state = reactive<{
    activeKind: 'definition' | 'library_playlist' | null
    definition: Definition | null
    libraryPlaylist: LibraryPlaylist | null
    preview: Preview | null
    current: CurrentDestination | null
    selection: SelectionState
    loading: boolean
    error: string | null
    conflict: boolean
    oneTimeResult: string | null
  }>({
    activeKind: null,
    definition: null,
    libraryPlaylist: null,
    preview: null,
    current: null,
    selection: { source: 'preview', dirty: false, orderedIds: [], enrichment: {} },
    loading: false,
    error: null,
    conflict: false,
    oneTimeResult: null,
  })
  private operationSerial = 0
  private activeOperations = 0

  constructor(private readonly api: ButlerApi, private readonly seedGenerator: () => string = () => crypto.randomUUID()) {}

  async load(definition: Definition): Promise<void> { await this.selectDefinition(definition) }

  async selectDefinition(definitionOrId: Definition | string): Promise<void> {
    const serial = this.beginOperation()
    this.state.error = null
    try {
      const definition = typeof definitionOrId === 'string' ? await this.api.getDefinition(definitionOrId) : definitionOrId
      if (!this.isCurrent(serial)) return
      const [preview, current] = await Promise.all([
        this.api.previewDefinition(definition.definitionId),
        this.api.getCurrentDestination(definition.definitionId),
      ])
      if (!this.isCurrent(serial)) return
      const usePreview = preview.status !== 'unavailable'
      const ids = usePreview ? [...preview.generatedTrackIds] : [...(current?.trackIds ?? [])]
      this.state.activeKind = 'definition'
      this.state.definition = definition
      this.state.libraryPlaylist = null
      this.state.preview = preview
      this.state.current = current
      this.state.selection = { source: usePreview ? 'preview' : 'current-fallback', dirty: false, orderedIds: ids, enrichment: {} }
      const enrichment = await this.enrich(ids)
      if (this.isCurrent(serial)) this.state.selection = { source: usePreview ? 'preview' : 'current-fallback', dirty: false, orderedIds: ids, enrichment }
    } catch (error) {
      if (this.isCurrent(serial)) this.state.error = errorMessage(error, 'Studio request failed')
    } finally {
      this.finishOperation()
    }
  }

  async selectLibraryPlaylist(summary: LibraryPlaylist): Promise<void> {
    const serial = this.beginOperation()
    this.state.error = null
    try {
      const detail = await this.api.getLibraryPlaylist(summary.spotifyPlaylistId)
      if (!this.isCurrent(serial)) return
      const ids = [...detail.trackIds]
      this.state.activeKind = 'library_playlist'
      this.state.libraryPlaylist = detail.summary
      this.state.definition = null
      this.state.preview = null
      this.state.current = null
      this.state.selection = { source: 'preview', dirty: false, orderedIds: ids, enrichment: {} }
      const enrichment = await this.enrich(ids)
      if (this.isCurrent(serial)) this.state.selection = { source: 'preview', dirty: false, orderedIds: ids, enrichment }
    } catch (error) {
      if (this.isCurrent(serial)) this.state.error = errorMessage(error, 'Library playlist request failed')
    } finally {
      this.finishOperation()
    }
  }

  async reloadActive(): Promise<void> {
    if (this.state.activeKind === 'definition' && this.state.definition) await this.selectDefinition(this.state.definition)
    else if (this.state.activeKind === 'library_playlist' && this.state.libraryPlaylist) await this.selectLibraryPlaylist(this.state.libraryPlaylist)
  }

  async reroll(): Promise<void> {
    if (this.state.activeKind !== 'definition' || !this.state.definition) return
    const serial = this.beginOperation()
    const definitionId = this.state.definition.definitionId
    this.state.error = null
    try {
      const preview = await this.api.previewDefinition(definitionId, this.seedGenerator())
      if (!this.isCurrent(serial)) return
      const ids = [...preview.generatedTrackIds]
      this.state.preview = preview
      this.state.selection = { source: 'preview', dirty: false, orderedIds: ids, enrichment: {} }
      const enrichment = await this.enrich(ids)
      if (this.isCurrent(serial)) this.state.selection = { source: 'preview', dirty: false, orderedIds: ids, enrichment }
    } catch (error) {
      if (this.isCurrent(serial)) this.state.error = errorMessage(error, 'Reroll failed')
    } finally {
      this.finishOperation()
    }
  }

  moveTrack(index: number, direction: -1 | 1): void {
    if (this.state.activeKind !== 'definition') return
    const target = index + direction
    if (index < 0 || target < 0 || target >= this.state.selection.orderedIds.length) return
    this.operationSerial += 1
    const ids = [...this.state.selection.orderedIds]
    ;[ids[index], ids[target]] = [ids[target], ids[index]]
    this.state.selection = { ...this.state.selection, dirty: true, orderedIds: ids }
  }

  moveTrackTo(index: number, target: number): void {
    if (this.state.activeKind !== 'definition' || index < 0 || target < 0 || index >= this.state.selection.orderedIds.length || target >= this.state.selection.orderedIds.length || index === target) return
    this.operationSerial += 1
    const ids = [...this.state.selection.orderedIds]
    const [moved] = ids.splice(index, 1)
    ids.splice(target, 0, moved)
    this.state.selection = { ...this.state.selection, dirty: true, orderedIds: ids }
  }

  removeTrack(index: number): void {
    if (this.state.activeKind !== 'definition' || index < 0 || index >= this.state.selection.orderedIds.length) return
    this.operationSerial += 1
    this.state.selection = { ...this.state.selection, dirty: true, orderedIds: this.state.selection.orderedIds.filter((_, itemIndex) => itemIndex !== index) }
  }

  async createDestination(input: { name?: string; description?: string; public?: boolean; collaborative?: boolean } = {}): Promise<void> {
    if (this.state.activeKind !== 'definition' || !this.state.definition) return
    const serial = this.beginOperation()
    const definitionId = this.state.definition.definitionId
    this.state.error = null
    try {
      const destination = await this.api.createDestination(definitionId, input)
      if (this.isCurrent(serial)) {
        this.state.definition = { ...this.state.definition, destination }
        this.state.current = null
      }
    } catch (error) {
      if (this.isCurrent(serial)) this.state.error = errorMessage(error, 'Destination creation failed')
    } finally {
      this.finishOperation()
    }
  }

  async sync(): Promise<boolean> {
    if (this.state.activeKind !== 'definition' || !this.state.definition?.destination) {
      this.state.error = 'Create a Butler destination before recurring synchronization.'
      return false
    }
    const serial = this.beginOperation()
    const definition = this.state.definition
    const destination = definition.destination
    const expectedSnapshotId = this.state.current?.lastSeenSnapshotId ?? destination?.lastSeenSnapshotId ?? null
    this.state.error = null
    this.state.conflict = false
    try {
      const current = await this.api.syncDestination(definition.definitionId, this.state.selection.orderedIds, expectedSnapshotId)
      if (!this.isCurrent(serial)) return false
      this.state.current = current
      this.updateDestinationFromCurrent(current)
      if (current) this.state.selection = { ...this.state.selection, dirty: false }
      return current !== null
    } catch (error) {
      if (this.isCurrent(serial)) {
        if ((error as ErrorLike).status === 409) this.state.conflict = true
        this.state.error = errorMessage(error, 'Synchronization failed')
      }
      return false
    } finally {
      this.finishOperation()
    }
  }

  async oneTimeUpdate(playlistId: string): Promise<boolean> {
    if (this.state.activeKind !== 'definition' || !this.state.definition || !playlistId.trim()) {
      this.state.error = 'A Spotify playlist ID is required.'
      return false
    }
    const serial = this.beginOperation()
    const definitionId = this.state.definition.definitionId
    this.state.error = null
    try {
      const result = await this.api.oneTimeUpdate(definitionId, playlistId.trim(), this.state.selection.orderedIds)
      if (!this.isCurrent(serial)) return false
      if (result.tracked !== false) throw new Error('Server returned an invalid tracked state')
      this.state.oneTimeResult = `Updated ${result.spotifyPlaylistId}; this playlist remains unmanaged (tracked=false).`
      return true
    } catch (error) {
      if (this.isCurrent(serial)) this.state.error = errorMessage(error, 'One-time update failed')
      return false
    } finally {
      this.finishOperation()
    }
  }

  private async enrich(ids: readonly string[]): Promise<Record<string, Song | undefined>> {
    const songs = await this.api.getSongs(ids)
    const byId: Record<string, Song | undefined> = {}
    songs.forEach(song => { byId[song.id] = song })
    return byId
  }

  private updateDestinationFromCurrent(current: CurrentDestination | null): void {
    if (!current || !this.state.definition?.destination) return
    this.state.definition = {
      ...this.state.definition,
      destination: {
        ...this.state.definition.destination,
        lastSyncedAt: current.lastSyncedAt,
        lastSeenSnapshotId: current.lastSeenSnapshotId,
      },
    }
  }

  private beginOperation(): number { this.operationSerial += 1; this.activeOperations += 1; this.state.loading = true; return this.operationSerial }
  private finishOperation(): void { this.activeOperations -= 1; this.state.loading = this.activeOperations > 0 }
  private isCurrent(serial: number): boolean { return serial === this.operationSerial }
}
