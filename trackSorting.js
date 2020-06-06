module.exports = {
    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     */
    groupTracksByDecade: function (trackList) {
        const reducer = (accumulator, item) => {
            const trackDecade = Math.floor(item.track.album.release_date.split('-')[0] / 10)*10
            if (accumulator.has(trackDecade)) {
                const oldList = accumulator.get(trackDecade)
                oldList.push(item)
                return accumulator
            } else {
                accumulator.set(trackDecade, [item])
                return accumulator
            }
        }
        const tracksByDecade = trackList.reduce(reducer, new Map())
        return tracksByDecade;
    }
};