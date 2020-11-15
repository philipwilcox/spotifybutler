const Utils = require('./utils')

module.exports = {

    /**
     * This returns a map of decade strings to lists of tracks, from an original flat track list.
     */
    groupTracksByDecade: function (savedTrackList) {
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
        const tracksByDecade = savedTrackList.reduce(reducer, new Map())
        return tracksByDecade;
    },

    /**
     * Returns the tracks from the given list, filtering out any tracks from the second list.
     *
     * TODO: tracksToRemove is of a different shape than saved tracks, or playlist tracks, which included "added_at"
     * metadata in a wrapper. Eventually it would be good to reconcile all this
     */
    trackListWithoutOtherList: function (savedTrackList, tracksToRemove) {
        const trackUrisToRemove = new Set(tracksToRemove.map(x => x.uri))
        // TODO: how to reconcile multiple entries in spotify for the same song with different URIs
        return savedTrackList.filter(x=> !trackUrisToRemove.has(x.track.uri))
    },

    /**
     * Returns a list of tracks from the original track list that ARE NOT from the artists provided.
     *
     * The artistLimit param controls how many of the top artists to filter out.
     */
    trackListNotByArtists: function (savedTrackList, artistList, artistLimit = 50) {
        // the saved tracks have a `artists` array with limited info but each does include `uri` as well as `name` and `type` and `id`
        const artistUrisToRemove = new Set(artistList.slice(0, artistLimit).map(x => x.uri))
        return savedTrackList.filter(x => !Utils.setContainsAnyOf(new Set(x.track.artists.map(a => a.uri)), artistUrisToRemove))
    },

    /**
     * Returns a list of tracks from the original track list that ARE from the artists provided.
     *
     * The artistLimit param controls how many of the artist list to include.
     */
    trackListByArtists: function (savedTrackList, artistList, artistLimit = 50) {
        // the saved tracks have a `artists` array with limited info but each does include `uri` as well as `name` and `type` and `id`
        const artistUrisToKeep = new Set(artistList.slice(0, artistLimit).map(x => x.uri))
        return savedTrackList.filter(x => Utils.setContainsAnyOf(new Set(x.track.artists.map(a => a.uri)), artistUrisToKeep))
    }

};
