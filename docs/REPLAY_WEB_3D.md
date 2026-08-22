# Web replay 3D world contract

HackerGuardian stores immutable chunk snapshots for replay sandbox playback in `hg_replay_world_chunks`. The web API exposes those snapshots as a stable browser-facing format without exposing the private BLOB serialization.

## Routes

`GET /v1/replays/{id}` includes a `world_snapshot` manifest:

```json
{
  "available": true,
  "format": "hg-web-world-v1",
  "anchor_ms": 30000,
  "anchor_precision": "exact_per_chunk",
  "chunk_count": 25,
  "size_bytes": 1234567,
  "chunks": [
    {
      "world": "world",
      "chunk_x": 4,
      "chunk_z": -2,
      "size_bytes": 48231,
      "anchor_ms": 30216,
      "anchor_precision": "exact"
    }
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
  "anchor_ms": 30216,
  "anchor_precision": "exact",
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

## Player/actor timing

New Paper recordings capture the subject player every server tick by default:

```yaml
Replays:
  subject_snapshot_interval_ticks: 1   # 20 Hz

  context:
    interval_ticks: 2                  # nearby players at 10 Hz
```

These are evidence sample rates, not browser frame rates. The browser must interpolate between the recorded samples on its render clock. FOLLOW/POV camera state must be derived from the same render-frame subject sample as the visible subject actor; camera smoothing must never substitute for missing actor interpolation.

Subject interpolation rules are deliberately conservative:

- interpolate position only on the straight line between two recorded samples;
- interpolate yaw along the shortest angular path;
- do not interpolate across world changes or large teleport discontinuities;
- discrete state such as sneak/sprint/held item changes at a recorded sample boundary;
- if a browser/GPU frame stalls, replay time should slow rather than skipping evidence.

The replay coordinator runs once per real server tick. Subject capture, nearby context and world snapshot acquisition each have independent cadences. This also makes `world_capture.chunks_per_tick` mean actual server ticks rather than being multiplied by the old subject snapshot scheduler period.

## Rendering expectations

The fidelity web client should:

1. load the replay manifest and all ordinary replay frame chunks before Play is enabled;
2. load every recorded world chunk keyframe required by the replay before Play is enabled;
3. select/import the exact recorded Minecraft/resource-pack asset set where possible;
4. expand RLE world sections and build render geometry off the main UI thread;
5. preload/decode all textures/models required by the recorded world and block-event revisions;
6. build immutable player tracks from the replay timeline;
7. run the replay clock, actor interpolation, FOLLOW/POV camera and dynamic world revision switching from the WebGPU render loop;
8. publish a throttled UI clock separately for timeline labels/inspector controls;
9. unlock Play only after data, textures, geometry, actor tracks, GPU buffers, shaders and the first rendered frame are ready.

The world keyframes are immutable visual anchors. New recordings persist an exact capture timestamp for each chunk because snapshot acquisition is intentionally spread across server ticks. Older recordings that predate per-chunk timestamps are explicitly reported as `trigger_estimate`; the API must never pretend that estimate is exact.

## Security and resource bounds

- Raw `hg_replay_world_chunks.data` is never returned.
- Decompression is bounded to 64 MiB per requested stored chunk.
- Palette size is bounded.
- All-air vertical sections are removed from the public representation.
- The API is read-only and uses the same HMAC authentication as the rest of v1.
- Resource-pack identity is metadata only; no API/HMAC/database secrets are exposed to the browser.
