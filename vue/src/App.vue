<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ButlerApiClient } from './api'
import { LibraryController, SessionController, StudioController } from './controllers'

const api = new ButlerApiClient(undefined, () => session.state.session = null)
const session = new SessionController(api)
const library = new LibraryController(api)
const studio = new StudioController(api)
const showCreate = ref(false)
const showOneTime = ref(false)
const showSyncConfirm = ref(false)
const destinationName = ref('')
const destinationDescription = ref('')
const oneTimePlaylistId = ref('')
const draggedIndex = ref<number | null>(null)

const selected = computed(() => studio.state.selection)
const selectedDefinition = computed(() => studio.state.definition)
const selectedLibraryPlaylist = computed(() => studio.state.libraryPlaylist)
const statusText = computed(() => studio.state.preview?.status ?? selectedLibraryPlaylist.value?.contentStatus ?? 'waiting')
const isBusy = computed(() => session.state.loading || library.state.loading || studio.state.loading)
const songFor = (id: string) => selected.value.enrichment[id]
const formatTime = (value: string | null | undefined) => {
  if (!value) return 'never'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'invalid date' : date.toLocaleString()
}
const buildTimestamp = __BUTLER_BUILD_TIMESTAMP__

async function boot() {
  const currentSession = await session.load()
  if (!currentSession) return
  const loadedLibrary = await library.load()
  const first = loadedLibrary?.definitions[0]
  if (first) await studio.load(first)
}
async function refreshSource(sourceKey?: string) { sourceKey ? await library.refreshSources([sourceKey]) : await library.refreshAll() }
async function choose(definition: (typeof library.state.definitions)[number]) { await studio.selectDefinition(definition) }
async function createDestination() { await studio.createDestination({ name: destinationName.value || undefined, description: destinationDescription.value || undefined }); showCreate.value = false }
async function sync() { if (await studio.sync()) showSyncConfirm.value = false }
async function oneTime() { if (await studio.oneTimeUpdate(oneTimePlaylistId.value)) showOneTime.value = false }
function keyMove(index: number, event: KeyboardEvent) { if (event.key === 'ArrowUp') { event.preventDefault(); studio.moveTrack(index, -1) } if (event.key === 'ArrowDown') { event.preventDefault(); studio.moveTrack(index, 1) } }
function dropTrack(index: number) { if (draggedIndex.value !== null) studio.moveTrackTo(draggedIndex.value, index); draggedIndex.value = null }
onMounted(boot)
</script>

