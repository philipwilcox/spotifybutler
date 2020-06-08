const spotifyRequests = require('../spotifyRequests')

module.exports = {
    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     */
    getMySavedTracks: async function (accessToken) {
        return spotifyRequests.getAllResults('/v1/me/tracks', accessToken)
    }
};
