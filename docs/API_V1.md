# HackerGuardian HTTP API v1

HackerGuardian exposes a server-to-server API for the web interface.

```text
Browser
  |
  v
Laravel
  |
  | HMAC-signed HTTP
  v
HackerGuardian proxy / standalone Paper
```

The browser must never receive HackerGuardian API keys. Laravel is the API client.

When `Settings.behind_proxy=true`, Paper does **not** open the public HG HTTP listener. It publishes secret-free operational status/evidence to the shared HackerGuardian database and the proxy exposes the authoritative API. Standalone Paper exposes the same contract with `role=backend`.

## Authentication

Every API route uses the same headers on Paper and proxy:

```text
X-HG-Key: <key id>
X-HG-TS: <unix epoch milliseconds>
X-HG-Nonce: <unique nonce>
X-HG-Sig: <lowercase hex HMAC-SHA256>
```

The signing string is exactly:

```text
UPPERCASE_HTTP_METHOD + "\n" +
SIGNED_REQUEST_TARGET + "\n" +
X_HG_TS + "\n" +
X_HG_NONCE + "\n" +
LOWERCASE_HEX_SHA256_OF_EXACT_BODY
```

`SIGNED_REQUEST_TARGET` is the normalized path plus the **raw query string when present**. Examples:

```text
/v1/health
/v1/replays?page=1&per_page=25&server=pvp
```

Query order and percent-encoding therefore matter to the signature: sign the exact request target that Laravel sends. A trailing slash is normalized away before verification.

`X-HG-Sig` is `HMAC-SHA256(secret, signing_string)` encoded as lowercase hexadecimal.

The timestamp must fall inside `allowed_skew_ms`, and a valid `(key, nonce)` may only be used once during the nonce TTL. A nonce is consumed only after the signature itself is valid.

With `require_auth=true`, the API refuses to start if all configured secrets are placeholders or shorter than 32 characters.

## Response envelope

Success:

```json
{
  "ok": true,
  "data": {},
  "meta": {
    "api_version": 1,
    "time_ms": 1787420000000
  }
}
```

Error:

```json
{
  "ok": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "Replay not found"
  },
  "meta": {
    "api_version": 1,
    "time_ms": 1787420000000
  }
}
```

Responses use `application/json; charset=utf-8`, `Cache-Control: no-store`, and `X-Content-Type-Options: nosniff`.

The HG API does not expose database credentials, HMAC secrets, secure-link secrets, arbitrary raw configuration, backend IP addresses, or raw replay database blobs.

## Routes

### `GET /v1/health`

Authoritative connection/status route.

Important fields:

- `role`: `proxy` or `backend`
- `instance_name`
- `plugin_version`
- `minecraft_version` (`null` for the proxy itself)
- `uptime_ms`
- `players_online`
- `database.configured`, `database.healthy`, `database.type`
- `capabilities`
- `servers`

Proxy server entries are derived from backend operational heartbeats and never contain backend addresses.

### `GET /v1/servers`

Returns secret-free operational state for HackerGuardian backends:

```json
{
  "servers": [
    {
      "name": "pvp",
      "online": true,
      "players_online": 18,
      "minecraft_version": "1.21.11",
      "plugin_version": "0.3.0",
      "detection_enabled": true,
      "tracked_players": 18,
      "learning_enabled": true,
      "trusted_players": 4,
      "learning_active_hours": 142.7,
      "active_probes": 0,
      "synthetic_probes": true,
      "last_seen_ms": 1787420000000
    }
  ]
}
```

A heartbeat older than `server_status_stale_ms` is treated as offline.

### `GET /v1/settings`

Returns safe effective operational settings only. It is not a raw YAML/config endpoint.

Current groups are `detection`, `learning`, `replays`, and `moderation`.

## Reports

### `GET /v1/reports`

Query parameters:

- `status`
- `q`
- `page`
- `per_page`

Returns a paginated `reports` array with nested `reported` and `reporter` identities.

### `GET /v1/reports/{id}`

Returns the report plus `comments`, `replays`, and `detections` collections.

The current database schema has no explicit report-to-replay or report-to-detection relation. Until that relation exists, `linked_replays`/`linked_detections` are `0` and the detail collections are empty. The API deliberately does not pretend that unrelated records for the same player are linked evidence.

The current report schema also has no `server_name`, so that field is currently `null`.

## Replays

Replay API output is intentionally independent from HackerGuardian's internal BLOB serialization.

### `GET /v1/replays`

Query parameters:

