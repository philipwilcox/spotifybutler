import {Track} from "./spotify/track.js";

export default class NewPlaylistInfo {
    playlistName: string
    allTracks: Track[]
    playlistId: null | string
    oldTracks: null | Track[]
    addedTracks: null | Track[]
    removedTracks: null | Track[]
    snapshotId: null | string

    constructor(playlistName: string,
                allTracks: Track[],
                playlistId: null | string = null,
                oldTracks: null | Track[] = null,
                addedTracks: null | Track[] = null,
                removedTracks: null | Track[] = null,
                snapshotId: null | string = null
    ) {
        this.playlistName = playlistName
        this.playlistId = playlistId
        this.allTracks = allTracks
        this.oldTracks = oldTracks
        this.addedTracks = addedTracks
        this.removedTracks = removedTracks
        this.snapshotId = snapshotId
    }
}