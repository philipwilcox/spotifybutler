import type {
  ApiErrorDto,
  CurrentDestination,
  Definition,
  Destination,
  Library,
  LibraryPlaylist,
  LibraryPlaylistDetail,
  OneTimeUpdate,
  Preview,
  Session,
  Song,
  SourceDependency,
  SourceSnapshot,
} from './types'

export class ContractValidationError extends Error {
  constructor(readonly path: string, message: string) {
    super(`${path}: ${message}`)
    this.name = 'ContractValidationError'
  }
}

const fail = (path: string, message: string): never => { throw new ContractValidationError(path, message) }
const object = (value: unknown, path: string): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : fail(path, 'expected object')
const string = (value: unknown, path: string): string => typeof value === 'string' ? value : fail(path, 'expected string')
const bool = (value: unknown, path: string): boolean => typeof value === 'boolean' ? value : fail(path, 'expected boolean')
const number = (value: unknown, path: string): number => typeof value === 'number' && Number.isFinite(value) ? value : fail(path, 'expected finite number')
const integer = (value: unknown, path: string): number => number(value, path) % 1 === 0 ? value as number : fail(path, 'expected integer')
const nonNegativeInteger = (value: unknown, path: string): number => integer(value, path) >= 0 ? value as number : fail(path, 'expected non-negative integer')
const nullableString = (value: unknown, path: string): string | null => value === null ? null : string(value, path)
const array = (value: unknown, path: string): unknown[] => Array.isArray(value) ? value : fail(path, 'expected array')
const required = (value: Record<string, unknown>, key: string, path: string): unknown => key in value ? value[key] : fail(`${path}.${key}`, 'is required')
const oneOf = <T extends string>(value: unknown, values: readonly T[], path: string): T => {
  const candidate = string(value, path)
  return values.includes(candidate as T) ? candidate as T : fail(path, `unsupported value ${candidate}`)
}

const dependency = (value: unknown, path: string): SourceDependency => {
  const v = object(value, path)
  const itemCount = required(v, 'itemCount', path)
  return {
    sourceKey: string(required(v, 'sourceKey', path), `${path}.sourceKey`),
    resourceKind: string(required(v, 'resourceKind', path), `${path}.resourceKind`),
    sourceRevision: nullableString(required(v, 'sourceRevision', path), `${path}.sourceRevision`),
    lastSyncedAt: nullableString(required(v, 'lastSyncedAt', path), `${path}.lastSyncedAt`),
    itemCount: itemCount === null ? null : nonNegativeInteger(itemCount, `${path}.itemCount`),
    usable: bool(required(v, 'usable', path), `${path}.usable`),
  }
}

const recipe = (value: unknown, path: string): Definition['recipe'] => {
  const v = object(value, path)
  const checkNode = (node: unknown, nodePath: string): void => {
    const n = object(node, nodePath)
    const type = string(required(n, 'type', nodePath), `${nodePath}.type`)
    const supported = [
      'saved_tracks', 'top_tracks', 'playlist_items', 'union', 'difference', 'filtered', 'all', 'and', 'or', 'not',
      'release_year_range', 'duration_range', 'album_id_in', 'artist_id_in', 'added_at_range', 'explicitness',
      'not_in_top_artists', 'not_in_top_tracks', 'by', 'keep_all', 'seeded_random', 'added_at_ascending',
      'added_at_descending', 'release_date_ascending', 'release_date_descending',
    ] as const
    oneOf(type, supported, `${nodePath}.type`)
    for (const key of ['sources', 'predicates']) {
      if (key in n) array(n[key], `${nodePath}.${key}`).forEach((item, index) => checkNode(item, `${nodePath}.${key}[${index}]`))
    }
    for (const key of ['left', 'right', 'source', 'predicate']) {
      if (key in n) checkNode(n[key], `${nodePath}.${key}`)
    }
    if (type === 'playlist_items') string(required(n, 'playlistName', nodePath), `${nodePath}.playlistName`)
    if (type === 'explicitness') bool(required(n, 'value', nodePath), `${nodePath}.value`)
    if (type === 'album_id_in' || type === 'artist_id_in') {
      const key = type === 'album_id_in' ? 'albumIds' : 'artistIds'
      array(required(n, key, nodePath), `${nodePath}.${key}`).forEach((item, index) => string(item, `${nodePath}.${key}[${index}]`))
    }
    if (type === 'by') oneOf(required(n, 'identity', nodePath), ['SpotifyUri'] as const, `${nodePath}.identity`)
  }

  checkNode(required(v, 'source', path), `${path}.source`)
  checkNode(required(v, 'predicate', path), `${path}.predicate`)
  checkNode(required(v, 'distinctness', path), `${path}.distinctness`)
  const selection = object(required(v, 'selection', path), `${path}.selection`)
  const target = required(selection, 'target', `${path}.selection`)
  if (target !== null) nonNegativeInteger(target, `${path}.selection.target`)
  const quotas = array(required(selection, 'quotas', `${path}.selection`), `${path}.selection.quotas`)
  quotas.forEach((item, index) => {
    const quotaPath = `${path}.selection.quotas[${index}]`
    const quota = object(item, quotaPath)
    oneOf(required(quota, 'dimension', quotaPath), ['PrimaryArtistId', 'AlbumId'] as const, `${quotaPath}.dimension`)
    nonNegativeInteger(required(quota, 'maximum', quotaPath), `${quotaPath}.maximum`)
  })
  checkNode(required(selection, 'rankBy', `${path}.selection`), `${path}.selection.rankBy`)
  checkNode(required(v, 'ordering', path), `${path}.ordering`)
  return {
    schemaVersion: nonNegativeInteger(required(v, 'schemaVersion', path), `${path}.schemaVersion`),
    source: v.source as Record<string, unknown>,
    predicate: v.predicate as Record<string, unknown>,
    distinctness: v.distinctness as Record<string, unknown>,
    selection: v.selection as Record<string, unknown>,
    ordering: v.ordering as Record<string, unknown>,
  }
}

