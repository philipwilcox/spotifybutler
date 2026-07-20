# Future Evolution

The notes here should be IGNORED for any current work, and only represent ideas for future directions to explore.

## API Updates

Make changes to be up to date with the 2026 changes to the Spotify API.

## Editable Playlists

Instead of deterministic playlist preview and generation, we want *fully client-editable* playlists, which suggests
moving towards a model where we return a list of song identifiers, and POST back the final ordered list after the user
makes any desired edits.

To minimize payload size, this suggests moving towards a ID-only playlist API and a separate song enrichment API that
can take either just one ID or a list of ID for returning the full info, that a client could cache instead of needing
to send back and forth every time.
