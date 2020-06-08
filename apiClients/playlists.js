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
        console.log(`Found these playlists ${playlists.map(x => " " + x.name)}`)
    }
};


const getListOfPlaylists = async function(accessToken) {
    return spotifyRequests.getAllResults('/v1/me/playlists', accessToken)
}