export const parseSession = (value: unknown): Session => {
  const v = object(value, 'session')
  return {
    userId: string(required(v, 'userId', 'session'), 'session.userId'),
    csrfToken: string(required(v, 'csrfToken', 'session'), 'session.csrfToken'),
    expiresAt: string(required(v, 'expiresAt', 'session'), 'session.expiresAt'),
  }
}

export const parseSourceSnapshot = (value: unknown, path = 'source'): SourceSnapshot => {
  const v = object(value, path)
  const itemCount = required(v, 'itemCount', path)
  return {
    sourceKey: string(required(v, 'sourceKey', path), `${path}.sourceKey`),
    resourceKind: string(required(v, 'resourceKind', path), `${path}.resourceKind`),
    sourceRevision: nullableString(required(v, 'sourceRevision', path), `${path}.sourceRevision`),
    lastSyncedAt: nullableString(required(v, 'lastSyncedAt', path), `${path}.lastSyncedAt`),
    itemCount: itemCount === null ? null : nonNegativeInteger(itemCount, `${path}.itemCount`),
    status: oneOf(required(v, 'status', path), ['empty', 'ready', 'refreshing', 'stale', 'error'] as const, `${path}.status`),
    canRefresh: bool(required(v, 'canRefresh', path), `${path}.canRefresh`),
    lastErrorCode: nullableString(required(v, 'lastErrorCode', path), `${path}.lastErrorCode`),
    lastErrorAt: nullableString(required(v, 'lastErrorAt', path), `${path}.lastErrorAt`),
  }
}

export const parseDestination = (value: unknown, path = 'destination'): Destination => {
  const v = object(value, path)
  return {
    definitionId: string(required(v, 'definitionId', path), `${path}.definitionId`),
    spotifyPlaylistId: string(required(v, 'spotifyPlaylistId', path), `${path}.spotifyPlaylistId`),
    createdAt: string(required(v, 'createdAt', path), `${path}.createdAt`),
    lastSyncedAt: nullableString(required(v, 'lastSyncedAt', path), `${path}.lastSyncedAt`),
    lastSeenSnapshotId: nullableString(required(v, 'lastSeenSnapshotId', path), `${path}.lastSeenSnapshotId`),
    canSync: bool(required(v, 'canSync', path), `${path}.canSync`),
    managementStatus: oneOf(required(v, 'managementStatus', path), ['butler_created'] as const, `${path}.managementStatus`),
  }
}

export const parseDefinition = (value: unknown, path = 'definition'): Definition => {
  const v = object(value, path)
  const kind = oneOf(required(v, 'kind', path), ['built_in', 'owner'] as const, `${path}.kind`)
  const destinationValue = required(v, 'destination', path)
  return {
    definitionId: string(required(v, 'definitionId', path), `${path}.definitionId`),
    name: string(required(v, 'name', path), `${path}.name`),
    description: string(required(v, 'description', path), `${path}.description`),
    kind,
    editable: bool(required(v, 'editable', path), `${path}.editable`),
    enabled: bool(required(v, 'enabled', path), `${path}.enabled`),
    recipe: recipe(required(v, 'recipe', path), `${path}.recipe`),
    sourceDependencies: array(required(v, 'sourceDependencies', path), `${path}.sourceDependencies`).map((item, i) => dependency(item, `${path}.sourceDependencies[${i}]`)),
    destination: destinationValue === null ? null : parseDestination(destinationValue, `${path}.destination`),
  }
}

