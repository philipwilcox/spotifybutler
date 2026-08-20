<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ButlerApiClient } from './api'
import { LibraryController, SessionController, StudioController } from './controllers'
import { operationProgressLabel, operationProgressPercent, OperationProgressController } from './operation-progress'

const api = new ButlerApiClient(undefined, () => session.state.session = null)
const session = new SessionController(api)
const progress = new OperationProgressController()
const library = new LibraryController(api, progress)
const studio = new StudioController(api, undefined, undefined, progress)
const selectedPublishCandidateId = ref<string | null>(null)
const draggedIndex = ref<number | null>(null)

const selected = computed(() => studio.state.selection)
const selectedDefinition = computed(() => studio.state.definition)
const selectedLibraryPlaylist = computed(() => studio.state.libraryPlaylist)
const trackedStatus = computed(() => progress.state.active)
const isBusy = computed(() => session.state.loading || library.state.loading || studio.state.loading || (trackedStatus.value !== null && trackedStatus.value.phase !== 'succeeded' && trackedStatus.value.phase !== 'failed'))
const progressPercent = computed(() => operationProgressPercent(trackedStatus.value))
const progressLabel = computed(() => operationProgressLabel(trackedStatus.value))
const songFor = (id: string) => selected.value.enrichment[id]
const headerArtSong = computed(() => selected.value.orderedIds.map(songFor).find(song => song?.album.imageUrl) ?? null)
const headerArt = computed(() => headerArtSong.value?.album.imageUrl ?? null)
const spotifyPageUrl = (song: NonNullable<typeof headerArtSong.value>) =>
  `https://open.spotify.com/${song.album.id ? 'album' : 'track'}/${encodeURIComponent(song.album.id ?? song.id)}`
const formatTime = (value: string | null | undefined) => {
  if (!value) return 'never'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'invalid date' : date.toLocaleString()
}
const buildTimestamp = __BUTLER_BUILD_TIMESTAMP__
const buildDisplayTimestamp = (() => {
  const date = new Date(buildTimestamp)
  return Number.isNaN(date.getTime()) ? buildTimestamp : date.toLocaleString()
})()

async function boot() {
  const currentSession = await session.load()
  if (!currentSession) return
  const loadedLibrary = await library.load()
  const first = loadedLibrary?.definitions[0]
  if (first) await studio.load(first)
}
async function refreshSource(sourceKey?: string) { sourceKey ? await library.refreshSources([sourceKey]) : await library.refreshAll() }
async function choose(definition: (typeof library.state.definitions)[number]) { await studio.selectDefinition(definition) }
async function planPublish() {
  const plan = await studio.planPublish()
  selectedPublishCandidateId.value = plan?.candidates.length === 1 ? plan.candidates[0].spotifyPlaylistId : null
}
async function publish() {
  const plan = studio.state.publishPlan
  if (!plan || (plan.action !== 'create' && plan.action !== 'adopt' && plan.action !== 'choose')) return
  const candidateId = plan.action === 'create' ? undefined : selectedPublishCandidateId.value || undefined
  if (plan.action !== 'create' && !candidateId) return
  if (await studio.publish(plan.action === 'create' ? 'create' : 'adopt', candidateId)) {
    selectedPublishCandidateId.value = null
    await library.load()
  }
}
async function sync() { await studio.sync() }
async function updateShuffleAfterGeneration(event: Event) { await studio.updateShuffleAfterGeneration((event.currentTarget as HTMLInputElement).checked) }
function keyMove(index: number, event: KeyboardEvent) { if (event.key === 'ArrowUp') { event.preventDefault(); studio.moveTrack(index, -1) } if (event.key === 'ArrowDown') { event.preventDefault(); studio.moveTrack(index, 1) } }
function dropTrack(index: number) { if (draggedIndex.value !== null) studio.moveTrackTo(draggedIndex.value, index); draggedIndex.value = null }
onMounted(boot)
watch(() => session.state.session, sessionValue => { if (!sessionValue) progress.dispose() })
onUnmounted(() => progress.dispose())
</script>