- `player_uuid`
- `player_name`
- `server`
- `trigger`
- `from` (epoch ms)
- `to` (epoch ms)
- `page`
- `per_page` (max 100)

Returns metadata including replay id, player, server, trigger, format/codec, size, duration, and chunk count.

### `GET /v1/replays/{id}`

Returns the viewer manifest:

- replay metadata
- `capture_start_at` / `capture_end_at`
- `trigger_offset_ms`
- first known world name
- ordered chunk metadata
- timeline trigger marker

Replay storage can include pre-trigger buffering. For browser playback, time `0` is the first captured chunk, while `trigger_offset_ms` marks when the replay trigger occurred.

Chunk `start_ms`/`end_ms` in the manifest are relative to the capture origin.

### `GET /v1/replays/{id}/chunks/{seq}`

HackerGuardian reads and decompresses its private replay chunk format and returns stable browser-facing JSON:

```json
{
  "replay_id": 938,
  "seq": 14,
  "start_ms": 70000,
  "end_ms": 75000,
  "format": "hg-web-replay-v1",
  "frames": [
    {
      "t": 70000,
      "players": [],
      "events": []
    }
  ]
}
```

Supported decoding currently includes subject/nearby player snapshots, block break/place, arm swing, sneak/sprint toggles, item consume, inventory click, and item drop/pickup. Unknown future internal event types are not allowed to expose the private binary format.

Safety limits bound decoded chunk size, individual event size, and record count.

## Detection

HackerGuardian keeps detection evidence separate from policy/punishment.

### `GET /v1/detection/status`

Returns:

- enabled state
- `mode=OBSERVE_ONLY`
- assessment window (standalone; may be `null` on a mixed-version proxy aggregate)
- tracked player count
- typed detector list
- supervised/population model state
- Learning Mode summary

Detector types distinguish `snapshot`, `deterministic`, `ml`, and `normality`. This includes checks such as `mining.fast-break`; the API does not repeat the old command-display problem where only snapshot detectors appeared.

### `GET /v1/detection/recent`

Optional query parameters:

- `player_uuid`
- `detector`
- `before` (epoch ms)
- `limit` (max 500)

Findings are persisted through a bounded asynchronous journal and expose:

- UUIDv7 event id
- time/player/server
- detector/category
- evidence strength
- score/reliability
- aggregate assessment risk
- numeric evidence metadata
- `replay_id` when a real relation exists

Near-identical periodic findings are suppressed for a configurable interval so a one-second assessment loop does not write one duplicate row per second.

### `GET /v1/detection/player/{uuid}`

Returns the investigation projection:

- player identity
- latest persisted assessment summary
- recent findings
- Learning Mode state when directly available
- normality model state when directly available
- recent replays for the player

A recent replay is not automatically claimed to be linked to a finding. `replay_id` remains `null` until HackerGuardian creates an explicit evidence relation.

## Learning Mode

### `GET /v1/learning/status`

Returns enabled/trusted population state, total accumulated active hours, model state, and probe activity.

`candidate_rows`, `eligible_rows`, and completed-probe totals are currently `null`. HackerGuardian deliberately does not scan potentially huge CSV datasets during an API request or pretend process-local counters are historical totals. `candidate_rows_this_process` is exposed separately when standalone Paper can prove it.

### `GET /v1/learning/players`

Query parameters:

- `trusted_only` (default `true`)
- `limit` (max 5000)

Returns the secret-free player status projection published by Paper:

- UUID/name/server
- trusted state
- accumulated active hours
- baseline maturity
- first trusted / last seen / last probe timestamps

`candidate_samples`, `eligible_samples`, and `quarantine_until` are currently `null`: current Learning Mode eligibility is row/session based, not one player-wide quarantine timestamp.

## Moderation

### `GET /v1/moderation/actions`

Query parameters:

- `target_uuid`
- `type`
- `server`
- `from`
- `to`
- `page`
- `per_page`

Returns a normalized audit stream across BAN/UNBAN/MUTE/UNMUTE/KICK/IP_BAN/IP_UNBAN. The public projection includes target identity where applicable, actor, reason, timing, scope, and active state where the underlying punishment row proves it.

The API intentionally does not expose target IP addresses in this initial operational contract. `linked_report_id` and `linked_replay_id` remain `null` until explicit relationship columns/tables exist.

## Current write policy

This first v1 surface is read-only. Future settings/moderation writes should be purpose-specific validated routes such as `PATCH /v1/settings/detection`; HackerGuardian should never expose arbitrary YAML editing or a generic SQL/config mutation endpoint.