export const parseLibraryPlaylist = (value: unknown, path = 'playlist'): LibraryPlaylist => {
  const v = object(value, path)
  const declaredItemCount = required(v, 'declaredItemCount', path)
  return {
    spotifyPlaylistId: string(required(v, 'spotifyPlaylistId', path), `${path}.spotifyPlaylistId`),
    name: string(required(v, 'name', path), `${path}.name`),
    description: nullableString(required(v, 'description', path), `${path}.description`),
    href: string(required(v, 'href', path), `${path}.href`),
    uri: string(required(v, 'uri', path), `${path}.uri`),
    displayUrl: nullableString(required(v, 'displayUrl', path), `${path}.displayUrl`),
    declaredItemCount: declaredItemCount === null ? null : nonNegativeInteger(declaredItemCount, `${path}.declaredItemCount`),
    cachedPlayableTrackCount: nonNegativeInteger(required(v, 'cachedPlayableTrackCount', path), `${path}.cachedPlayableTrackCount`),
    contentSourceKey: string(required(v, 'contentSourceKey', path), `${path}.contentSourceKey`),
    contentStatus: oneOf(required(v, 'contentStatus', path), ['empty', 'ready', 'refreshing', 'stale', 'error'] as const, `${path}.contentStatus`),
    sourceRevision: nullableString(required(v, 'sourceRevision', path), `${path}.sourceRevision`),
    lastSyncedAt: nullableString(required(v, 'lastSyncedAt', path), `${path}.lastSyncedAt`),
  }
}

export const parseLibraryPlaylistDetail = (value: unknown): LibraryPlaylistDetail => {
  const v = object(value, 'playlistDetail')
  return {
    summary: parseLibraryPlaylist(required(v, 'summary', 'playlistDetail'), 'playlistDetail.summary'),
    trackIds: array(required(v, 'trackIds', 'playlistDetail'), 'playlistDetail.trackIds').map((item, i) => string(item, `playlistDetail.trackIds[${i}]`)),
  }
}

export const parseLibrary = (value: unknown): Library => {
  const v = object(value, 'library')
  return {
    ownerSpotifyUserId: string(required(v, 'ownerSpotifyUserId', 'library'), 'library.ownerSpotifyUserId'),
    status: oneOf(required(v, 'status', 'library'), ['empty', 'ready', 'partial', 'refreshing', 'stale'] as const, 'library.status'),
    sources: array(required(v, 'sources', 'library'), 'library.sources').map((item, i) => parseSourceSnapshot(item, `library.sources[${i}]`)),
    definitions: array(required(v, 'definitions', 'library'), 'library.definitions').map((item, i) => parseDefinition(item, `library.definitions[${i}]`)),
    playlists: array(required(v, 'playlists', 'library'), 'library.playlists').map((item, i) => parseLibraryPlaylist(item, `library.playlists[${i}]`)),
  }
}

export const parseDefinitionList = (value: unknown): Definition[] => {
  const v = object(value, 'definitions')
  return array(required(v, 'items', 'definitions'), 'definitions.items').map((item, i) => parseDefinition(item, `definitions.items[${i}]`))
}

export const parsePreview = (value: unknown): Preview => {
  const v = object(value, 'preview')
  return {
    definitionId: string(required(v, 'definitionId', 'preview'), 'preview.definitionId'),
    status: oneOf(required(v, 'status', 'preview'), ['ready', 'empty', 'partial', 'unavailable'] as const, 'preview.status'),
    generatedTrackIds: array(required(v, 'generatedTrackIds', 'preview'), 'preview.generatedTrackIds').map((item, i) => string(item, `preview.generatedTrackIds[${i}]`)),
    generatedTrackCount: nonNegativeInteger(required(v, 'generatedTrackCount', 'preview'), 'preview.generatedTrackCount'),
    seed: string(required(v, 'seed', 'preview'), 'preview.seed'),
    recipeRevision: string(required(v, 'recipeRevision', 'preview'), 'preview.recipeRevision'),
    algorithmVersion: string(required(v, 'algorithmVersion', 'preview'), 'preview.algorithmVersion'),
    sourceDependencies: array(required(v, 'sourceDependencies', 'preview'), 'preview.sourceDependencies').map((item, i) => dependency(item, `preview.sourceDependencies[${i}]`)),
    generatedAt: string(required(v, 'generatedAt', 'preview'), 'preview.generatedAt'),
    unavailableReason: nullableString(required(v, 'unavailableReason', 'preview'), 'preview.unavailableReason'),
  }
}

