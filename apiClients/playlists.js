const spotifyRequests = require('../spotifyRequests')

/**
 Playlist saving design:
 - get users playlists and find ID of one with matching name
 - if not found, create a new one with that name
 - compare items vs desired items
 - remove no longer desired items
 - add new items

 async function savePlaylistByName(name, tracks)

 function getUserPlaylists(accessToken) : Promise
 function createPlaylist(accessToken, name) : Promise
 function getPlaylistDifferences(playlist, newTracks) : {removed: [tracks], added: [tracks]}
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
        const playlistWithName = await getOrCreatePlaylistByName(playlists, playlistName, accessToken)
        console.log(`Found these playlists ${playlists.map(x => " " + x.name)}`)
    }
};


const getListOfPlaylists = async function(accessToken) {
    return spotifyRequests.getAllResults('/v1/me/playlists', accessToken)
}

const getOrCreatePlaylistByName = async function(playlists, playlistName, accessToken) {
    const existingPlaylist = playlists.find(x => x.name === playlistName)
    if (existingPlaylist) {
        return existingPlaylist
    } else {
        return createPlaylistWithName(playlistName, accessToken)
    }
}

const createPlaylistWithName = async function(playlistName, accessToken) {
    const userId = await spotifyRequests.getUserId(accessToken)
    console.log(`user ID is ${userId}`)
    const endpoint = `/v1/users/${userId}/playlists`
    const data = {
        name: playlistName,
        public: false,
        collaborative: false,
        description: "Automatically generated playlist from Spotify Butler app"
    }
    return spotifyRequests.postData(endpoint, data, accessToken)
}