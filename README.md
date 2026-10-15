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
| Live relay | Client encodes changed tiles per map region band (16 rows) and sends them; server forwards the compressed bytes untouched to online teammates. Live data **overwrites** (it is the newest observation, so terrain changes propagate); live data travels in an ordered stream, so a late or resent update never undoes a newer one. |
| Offline teammates | Server merges everything into a per-team store (`<datadir>/teammapsync/<worldID>/v2/<teamKey>/`). On join it streams the team map as **fill** data (only fills undiscovered tiles), a few regions at a time. |
| Backlog | On join (or team change) a client uploads every local map file the server has not acknowledged for that team (tracked per file by modification time and size), so exploration from before installing the mod or joining the team is shared too. |
| Other levels | Tiles for a level the player is not on go to a client-side inbox (`teammapsync/client/<worldID>/inbox/`), never into vanilla's map files, which vanilla may load or save at any moment. The inbox is replayed into the open map when the player enters the level, without overwriting tiles that live data or the player changed since; an entry is removed only once fully applied, and tiles not yet applied when the game closes are saved to it. |
| Markers | The edit form gets a "Share with team" checkbox. Shared markers live in the vanilla list (drawn and edited normally) but are owned by the server, kept out of `icons.mapdata`, and identified by UUID. Changes are sent when the edit form closes (a cancelled new marker never reaches the team), one request in flight per marker; the server sends the result to every teammate in order. A result for a marker being edited is held back until the edit is over, then applied unless the player changed the marker. |
| Team identity | Vanilla renumbers teams on every load (the saved `teamID` is ignored), so the mod stores a UUID in each team's save record (`teamMapSyncID`) and keys all team data by it. |
| Team changes | Server checks each player's team every half second and on every received packet; a change starts a new session (old team markers removed, new team map streamed). |

## Network reliability

Vanilla's transport is unreliable (Steam "unreliable" messages or plain UDP). Packets are cut into 1 KB fragments, a packet missing one fragment is dropped, and nothing is resent; vanilla features recover by themselves (region requests are repeated after 2 s). This mod therefore runs its own delivery on top:

- **Handshake**: the client sends a heartbeat every second; the server starts a session on the first one and repeats the session announcement until a heartbeat names it. Every session has a new **epoch**, carried by every packet, so data from an earlier team or connection is dropped.
- **Acknowledged delivery** (`ReliableChannel`): tiles and markers carry a sequence number, are acknowledged in batches and resent with backoff (2 s, up to 16 s) until acknowledged. Receivers drop repeats (`SeqWindow`). Live tiles and markers also carry a gapless ordinal and are applied in sending order (`OrderedBuffer`); stored and backlog data only fills undiscovered tiles, so its order does not matter.
- **Small packets**: tiles travel in 16-row bands, about one fragment each, so a lost fragment costs one band, not a whole region.
- **Congestion**: bytes in flight are capped, so a slow or lossy link slows this traffic through late acknowledgements. Bulk data backs off further while the client's vanilla region loading has requests outstanding, leaving room for terrain.
- A local map file only counts as uploaded once every packet of it was acknowledged; the server stops acknowledging when a player exceeds its receive allowance, so excess uploads are delayed, not lost.

## Low traffic mode

Off by default. The server owner (or the host of a hosted game) turns it on in `cfg/mods/a632079.teammapsync.cfg` of the game's data folder (for a dedicated server, the folder given with `-datadir`). A hosting client creates the file with defaults; a dedicated server does not, so create it there:

```
{
	SETTINGS = {
		lowTraffic = true
	}
}
```

The setting is read when the server starts. With it on, players upload only *which* tiles their discovery changed (a bitmask), and the server colours them from its own level with vanilla's map colour rules minus lighting. It then merges them into the team's map and relays only the tiles whose colour changed. Exploration made before joining the team (the local map files) is still uploaded as colours.