export const parseCurrent = (value: unknown): CurrentDestination | null => {
  const v = object(value, 'current')
  const current = required(v, 'current', 'current')
  if (current === null) return null
  const c = object(current, 'current.current')
  return {
    spotifyPlaylistId: string(required(c, 'spotifyPlaylistId', 'current.current'), 'current.current.spotifyPlaylistId'),
    trackIds: array(required(c, 'trackIds', 'current.current'), 'current.current.trackIds').map((item, i) => string(item, `current.current.trackIds[${i}]`)),
    lastSyncedAt: nullableString(required(c, 'lastSyncedAt', 'current.current'), 'current.current.lastSyncedAt'),
    lastSeenSnapshotId: nullableString(required(c, 'lastSeenSnapshotId', 'current.current'), 'current.current.lastSeenSnapshotId'),
  }
}

const parseSong = (value: unknown, path: string): Song => {
  const v = object(value, path)
  const album = object(required(v, 'album', path), `${path}.album`)
  const artists = array(required(v, 'artists', path), `${path}.artists`).map((item, i) => {
    const artistPath = `${path}.artists[${i}]`
    const a = object(item, artistPath)
    return {
      id: nullableString(required(a, 'id', artistPath), `${artistPath}.id`),
      name: nullableString(required(a, 'name', artistPath), `${artistPath}.name`),
      href: nullableString(required(a, 'href', artistPath), `${artistPath}.href`),
      uri: nullableString(required(a, 'uri', artistPath), `${artistPath}.uri`),
    }
  })
  const durationMs = required(v, 'durationMs', path)
  return {
    id: string(required(v, 'id', path), `${path}.id`),
    name: string(required(v, 'name', path), `${path}.name`),
    href: string(required(v, 'href', path), `${path}.href`),
    uri: string(required(v, 'uri', path), `${path}.uri`),
    album: {
      id: nullableString(required(album, 'id', `${path}.album`), `${path}.album.id`),
      name: nullableString(required(album, 'name', `${path}.album`), `${path}.album.name`),
      href: nullableString(required(album, 'href', `${path}.album`), `${path}.album.href`),
      uri: nullableString(required(album, 'uri', `${path}.album`), `${path}.album.uri`),
      releaseDate: nullableString(required(album, 'releaseDate', `${path}.album`), `${path}.album.releaseDate`),
    },
    artists,
    durationMs: durationMs === null ? null : nonNegativeInteger(durationMs, `${path}.durationMs`),
    explicit: required(v, 'explicit', path) === null ? null : bool(required(v, 'explicit', path), `${path}.explicit`),
    available: bool(required(v, 'available', path), `${path}.available`),
  }
}

export const parseSongs = (value: unknown): { items: Song[]; missingIds: string[] } => {
  const v = object(value, 'songs')
  return {
    items: array(required(v, 'items', 'songs'), 'songs.items').map((item, i) => parseSong(item, `songs.items[${i}]`)),
    missingIds: array(required(v, 'missingIds', 'songs'), 'songs.missingIds').map((item, i) => string(item, `songs.missingIds[${i}]`)),
  }
}

export const parseOneTimeUpdate = (value: unknown): OneTimeUpdate => {
  const v = object(value, 'oneTimeUpdate')
  if (required(v, 'tracked', 'oneTimeUpdate') !== false) fail('oneTimeUpdate.tracked', 'must be false')
  return {
    spotifyPlaylistId: string(required(v, 'spotifyPlaylistId', 'oneTimeUpdate'), 'oneTimeUpdate.spotifyPlaylistId'),
    trackIds: array(required(v, 'trackIds', 'oneTimeUpdate'), 'oneTimeUpdate.trackIds').map((item, i) => string(item, `oneTimeUpdate.trackIds[${i}]`)),
    lastSeenSnapshotId: nullableString(required(v, 'lastSeenSnapshotId', 'oneTimeUpdate'), 'oneTimeUpdate.lastSeenSnapshotId'),
    appliedAt: string(required(v, 'appliedAt', 'oneTimeUpdate'), 'oneTimeUpdate.appliedAt'),
    tracked: false,
  }
}

export const parseError = (value: unknown): ApiErrorDto => {
  const v = object(value, 'error')
  const details = object(required(v, 'details', 'error'), 'error.details')
  return {
    code: string(required(v, 'code', 'error'), 'error.code'),
    message: string(required(v, 'message', 'error'), 'error.message'),
    requestId: string(required(v, 'requestId', 'error'), 'error.requestId'),
    details: Object.fromEntries(Object.entries(details).map(([key, item]) => [key, string(item, `error.details.${key}`)])),
  }
}
