const spotifyRequests = require('../spotifyRequests')

module.exports = {
    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     */
    getMySavedTracks: async function (accessToken) {
        return spotifyRequests.getAllResults('/v1/me/tracks', accessToken)
    },

    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/personalization/get-users-top-artists-and-tracks/
     * for tracks.
     */
    getMyTopTracks: async function (accessToken) {
        return spotifyRequests.getAllResults('/v1/me/top/tracks', accessToken)
    },

    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/personalization/get-users-top-artists-and-tracks/
     * for tracks.
     */
    getMyTopArtists: async function (accessToken) {
        return spotifyRequests.getAllResults('/v1/me/top/artists', accessToken)
    }
};
