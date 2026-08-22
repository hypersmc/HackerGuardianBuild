# Web replay 3D world contract

HackerGuardian already stores immutable chunk snapshots for replay sandbox playback in `hg_replay_world_chunks`. The web API now exposes those snapshots as a stable browser-facing format without exposing the private BLOB serialization.

## Routes

`GET /v1/replays/{id}` includes a `world_snapshot` manifest:

```json
{
  "available": true,
  "format": "hg-web-world-v1",
  "anchor_ms": 30000,
  "anchor_precision": "trigger_estimate",
  "chunk_count": 25,
  "size_bytes": 1234567,
  "chunks": [
    {"world":"world","chunk_x":4,"chunk_z":-2,"size_bytes":48231}
  ]
}
```

`GET /v1/replays/{id}/world` returns the same world-snapshot manifest with `replay_id`.

`GET /v1/replays/{id}/world/chunks/{chunkX}/{chunkZ}?world=world` decodes one stored chunk and returns `hg-web-world-v1`.

## `hg-web-world-v1`

```json
{
  "replay_id": 42,
  "format": "hg-web-world-v1",
  "world": "world",
  "chunk_x": 4,
  "chunk_z": -2,
  "anchor_ms": 30000,
  "min_y": -64,
  "max_y": 319,
  "order": "y-x-z",
  "sections": [
    {
      "base_y": 48,
      "height": 16,
      "palette": [
        "minecraft:stone",
        "minecraft:air",
        "minecraft:oak_planks[axis=y]"
      ],
      "runs": [[0,312],[1,54],[2,2]]
    }
  ]
}
```

Each section covers at most 16 vertical blocks. The logical uncompressed block order is:

```text
for y
  for x 0..15
    for z 0..15
```

Each `runs` entry is `[localPaletteIndex, count]`. Runs concatenate to exactly `height * 16 * 16` blocks. All-air sections are omitted.

Block state strings come from Bukkit `BlockData#getAsString()` and are kept namespaced/version-readable rather than exposing internal numeric Minecraft IDs.

## Rendering expectations

The web client should:

1. load the replay manifest and ordinary replay frame chunks;
2. load only world chunks relevant to the current camera/subject;
3. expand RLE sections into a local voxel representation;
4. omit air and generate faces only where a neighboring voxel is not opaque;
5. overlay recorded player snapshots and discrete replay events;
6. keep the existing 2D evidence/timeline UI available if world snapshots are missing.

The v1 world snapshot is an immutable visual anchor captured when the active replay session begins, normally around the trigger. Existing storage does not persist an exact capture timestamp per world chunk, so the API labels `anchor_precision` as `trigger_estimate` instead of pretending otherwise. The normal replay stream remains authoritative for player/event timing.

## Security and resource bounds

- Raw `hg_replay_world_chunks.data` is never returned.
- Decompression is bounded to 64 MiB per requested stored chunk.
- Palette size is bounded.
- All-air vertical sections are removed from the public representation.
- The API is read-only and uses the same HMAC authentication as the rest of v1.