Measured on a real world (443k surface tiles, 72k cave and dungeon tiles, same compression as normal mode):

| Data | Surface | Caves and dungeons |
|---|---|---|
| Colours as seen by the player (normal mode) | 173 KB | 74 KB |
| Discovery mask (low traffic upload) | 7 KB | 8 KB |
| Colours without lighting (low traffic download) | 165 KB | 29 KB |

Uploads shrink by 90-95%. Downloads shrink by about 60% in caves, where vanilla's lighting makes every tile's colour different, and barely change on the surface. Map colours there are already mostly a function of tile and object.

Trade-offs:

- Teammates see caves and indoor areas evenly lit, as if fully lit. Your own map keeps vanilla's look.
- On a dedicated server, trees and some liquids use their plain map colour (the exact ones need client textures).
- Only these tiles are coloured: within 96 tiles of where the player stood when the server accepted the mask, on the level the player was on, and in terrain the server still has loaded. Vanilla already sends a client the terrain 80 tiles around it, so a modified client learns nothing new this way. Tiles discovered just before changing level, or before a long delay on a very lossy link, can be lost.
- Only tiles whose colour differs from the team's stored map are relayed, so lit and unlit views of the same tile do not bounce between players. A player who comes back online still holding terrain that changed while they were away keeps the old look until they see that spot again. Stored data reaches a joining player as fill only.
- The server colours at most 64k tiles per player per second, and at most 16k per tick for all players together (about 1.5 ms). Masks are queued and coloured in slices across ticks. Pending work per player is capped, and over the cap new masks are not acknowledged, so the client sends them later. Masks that were already acknowledged are still coloured after their player leaves and before the server stops.
- Colour uploads from an outdated client only fill undiscovered tiles while the mode is on.

All players need the same mod version; the mode itself needs no client setting.

## Performance design

The guiding constraint is no effect on server TPS.

- **Server thread** only forwards already-compressed bytes, updates counters and drains send queues. Decoding, merging, encoding and all file IO run on one low-priority worker thread. In low traffic mode the server thread also reads tile colours from the level, which only it may touch, within the per-player tile cap above.
- **Bandwidth caps**: client upload about 40 KB/s; server send about 50 KB/s per player and 320 KB/s for the whole server, players served round robin; server accepts at most 96 KB/s per player (burst 192 KB). Overdrafts carry over to later ticks, so large packets cannot exceed the average.
- **Client game thread** writes received tiles into the open map at most 4096 tiles per tick; compression and file access run on a worker thread.
- **Memory**: stored maps are streamed to joining players and replayed from the inbox a few regions at a time, never loaded as a whole.

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
- Team data accepted by the server is written to disk within 30 s (markers within 5 s); a server crash in that window loses it from the store, although clients may already count it as uploaded.
- Local map regions that received teammates' tiles are saved by vanilla with a new timestamp and uploaded again (as fill data) on the next join.
- Ticking or unticking "Share with team" applies when the edit form closes, also when the form is cancelled.
- Pure black map tiles are treated as undiscovered, as in vanilla's own files.
- A team is capped at 500 shared markers.
- The `deletemapdata` client command wipes shared markers locally only (the mod detects the command); they return on the next join.
- Version 1.1 changed the protocol and the server store layout (`v2/`, keyed by team UUID): all players must update, and team maps are rebuilt from the players' uploads once.
- Patches bind to exact method signatures of 1.3.3; re-check after game updates.

## Build

```
./gradlew buildModJar
```

Output: `build/jar/TeamMapSync-1.3.3-1.1.jar`. Gradle 9 needs JDK 17 or newer (`JAVA_HOME`).

Run in dev mode with `./gradlew runClient` (`-dev -mod build/jar/`); a plain Steam launch does not pass `-mod`, so the dev mod is not listed. The Workshop upload button only appears when the jar contains `resources/preview.png`; regenerate it with `python tools/make_workshop_images.py .` (needs Pillow and Windows fonts).
