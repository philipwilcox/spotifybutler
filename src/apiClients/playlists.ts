import spotifyRequests from '../../spotifyRequests.js'
import constants from '../constants.js'
import Utils from '../../utils.js'

const DO_SHUFFLE = true

/**
 Playlist saving design:
 - get users playlists and find ID of one with matching name
 - if not found, create a new one with that name
 - compare items vs desired items
 - remove no longer desired items
 - add new items

 async function savePlaylistByName(name, tracks)

 function removeTracksFromPlaylist(playlist, tracks) : Promise
 function addTracksToPlaylist(playlist, tracks) : Promise
 */
export default {
    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     */
    savePlaylistByName: async function (playlistName, trackList, accessToken) {
        const playlists = await getListOfPlaylists(accessToken) // TODO: could hoist this up for perf reasons
        const playlistWithName = await getOrCreatePlaylistByNameWithTracks(playlists, playlistName, accessToken)
        const playlistDifferences = getPlaylistDifferences(playlistName, playlistWithName.tracks, trackList)
        console.log(`\n\nFor playlist ${playlistWithName.name} (${playlistWithName.id}) I will add ${playlistDifferences.added.length} tracks and will remove ${playlistDifferences.removed.length}`)
        if (DO_SHUFFLE) {
            await removeTracksFromPlaylist(playlistWithName.id, playlistWithName.tracks, accessToken)
            console.log(`   Cleared out playlist ${playlistName} entirely to re-add in shuffled order`)
            await addTracksToPlaylist(playlistWithName.id, Utils.shuffle(trackList), accessToken)
        } else {
            if (playlistDifferences.added.length > 0) {
                await addTracksToPlaylist(playlistWithName.id, playlistDifferences.added, accessToken)
            }
            if (playlistDifferences.removed.length > 0) {
                await removeTracksFromPlaylist(playlistWithName.id, playlistDifferences.removed, accessToken)
            }
        }
        console.log(`   Added to ${playlistName}: ${playlistDifferences.added.map(x => x.track.name)}`)
        console.log(`   Removed from ${playlistName}: ${playlistDifferences.removed.map(x => x.track.name)}`)
        return playlistDifferences
    },

    getPlaylistAndTracksByName: async function (playlistName, accessToken) {
        const playlists = await getListOfPlaylists(accessToken) // TODO: could hoist this up for perf reasons
        return getPlaylistAndTracksByNameInternal(playlists, playlistName, accessToken);
    }
};

const getListOfPlaylists = async function (accessToken) {
    return spotifyRequests.getAllResults('/v1/me/playlists', accessToken)
}

const getPlaylistAndTracksByNameInternal = async function (playlists, playlistName, accessToken) {
    const existingPlaylist = playlists.find(x => x.name === playlistName)
    if (existingPlaylist) {
        // hydrate tracks data - only needed if existing playlist; new playlist we can assume is empty
        const tracksUrl = new URL(existingPlaylist.tracks.href)
        if (tracksUrl.host !== constants.SPOTIFY_API_HOSTNAME) {
            throw `Expected ${constants.SPOTIFY_API_HOSTNAME} for host for hydration URL, got ${tracksUrl.host} from ${existingPlaylist.tracks.href}`
        }
        const tracks = await spotifyRequests.getAllResults(tracksUrl.pathname, accessToken)
        existingPlaylist.tracks = tracks
        return existingPlaylist
    } else {
        return {placeholder: true, tracks: []};
    }
}

const getOrCreatePlaylistByNameWithTracks = async function (playlists, playlistName, accessToken) {
    const existingPlaylist = playlists.find(x => x.name === playlistName)
    if (existingPlaylist) {
        return getPlaylistAndTracksByNameInternal(playlists, playlistName, accessToken)
    } else {
        const newPlaylist = await createPlaylistWithName(playlistName, accessToken)
        // hydrate as empty of tracks since we just created it
        newPlaylist.tracks = []
        return newPlaylist
    }
}

const createPlaylistWithName = async function (playlistName, accessToken) {
    const userId = await spotifyRequests.getUserId(accessToken)
    const endpoint = `/v1/users/${userId}/playlists`
    const data = {
        name: playlistName,
        public: false,
        collaborative: false,
        description: "Automatically generated playlist from Spotify Butler app"
    }
    return spotifyRequests.postData(endpoint, data, accessToken)
}

/**
 * Return an object like {removed: [tracks], added: [tracks]} that result from comparing the tracks already in the
 * playlist to the given list of desired tracks.
 */
const getPlaylistDifferences = function (playlistName, playlistTrackList, desiredTracklist) {
    const playlistTrackUris = new Set(playlistTrackList.map(x => x.track.uri))
    const desiredTrackUris = new Set(desiredTracklist.map(x => x.track.uri))
    // TODO: how to reconcile multiple entries in spotify for the same song with different URIs
    const removedTracks = playlistTrackList.filter(x => !desiredTrackUris.has(x.track.uri))
    const addedTracks = desiredTracklist.filter(x => !playlistTrackUris.has(x.track.uri))
    return {
        name: playlistName,
        removed: removedTracks,
        added: addedTracks,
        newLength: desiredTracklist.length
    }
}

const addTracksToPlaylist = async function (playlistId, trackList, accessToken) {
    // NOTE: can't do more than 100 items at a time
    const endpoint = `/v1/playlists/${playlistId}/tracks`
    const chunkedTrackList = chunkedList(trackList, 100)
    for (const chunk of chunkedTrackList) {
        const data = {
            uris: chunk.map(x => x.track.uri)
        }
        await spotifyRequests.postData(endpoint, data, accessToken)
    }
}

const removeTracksFromPlaylist = async function (playlistId, trackList, accessToken) {
    // NOTE: can't do more than 100 items at a time
    const endpoint = `/v1/playlists/${playlistId}/tracks`
    const chunkedTrackList = chunkedList(trackList, 100)
    for (const chunk of chunkedTrackList) {
        // TODO: this doesn't work yet
        const data = {
            tracks: chunk.map(x => ({
                uri: x.track.uri
            }))
        }
        await spotifyRequests.deleteData(endpoint, data, accessToken)
    }
}

const chunkedList = function (list, chunkSize) {
    let start = 0
    let listOfLists = []
    while (start < list.length) {
        const sublist = list.slice(start, start + 100)
        listOfLists.push(sublist)
        start += 100
    }
    return listOfLists
}