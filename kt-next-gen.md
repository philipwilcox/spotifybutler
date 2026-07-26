# Future Evolution

The notes here should be IGNORED for any current work, and only represent ideas for future directions to explore.

## Replace Docker Workflow

Remove the secrets file / mounted file fallback stuff in favor of env vars that we added? though how to use that for local mode.

## Admin Panel
Show who's used it! And possibly expand the "known users only restriction" after all if i ever deploy publicly.

## Album Art and Track Info Layout

Improve and add this

## Bulk Sync

Come up with a good flow around "republish all generated playlists" or multi-select of them to enable backend parallization of the sync.

## Editable Playlists

Let's make this able to edit non-generated playlists in the library too, and do things like shuffle their ordering with our shuffle logic.

## Styling

Revisit all UX elements. Make tab and angled button styles like my original proposal.

## Perf - Full Library Sync

Parallelize this. Also provide ways to only fetch liked songs, not update remote playlists. But also add "update just
this playlist" button for each playlist + source.

## Feedback

When we know how many paginated POSTs to make to sync playlists, expose this back to the frontend as progress! (websockets?)

## Recipe Builder UI

Let's figure out a slick way to do this

## LastFM Integration

Pull personal stats and richer genre information

## AI

Recipes from human language and button-guided stuff, like "start with four artists or songs, find others in my library
that are closest." At the song-level that would be per-song not per-artist, even.