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