<template>
  <main class="shell">
    <div v-if="isBusy" class="top-progress" role="progressbar" aria-label="Waiting for Spotify Butler" :aria-valuemin="progressPercent === null ? undefined : 0" :aria-valuemax="progressPercent === null ? undefined : 100" :aria-valuenow="progressPercent ?? undefined" :aria-valuetext="progressPercent === null ? 'Working' : `${progressPercent}% complete`">
      <span class="progress-label">{{ progressLabel }}</span><span class="progress-track"><span class="progress-bar" :class="{ determinate: progressPercent !== null }" :style="progressPercent === null ? undefined : { width: `${progressPercent}%` }" /></span>
    </div>
    <div v-if="progress.state.connectionError" class="alert error" role="alert">{{ progress.state.connectionError }}</div>
    <header class="hud panel">
      <h1>BUTLER // PLAYLIST STUDIO</h1>
      <div class="hud-identity">
        <span class="build-stamp" :title="`Frontend bundle built at ${buildTimestamp} UTC`">BUILD {{ buildDisplayTimestamp }}</span>
        <div class="hud-status" aria-live="polite"><span class="status-light" :class="session.state.session ? 'online' : 'offline'" />{{ session.state.session ? `OPERATOR ${session.state.session.userId}` : 'AUTHENTICATION REQUIRED' }} <button v-if="session.state.session" class="button quiet" @click="session.signOut">SIGN OUT</button><button v-else class="button gold" @click="session.startOAuth">CONNECT SPOTIFY</button></div>
      </div>
    </header>

    <section v-if="!session.state.session" class="empty panel"><p class="eyebrow">MISSION LOCKED</p><h2>Connect Spotify to open the studio.</h2><p>Your credentials and tokens stay on the Butler server. The browser receives only the scoped session.</p><button class="button gold" @click="session.startOAuth">BEGIN AUTHORIZATION</button><p v-if="session.state.error" class="error">{{ session.state.error }}</p></section>

    <template v-else>
      <div v-if="session.state.error" class="alert error" role="alert">{{ session.state.error }}</div>
      <section class="layout">
        <aside class="panel sidebar">
          <div class="section-title"><span>DEFINED PLAYLISTS</span><span class="section-title-separator">//</span><span class="counter">{{ library.state.definitions.length }}</span></div>
          <button v-for="definition in library.state.definitions" :key="definition.definitionId" class="mission definition-mission" :class="{ active: definition.definitionId === selectedDefinition?.definitionId }" @click="choose(definition)"><span class="mission-mark">◆</span><span><strong>{{ definition.name }}</strong><small>{{ definition.kind }} · {{ definition.definitionId }}</small></span></button>
          <p v-if="library.state.loading && !library.state.definitions.length" class="hint">Loading library…</p>
          <p v-else-if="!library.state.loading && !library.state.definitions.length" class="hint">No definitions are available.</p>
          <div class="divider" />
          <div class="section-title"><span>LIBRARY PLAYLISTS</span><span class="counter">{{ library.state.library?.playlists.length || 0 }}</span></div>
          <button v-for="playlist in library.state.library?.playlists" :key="playlist.spotifyPlaylistId" class="mission" :class="{ active: playlist.spotifyPlaylistId === selectedLibraryPlaylist?.spotifyPlaylistId }" @click="studio.selectLibraryPlaylist(playlist)"><span class="mission-mark">♫</span><img v-if="playlist.spotifyPlaylistId === selectedLibraryPlaylist?.spotifyPlaylistId && headerArt" class="art-frame art-rail" :src="headerArt" alt="Selected playlist album art" /><span><strong>{{ playlist.name }}</strong><small>{{ playlist.cachedPlayableTrackCount }}/{{ playlist.declaredItemCount ?? '—' }} tracks · {{ playlist.contentStatus }}</small></span></button>
          <div class="divider" />
          <div class="section-title"><span>LIBRARY SOURCES</span><button class="icon-button" aria-label="Refresh all sources" @click="refreshSource()">↻</button></div>
          <p v-if="library.state.error" class="error library-error" role="alert">LIBRARY LOAD FAILED: {{ library.state.error }}</p>
          <div v-for="source in library.state.library?.sources" :key="source.sourceKey" class="source-row"><span class="status-light" :class="source.status" /><span class="source-copy"><strong>{{ source.sourceKey }}</strong><small>{{ source.itemCount ?? '—' }} items · {{ formatTime(source.lastSyncedAt) }}</small></span><button v-if="source.canRefresh" class="icon-button" :aria-label="`Refresh ${source.sourceKey}`" @click="refreshSource(source.sourceKey)">↻</button></div>
        </aside>

        <section class="workspace">
          <div v-if="studio.state.error" class="alert error" role="alert">{{ studio.state.error }} <button class="icon-button" aria-label="Dismiss error" @click="studio.state.error = null">×</button></div>
          <div v-if="studio.state.conflict" class="alert conflict" role="alert"><strong>DESTINATION SNAPSHOT CONFLICT</strong><span>The remote playlist changed. Your staged order is preserved; review it and synchronize again when ready.</span></div>
          <section class="panel playlist-info">
            <div class="section-title"><span>{{ studio.state.activeKind === 'library_playlist' ? 'LIBRARY PLAYLIST' : 'ACTIVE DEFINITION' }}</span><span class="section-title-separator">//</span><span class="section-title-detail">{{ selectedDefinition?.definitionId || selectedLibraryPlaylist?.spotifyPlaylistId }}</span></div>
            <div class="playlist-info-top">
              <a v-if="headerArt && headerArtSong" class="art-link" :href="spotifyPageUrl(headerArtSong)" target="_blank" rel="noreferrer" aria-label="Open album on Spotify"><img class="art-frame art-header" :src="headerArt" alt="Selected album artwork" /><span class="art-label">SPOTIFY ART</span></a>
              <div class="playlist-heading"><h2>{{ selectedDefinition?.name || selectedLibraryPlaylist?.name || 'Select a mission' }}</h2><p>{{ selectedDefinition?.description || selectedLibraryPlaylist?.description || 'Choose a playlist or definition from the sidebar.' }}</p></div>
              <div class="playlist-info-rail">
                <div class="mission-meta"><span>{{ selected?.orderedIds.length }} TRACKS</span><span v-if="selected?.dirty" class="dirty">● UNSAVED ORDER</span></div>
              </div>
            </div>
            <div v-if="studio.state.activeKind === 'definition'" class="playlist-info-lower">
              <div class="playlist-info-details">
                <div class="definition-details">
                  <div><span class="label">SEED</span><code class="seed-value" :title="studio.state.preview?.seed || '—'">{{ studio.state.preview?.seed || '—' }}</code></div>
                  <div><span class="label">RECIPE REVISION</span><code>{{ studio.state.preview?.recipeRevision?.slice(0, 16) || '—' }}</code></div>
                  <div><span class="label">GENERATED</span><span>{{ formatTime(studio.state.preview?.generatedAt) }}</span></div>
                </div>
                <div class="destination-summary">
                  <span class="label">DESTINATION</span>
                  <h3>{{ selectedDefinition?.destination ? selectedDefinition.destination.spotifyPlaylistId : 'NO BUTLER DESTINATION' }}</h3>
                  <p v-if="selectedDefinition?.destination">Last sync: {{ formatTime(selectedDefinition.destination.lastSyncedAt) }} · snapshot {{ selectedDefinition.destination.lastSeenSnapshotId || 'unseen' }}</p>
                  <p v-else>Publish the staged order to create or adopt a managed Spotify playlist.</p>
                </div>
              </div>
              <div class="playlist-info-action-panel">
                <label class="shuffle-setting"><input type="checkbox" :checked="selectedDefinition?.recipe.shuffleAfterGeneration ?? false" :disabled="studio.state.loading" @change="updateShuffleAfterGeneration" /><span>SHUFFLE AFTER GENERATION</span></label>
                <div class="action-group playlist-info-actions">
                  <button class="button quiet" :disabled="studio.state.loading" @click="studio.reroll">REROLL PREVIEW</button>
                  <button v-if="!selectedDefinition?.destination" class="button gold" :disabled="studio.state.loading" @click="planPublish">PUBLISH</button>
                  <button v-else class="button gold" :disabled="studio.state.loading" @click="sync">SYNC DESTINATION</button>
                </div>
              </div>
            </div>
          </section>
          <section class="panel selection"><div class="section-title"><span>STAGED TRACK SEQUENCE</span><span class="section-title-separator">//</span><span class="counter">{{ selected?.orderedIds.length || 0 }}</span></div><div class="selection-toolbar"><p class="hint">Drag rows or use the keyboard controls; changes remain staged.</p><button v-if="studio.state.activeKind === 'definition'" class="button quiet" :disabled="studio.state.loading || (selected?.orderedIds.length || 0) < 2" aria-label="Shuffle staged track sequence" @click="studio.shuffleTrackOrder">SHUFFLE</button></div><div class="track-table"><div class="track-head"><span>#</span><span class="track-heading-name">TRACK / ARTIST</span><span>ALBUM</span><span>CONTROLS</span></div><div v-for="(id, index) in selected?.orderedIds" :key="`${id}-${index}`" class="track-row" draggable="true" @dragstart="draggedIndex = index" @dragover.prevent @drop="dropTrack(index)"><span class="track-number">{{ String(index + 1).padStart(2, '0') }}</span><a v-if="songFor(id)?.album.imageUrl && songFor(id)" class="art-link art-track-link" :href="spotifyPageUrl(songFor(id)!)" target="_blank" rel="noreferrer" :aria-label="`Open ${songFor(id)?.album.name || 'album'} on Spotify`"><img class="art-frame art-track" :src="songFor(id)?.album.imageUrl" alt="Album artwork" /></a><span class="track-name"><strong><span v-if="songFor(id)">{{ songFor(id)?.name }}</span><span v-else class="enrichment-pending" role="status" aria-label="Track ID enrichment pending" /></strong><small>{{ songFor(id)?.artists.map(artist => artist.name).filter(Boolean).join(', ') || id }}</small></span><span class="album">{{ songFor(id)?.album.name || '—' }}</span><span class="track-controls"><button class="icon-button" :aria-label="`Move track ${index + 1} up`" @click="studio.moveTrack(index, -1)" @keydown="keyMove(index, $event)">↑</button><button class="icon-button" :aria-label="`Move track ${index + 1} down`" @click="studio.moveTrack(index, 1)" @keydown="keyMove(index, $event)">↓</button><button class="icon-button danger" :aria-label="`Remove track ${index + 1}`" @click="studio.removeTrack(index)">×</button></span></div><div v-if="!selected?.orderedIds.length" class="empty-table">No tracks in this preview.</div></div></section>
        </section>
      </section>
    </template>

    <div v-if="studio.state.publishPlan" class="dialog-backdrop" role="presentation"><form class="dialog panel" role="dialog" aria-modal="true" aria-labelledby="publish-title" @submit.prevent="publish"><p class="eyebrow">PUBLISH DESTINATION</p><h2 id="publish-title">{{ studio.state.publishPlan.action === 'create' ? 'Create a new playlist?' : studio.state.publishPlan.action === 'blocked' ? 'Playlist cannot be adopted' : studio.state.publishPlan.action === 'choose' ? 'Choose a playlist to adopt' : 'Adopt this playlist?' }}</h2><p v-if="studio.state.publishPlan.action === 'create'">This creates and publishes <strong>{{ studio.state.publishPlan.playlistName }}</strong> as a managed Spotify playlist.</p><p v-else-if="studio.state.publishPlan.action === 'blocked'">{{ studio.state.publishPlan.message }}</p><p v-else-if="studio.state.publishPlan.action === 'choose'">Multiple owned playlists match <strong>{{ studio.state.publishPlan.playlistName }}</strong>. Select the destination to adopt.</p><p v-else>Adopt <strong>{{ studio.state.publishPlan.playlistName }}</strong> as the managed destination and publish the staged order.</p><div v-if="studio.state.publishPlan.action === 'choose'" class="candidate-list"><label v-for="candidate in studio.state.publishPlan.candidates" :key="candidate.spotifyPlaylistId"><input type="radio" name="publish-candidate" :value="candidate.spotifyPlaylistId" v-model="selectedPublishCandidateId" /><span><strong>{{ candidate.name }}</strong><small>{{ candidate.description || 'No description' }} · {{ candidate.itemCount ?? '—' }} tracks</small></span></label></div><div class="dialog-actions"><button type="button" class="button quiet" @click="studio.state.publishPlan = null; selectedPublishCandidateId = null">CANCEL</button><button v-if="studio.state.publishPlan.action !== 'blocked'" type="submit" class="button gold" :disabled="studio.state.publishPlan.action !== 'create' && !selectedPublishCandidateId">PUBLISH</button></div></form></div>
  </main>
</template>
