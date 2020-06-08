const spotifyRequests = require('../spotifyRequests')
const constants = require('../constants')

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
module.exports = {
    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     */
    savePlaylistByName: async function (playlistName, trackList, accessToken) {
        // TODO: implement the rest
        const playlists = await getListOfPlaylists(accessToken)
        const playlistWithName = await getOrCreatePlaylistByNameWithTracks(playlists, playlistName, accessToken)
        console.log(`Found these playlists ${playlists.map(x => " " + x.name)}`)
    }
};

const getListOfPlaylists = async function(accessToken) {
    return spotifyRequests.getAllResults('/v1/me/playlists', accessToken)
}

const getOrCreatePlaylistByNameWithTracks = async function(playlists, playlistName, accessToken) {
    const existingPlaylist = playlists.find(x => x.name === playlistName)
    if (existingPlaylist) {
        // hydrate tracks data - only needed if existing playlist; new playlist we can assume is empty
        const tracksUrl = new URL(existingPlaylist.tracks.href)
        if (tracksUrl.host !== constants.SPOTIFY_API_HOSTNAME) {
            throw `Expected ${constants.SPOTIFY_API_HOSTNAME} for host for hydration URL, got ${tracksUrl.host} from ${playlist.tracks.href}`
        }
        console.log("hydrating")
        const tracks = await spotifyRequests.getAllResults(tracksUrl.pathname, accessToken)
        existingPlaylist.tracks = tracks
        return existingPlaylist
    } else {
        const newPlaylist = await createPlaylistWithName(playlistName, accessToken)
        // hydrate as empty of tracks since we just created it
        newPlaylist.tracks = []
        return newPlaylist
    }
}

const createPlaylistWithName = async function(playlistName, accessToken) {
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
const getPlaylistDifferences = function(playlistTrackList, desiredTracklist) {
    
}