<template>
  <main class="shell">
    <header class="hud panel">
      <div><p class="eyebrow">SPOTIFY // BUTLER</p><h1>PLAYLIST STUDIO</h1></div>
      <div v-if="isBusy" class="hud-progress" role="progressbar" aria-label="Waiting for Spotify Butler" aria-valuetext="Working"><span class="progress-label">WORKING</span><span class="progress-track"><span class="progress-bar" /></span></div>
      <div class="hud-identity">
        <span class="build-stamp" :title="`Frontend bundle built at ${buildTimestamp}`">BUILD {{ buildTimestamp }}</span>
        <div class="hud-status" aria-live="polite"><span class="status-light" :class="session.state.session ? 'online' : 'offline'" />{{ session.state.session ? `OPERATOR ${session.state.session.userId}` : 'AUTHENTICATION REQUIRED' }} <button v-if="session.state.session" class="button quiet" @click="session.signOut">SIGN OUT</button><button v-else class="button gold" @click="session.startOAuth">CONNECT SPOTIFY</button></div>
      </div>
    </header>

    <section v-if="!session.state.session" class="empty panel"><p class="eyebrow">MISSION LOCKED</p><h2>Connect Spotify to open the studio.</h2><p>Your credentials and tokens stay on the Butler server. The browser receives only the scoped session.</p><button class="button gold" @click="session.startOAuth">BEGIN AUTHORIZATION</button><p v-if="session.state.error" class="error">{{ session.state.error }}</p></section>

    <template v-else>
      <div v-if="session.state.error" class="alert error" role="alert">{{ session.state.error }}</div>
      <section class="layout">
        <aside class="panel sidebar">
          <div class="section-title"><span>DEFINED PLAYLISTS</span><span class="counter">{{ library.state.definitions.length }}</span></div>
          <button v-for="definition in library.state.definitions" :key="definition.definitionId" class="mission" :class="{ active: definition.definitionId === selectedDefinition?.definitionId }" @click="choose(definition)"><span class="mission-mark">◆</span><span><strong>{{ definition.name }}</strong><small>{{ definition.kind }} · {{ definition.definitionId }}</small></span></button>
          <p v-if="library.state.loading && !library.state.definitions.length" class="hint">Loading library…</p>
          <p v-else-if="!library.state.loading && !library.state.definitions.length" class="hint">No definitions are available.</p>
          <div class="divider" />
          <div class="section-title"><span>LIBRARY PLAYLISTS</span><span class="counter">{{ library.state.library?.playlists.length || 0 }}</span></div>
          <button v-for="playlist in library.state.library?.playlists" :key="playlist.spotifyPlaylistId" class="mission" @click="studio.selectLibraryPlaylist(playlist)"><span class="mission-mark">♫</span><span><strong>{{ playlist.name }}</strong><small>{{ playlist.cachedPlayableTrackCount }}/{{ playlist.declaredItemCount ?? '—' }} tracks · {{ playlist.contentStatus }}</small></span></button>
          <div class="divider" />
          <div class="section-title"><span>LIBRARY SOURCES</span><button class="icon-button" aria-label="Refresh all sources" @click="refreshSource()">↻</button></div>
          <p v-if="library.state.error" class="error library-error" role="alert">LIBRARY LOAD FAILED: {{ library.state.error }}</p>
          <div v-for="source in library.state.library?.sources" :key="source.sourceKey" class="source-row"><span class="status-light" :class="source.status" /><span class="source-copy"><strong>{{ source.sourceKey }}</strong><small>{{ source.itemCount ?? '—' }} items · {{ formatTime(source.lastSyncedAt) }}</small></span><button v-if="source.canRefresh" class="icon-button" :aria-label="`Refresh ${source.sourceKey}`" @click="refreshSource(source.sourceKey)">↻</button></div>
        </aside>

        <section class="workspace">
          <div v-if="studio.state.error" class="alert error" role="alert">{{ studio.state.error }} <button class="icon-button" aria-label="Dismiss error" @click="studio.state.error = null">×</button></div>
          <div v-if="studio.state.oneTimeResult" class="alert success" role="status">{{ studio.state.oneTimeResult }} <button class="icon-button" aria-label="Dismiss update result" @click="studio.state.oneTimeResult = null">×</button></div>
          <div v-if="studio.state.conflict" class="alert conflict" role="alert"><strong>DESTINATION SNAPSHOT CONFLICT</strong><span>The remote playlist changed. Your staged order is preserved; review it and synchronize again when ready.</span></div>
          <section class="panel mission-header">
            <div><p class="eyebrow">{{ studio.state.activeKind === 'library_playlist' ? 'LIBRARY PLAYLIST' : 'ACTIVE DEFINITION' }} // {{ selectedDefinition?.definitionId || selectedLibraryPlaylist?.spotifyPlaylistId }}</p><h2>{{ selectedDefinition?.name || selectedLibraryPlaylist?.name || 'Select a mission' }}</h2><p>{{ selectedDefinition?.description || selectedLibraryPlaylist?.description || 'Choose a playlist or definition from the sidebar.' }}</p></div>
            <div class="mission-meta"><span class="tag" :class="`tag-${statusText}`">{{ statusText }}</span><span>{{ selected?.orderedIds.length }} TRACKS</span><span v-if="selected?.dirty" class="dirty">● UNSAVED ORDER</span></div>
          </section>
          <section v-if="studio.state.activeKind === 'definition'" class="panel telemetry">
            <div><span class="label">SEED</span><code>{{ studio.state.preview?.seed || '—' }}</code></div><div><span class="label">RECIPE REVISION</span><code>{{ studio.state.preview?.recipeRevision?.slice(0, 16) || '—' }}</code></div><div><span class="label">GENERATED</span><span>{{ formatTime(studio.state.preview?.generatedAt) }}</span></div><button class="button quiet" :disabled="studio.state.loading" @click="studio.reroll">REROLL PREVIEW</button>
          </section>
          <section v-if="studio.state.activeKind === 'definition'" class="panel destination"><div><p class="eyebrow">DESTINATION CONTROL</p><h3>{{ selectedDefinition?.destination ? selectedDefinition.destination.spotifyPlaylistId : 'NO BUTLER DESTINATION' }}</h3><p v-if="selectedDefinition?.destination">Last sync: {{ formatTime(selectedDefinition.destination.lastSyncedAt) }} · snapshot {{ selectedDefinition.destination.lastSeenSnapshotId || 'unseen' }}</p><p v-else>Recurring sync is gated until Butler creates a managed Spotify playlist.</p></div><div class="action-group"><button v-if="!selectedDefinition?.destination" class="button gold" @click="showCreate = true">CREATE DESTINATION</button><button v-else class="button gold" :disabled="studio.state.loading" @click="showSyncConfirm = true">SYNC DESTINATION</button><button class="button quiet" :disabled="studio.state.loading" @click="showOneTime = true">ONE-TIME UPDATE</button></div></section>
          <section class="panel selection"><div class="section-title"><span>STAGED TRACK SEQUENCE</span><span class="counter">{{ selected?.orderedIds.length || 0 }}</span></div><p class="hint">Preview IDs are authoritative until you edit this local sequence. Drag rows or use the keyboard controls; changes remain staged.</p><div class="track-table"><div class="track-head"><span>#</span><span>TRACK / ARTIST</span><span>ALBUM</span><span>CONTROLS</span></div><div v-for="(id, index) in selected?.orderedIds" :key="`${id}-${index}`" class="track-row" draggable="true" @dragstart="draggedIndex = index" @dragover.prevent @drop="dropTrack(index)"><span class="track-number">{{ String(index + 1).padStart(2, '0') }}</span><span class="track-name"><strong>{{ songFor(id)?.name || 'Enrichment pending' }}</strong><small>{{ songFor(id)?.artists.map(artist => artist.name).filter(Boolean).join(', ') || id }}</small></span><span class="album">{{ songFor(id)?.album.name || '—' }}</span><span class="track-controls"><button class="icon-button" :aria-label="`Move track ${index + 1} up`" @click="studio.moveTrack(index, -1)" @keydown="keyMove(index, $event)">↑</button><button class="icon-button" :aria-label="`Move track ${index + 1} down`" @click="studio.moveTrack(index, 1)" @keydown="keyMove(index, $event)">↓</button><button class="icon-button danger" :aria-label="`Remove track ${index + 1}`" @click="studio.removeTrack(index)">×</button></span></div><div v-if="!selected?.orderedIds.length" class="empty-table">No tracks in this preview.</div></div></section>
        </section>
      </section>
    </template>

    <div v-if="showCreate" class="dialog-backdrop" role="presentation"><form class="dialog panel" role="dialog" aria-modal="true" aria-labelledby="create-title" @submit.prevent="createDestination"><p class="eyebrow">NEW MANAGED DESTINATION</p><h2 id="create-title">Create Butler playlist?</h2><p>This creates a new Spotify playlist and binds it to the selected built-in definition.</p><label for="destination-name">Playlist name<input id="destination-name" v-model="destinationName" placeholder="Butler playlist" /></label><label for="destination-description">Description<textarea id="destination-description" v-model="destinationDescription" rows="3" /></label><div class="dialog-actions"><button type="button" class="button quiet" @click="showCreate = false">CANCEL</button><button type="submit" class="button gold">CREATE</button></div></form></div>
    <div v-if="showSyncConfirm" class="dialog-backdrop" role="presentation"><div class="dialog panel" role="dialog" aria-modal="true" aria-labelledby="sync-title"><p class="eyebrow">RECURRING SYNC</p><h2 id="sync-title">Replace the managed playlist?</h2><p>This sends the exact staged order to {{ selectedDefinition?.destination?.spotifyPlaylistId }}. The current destination snapshot will be checked.</p><div class="dialog-actions"><button type="button" class="button quiet" @click="showSyncConfirm = false">CANCEL</button><button type="button" class="button gold" @click="sync">CONFIRM SYNC</button></div></div></div>
    <div v-if="showOneTime" class="dialog-backdrop" role="presentation"><form class="dialog panel" role="dialog" aria-modal="true" aria-labelledby="one-time-title" @submit.prevent="oneTime"><p class="eyebrow">UNTRACKED UPDATE</p><h2 id="one-time-title">Update another playlist</h2><p>Provide a Spotify playlist ID. This update returns <code>tracked=false</code> and does not change Butler mappings.</p><label for="one-time-playlist-id">Spotify playlist ID<input id="one-time-playlist-id" v-model="oneTimePlaylistId" required placeholder="37i..." /></label><div class="dialog-actions"><button type="button" class="button quiet" @click="showOneTime = false">CANCEL</button><button type="submit" class="button gold">UPDATE ONCE</button></div></form></div>
  </main>
</template>
