# Team Map Sync

Necesse 1.3.3 mod. Players on the same team share map exploration, and markers ticked "Share with team".

Every player must have the mod (it registers packets, so it is not `clientside`).

## How vanilla handles the map

- The discovered map exists only on each client (`%APPDATA%/Necesse/saves/maps/<worldID>/<level>/XxY.mapdata`); the server never sees it.
- A client can only colour tiles of regions it has loaded, so it cannot compute a teammate's map; colours have to be transferred.
- The only vanilla sharing is the cartographer's table: manual, both players at the table, dimensions only, no markers.
- Markers are a client-only list in `icons.mapdata`, edited in place with no change events.

## What this mod does

| Part | Mechanism |
|---|---|
| Recording | Patch on `ClientDiscoveredMapRegion.setRGBA`, active only while the real map runs `tickDiscovery` / `tickUpdate`. Each changed tile is one append to a primitive buffer. Flushed every 2 s. |
| Live relay | Client encodes changed tiles per map region and sends them; server forwards the compressed bytes untouched to online teammates. Live data **overwrites** (it is the newest observation, so terrain changes propagate). |
| Offline teammates | Server merges everything into a per-team store (`<datadir>/teammapsync/<worldID>/team<N>/`). On join it streams the team map as **fill** data (only fills undiscovered tiles). |
| Backlog | On join (or team change) a client uploads local map files changed since its last upload for that team, so exploration from before installing the mod or joining the team is shared too. |
| Markers | The edit form gets a "Share with team" checkbox. Shared markers live in the vanilla list (drawn and edited normally) but are owned by the server, kept out of `icons.mapdata`, and identified by UUID. Edits are picked up by polling with a short debounce. |
| Team changes | Server checks each player's team once a second; a change resets the session (old team markers removed, new team map streamed). |

## Performance design

The guiding constraint is no effect on server TPS.

- **Server thread** only forwards already-compressed bytes, updates counters and drains send queues. Decoding, merging, encoding and all file IO run on one low-priority worker thread.
- **Bandwidth caps**: per player upload 96 KB/s (burst 384 KB), backlog send 6 KB/tick per player and 48 KB/tick per server.
- **Client game thread** writes received tiles into the open map at most 4096 tiles per tick; compression and file merges run on a worker thread.

### Data format

Each map region's tiles are sent as a dense grid over their bounding box (RGB, row by row) compressed with Deflate level 6, behind a one-byte codec id. Measured on real map regions:

| Layout + compressor | Full regions (6 real) | Radius-40 discovery | One-step delta | Encode, one full region |
|---|---|---|---|---|
| Sparse planar + Deflate 6 | 118 KB | 5.1 KB | 213 B | 8.6 ms |
| **Dense grid + Deflate 6 (used)** | **64 KB** | **2.7 KB** | 214 B | **4.3 ms** |
| Dense grid + Deflate 9 | 60 KB | 2.6 KB | 202 B | 45 ms |
| Dense grid + zstd 3 | 73 KB | 3.1 KB | 213 B | ~0.5 ms |
| Dense grid + zstd 19 | 53 KB | 2.4 KB | 204 B | 58 ms |

zstd is not used. A follow-up sweep (same data, warmed up) showed the gap at levels 3-4 comes from their fast single-probe match finder, not from a missing dictionary: a trained 16 KB dictionary saved under 2%, and `minMatch=3` changed nothing. From level 5 (hash-chain search) zstd matches Deflate's size:

| Codec | Full regions total | Encode / region | Decode / region |
|---|---|---|---|
| Deflate 6 | 66.3 KB | 1.7 ms | - |
| zstd 3 / 4 | 75.2 / 73.3 KB | 0.17 / 0.44 ms | 0.06 ms |
| zstd 5 | 66.5 KB | 0.48 ms | 0.06 ms |
| zstd 9 | 65.4 KB | 1.1 ms | 0.04 ms |
| zstd 12 | 62.4 KB | 4.9 ms | 0.03 ms |

Brotli (brotli4j 1.23.0, native, window 18) on the same grids, with Deflate and zstd from the same run for comparison (disc = a radius-40 first discovery, step = one tile of movement):

| Codec | Full regions total | Disc avg | Step avg | Encode / region | Decode / region |
|---|---|---|---|---|---|
| Deflate 6 | 66.3 KB | 1492 B | 106 B | 1.7 ms | 0.11 ms |
| zstd 5 | 66.5 KB | 1588 B | 111 B | 0.44 ms | 0.04 ms |
| Brotli q4 | 76.2 KB | 1740 B | 109 B | 0.56 ms | 0.10 ms |
| Brotli q5 | 66.7 KB | 1553 B | 104 B | 0.97 ms | 0.09 ms |
| Brotli q6 | 65.2 KB | 1531 B | 100 B | 1.2 ms | 0.08 ms |
| Brotli q9 | 62.2 KB | 1493 B | 100 B | 3.5 ms | 0.07 ms |
| Brotli q11 | 56.5 KB | 1375 B | 108 B | 64 ms | 0.08 ms |

Brotli behaves like zstd here: the fast qualities lose, the middle ones tie with Deflate, and only the slow ones win. Its built-in text dictionary does nothing for RGB grids.

The CPU saving of either would land on a worker thread (full regions are only encoded on store flushes and backlog uploads), while zstd-jni adds 6.6 MB of native libraries that may not load on every server platform. The codec id allows switching later.

## Known limits

- Players without a team (team ID -1) do not share anything.
- Tiles for a level the player has never visited this session wait in memory (up to about 1M tiles) until the level size is known; beyond that they are dropped and resent on the next join.
- Pure black map tiles are treated as undiscovered, as in vanilla's own files.
- A team is capped at 500 shared markers.
- The `deletemapdata` client command wipes shared markers locally only; they return on the next join.
- Patches bind to exact method signatures of 1.3.3; re-check after game updates.

## Build

```
./gradlew buildModJar
```

Output: `build/jar/TeamMapSync-1.3.3-1.0.jar`.
