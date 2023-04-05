# spotifybutler

This is something to create dynamic playlists to do things like "here are songs you like that
aren't in your top songs" or "here are songs you liked that aren't in the last 50 you played."

To set up you need to add a secrets.ts file in the `src` directory that exports your client ID, etc.

I'd love to do fancier queries than those two, like iTunes smart playlists, but the Spotify API doesn't support getting
much more than just that. I wish it would give me "last play time" and "total play count" for a user/track, but don't see
that in their API, and the feature requests are old and stale.

Currently developed against node v16.14.0 LTS.
