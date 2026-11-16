# HackerGuardian packet observation v1

This is the **read-only observation foundation** for future packet-aware movement validation.
It does not implement a movement physics model, a cheat check, or any enforcement.

## Scope

When explicitly enabled, ProtocolLib observes these inbound play packets:

- `GROUND`: no position or rotation payload
- `POSITION`: position update
- `LOOK`: rotation update
- `POSITION_LOOK`: position and rotation update

The observer stores **packet kind, receive-order sequence, and a monotonic arrival timestamp** in a bounded per-player in-memory ring buffer. Each player's retained history is capped. Overwritten events are counted and exposed to operators.

The next increment also stores **nullable, immutable, client-reported**:
- X/Y/Z coordinates from POSITION and POSITION_LOOK
- yaw/pitch from LOOK and POSITION_LOOK
- on-ground state where the boolean field is available
- horizontal collision **only when the layout contains a distinct collision field**; otherwise UNKNOWN. Minecraft 1.20.x–1.21.1 has three booleans (onGround, hasPosition, hasRotation), while 1.21.2+ adds horizontalCollision before the position/rotation presence flags. The decoder verifies the expected trailing presence flags for each packet kind before trusting the on-ground/collision slots.

The decoder includes inherited packet fields via a cached ProtocolLib `StructureModifier` template because movement packet subclasses on some post-1.17 versions expose no direct fields. A packet with missing required coordinates/rotation, or non-finite data, still increments the packet counter but increments `decodeFailures` and **never substitutes a fabricated position**. These are *client claims*, not accepted Bukkit player locations. See the ProtocolLib superclass-field discussion: https://www.spigotmc.org/threads/solution-protocollib-movement-packets-empty-on-1-17-position_look-etc.716161/

In a **separate bounded context ring**, Bukkit's non-cancelled `PlayerTeleportEvent` and `PlayerVelocityEvent` record server-intent target coordinates, rotation, velocity, world UUID and teleport cause. These are **not** decoded outgoing protocol packets and do not prove the client received or acknowledged a teleport/velocity. There is no teleport-ID reconciliation or velocity-ACK implementation yet.

The observer does not store an authoritative client tick, per-tick physics/collision state or raw packet payloads. Observations and server intents are memory-only, cleared at disconnect. No persistent database copies are created.

This is reception timing, **not** a guaranteed client tick clock. Network/proxy batching, packet delays, unusual scheduling and ProtocolLib listener order can change the arrival distribution.

## Thread-safety

Inbound ProtocolLib packet callbacks generally execute off the Bukkit main thread.
The callback only:

1. obtains the already-associated player reference;
2. retrieves an immutable UUID captured on the Bukkit thread;
3. classifies and decodes the packet into immutable, nullable primitives;
4. appends an immutable observation through a synchronized per-player buffer.

It does not inspect world, inventory, player location, game mode or Bukkit state on a packet thread.
Server-intent context is copied on the Bukkit main thread, where teleport and velocity events occur.
Join/quit are handled on the Bukkit event thread, and the player's session is invalidated at quit so late callbacks cannot resurrect the buffer.

## Enable for controlled testing

Edit `plugins/HackerGuardian/detection.yml`:

```yaml
DetectionV2:
  packet_observation:
    enabled: true
    max_packets_per_player: 256
```

Restart the server. This observer only starts if ProtocolLib is present and
the Minecraft version falls within HackerGuardian's explicitly verified range.

Operators with `hg.admin.detection` can run:

```text
/hg detection
/hg detection packets <online-player>
```

The in-game command defaults to a **five-line summary** instead of dumping
every diagnostic value into Minecraft chat:

```text
/hg detection packets <player>          # 5-line summary
/hg detection packets <player> move     # detailed movement, <=6 lines
/hg detection packets <player> net      # teleport + velocity, <=6 lines
```

The summary includes total packets, decode errors, the recent five-second
receive rate, ground/collision flags, and teleport pending/matched/unmatched
counts. Use `move` for coordinates, rotation, buffer capacity and packet-kind
breakdown; use `net` for teleport confirmation IDs, resends, timeouts,
outgoing self-velocity, and Bukkit server intent.

The recent breakdown is calculated from the **bounded retained observations**,
not from an independent unbounded event log. When old observations were
overwritten inside that five-second interval, `move` explicitly shows
`5s truncated` and the rates are lower bounds. A high packet rate or
unmatched teleport confirmation **is not** proof of cheating.

Disable the switch and restart to turn off the observer.

## Validation checklist

- Confirm startup and shutdown on Paper with the supported ProtocolLib version.
- Join as a legitimate player; inspect packet counts while idle, moving, looking and sprinting.
- Confirm X/Y/Z and yaw/pitch change independently during POSITION/LOOK, and ground state is plausibly reported; check `decodeFailures` stays at zero for supported layouts.
- Confirm a non-cancelled `/tp` produces server-intent context; test a plugin/knockback velocity separately. No context should be mistaken for client confirmation.
- Confirm non-player packets do not appear in movement observations.
- Quit/rejoin, and verify old history is cleared.
- Test across actual Minecraft versions (not only the API compilation matrix).
- Verify behavior under proxy latency, induced lag, packet batching and reconnects.
- Profile allocations/CPU at 20, 50 and 100+ players; increase/decrease ring capacity with measured data.
- Ensure gameplay, punishment, replay, ML and reports remain unchanged.

## First single-account runtime smoke test (2026-11-16)

The project tester reported successfully completing these checks in Minecraft:
idle observation, looking around, walking/sprinting/sneaking/jumping, teleporting,
and disconnecting/reconnecting. The screenshot showed increasing packet counts
and correct bounded-buffer accounting (289 total, 256 retained, 33 overwritten).

**Scope of this result:** initial self-reported single-client manual testing only.
It is not evidence of cheat-detection accuracy, all-client compatibility, proxy
behavior, or scalability. The 5-second per-kind diagnostics were added *after*
this initial test and still require an in-game check.

## Boolean flag mapping regression (2026-11-16)

Initial solo testing found that `Horizontal collision` appeared true all the time,
including in mid-air. The earlier decoder unconditionally interpreted boolean
index 1 as collision. That is **incorrect on legacy movement packets**:
index 1 is the `hasPosition` flag, usually true for POSITION updates.
We now detect the exact three-vs-four boolean field shape and verify packet
kind sentinels before interpreting flags. Legacy sessions should show
`Horizontal collision: UNKNOWN` (not false or true), even during flight.

Sources for protocol field layouts:
- [Minecraft 1.20.2 movement packet fields](https://mappings.dev/1.20.2/net/minecraft/network/protocol/game/ServerboundMovePlayerPacket.html)
- [Minecraft 1.21.1 movement packet fields](https://mappings.dev/1.21.1/net/minecraft/network/protocol/game/ServerboundMovePlayerPacket.html)
- [Minecraft 1.21.11 movement packet fields](https://mappings.dev/1.21.11/net/minecraft/network/protocol/game/ServerboundMovePlayerPacket.html)

**Follow-up checks:** rebuild the same PR branch, restart, and test ground vs
mid-air movement. On 1.20.x–1.21.1, collision must be UNKNOWN. On later
versions, compare collision near a wall vs in free flight. If either on-ground
or collision values remain implausible, mark this runtime mapping unverified
for that server version rather than interpreting it as physics evidence.

## Regression retest: on-ground and collision (2026-11-16)

The tester rebuilt and retested the boolean-layout fix on the same Minecraft
test server. Two in-game readings show:

- While airborne: `Client on-ground: false`.
- Once standing on the ground: `Client on-ground: true`.
- In both readings: `Horizontal collision: UNKNOWN` (rather than the
  incorrect always-true value).
- `Decoder failures: 0` in both observations.
- Client position Y changed between readings, consistent with vertical motion.

**Result:** pass for this *single-client, single-server* ground-state/collision
regression smoke test. Server/client/ProtocolLib version details were not captured
in the screenshots, so do not claim specific-version support from this test.
Collision `UNKNOWN` must never be treated as `false` for physics decisions.
No inference about anti-cheat effectiveness, network packet delivery, latency
handling, or other Minecraft versions is supported by this result.

## Network teleport/velocity correlation (2026-11-16)

The packet observer now tracks the *transport observation timeline* independently
from the Bukkit intent snapshots:

- **Outbound `PacketType.Play.Server.POSITION`:** read the teleport ID and
  track it as pending. Re-sent IDs refresh their age without inventing a new
  teleport. At most 32 IDs remain pending; older IDs expire after 30 seconds.
- **Inbound `PacketType.Play.Client.TELEPORT_ACCEPT`:** decode its teleport
  ID and remove *only* a matching pending ID. Unknown, repeated, expired or
  unreadable confirmations are counted separately. A match means the server
  observed the client's confirmation packet; it is **not proof** that the
  client applied the correct coordinates or that the server accepted them.
- **Movement while a teleport is pending:** count observed inbound movement
  packets during that window and retain the count for the last matched
  teleport. These movements may be stale packets in flight; this counter
  must not trigger an anti-cheat finding.
- **Outbound `PacketType.Play.Server.ENTITY_VELOCITY`:** consider only a
  velocity packet whose entity ID matches the player's ID previously obtained
  on the Bukkit thread (also refreshed after respawn). Where possible, read
  an actual velocity vector; otherwise store an observed event with an
  unknown payload. This packet has **no equivalent client acknowledgement**.
- **Bukkit `PlayerTeleportEvent` and `PlayerVelocityEvent`:** remain
  separate `BUKKIT_*_INTENT` events. We do not claim a one-to-one link to
  a particular outgoing packet or guarantee that a sending callback equals
  network delivery.

All observations are per-player, bounded and cleared on disconnect; the
transport ring retains at most 64 significant events. No new database
tables, replay triggers, alerts, kicks, bans, setbacks or ML labels are added.
The feature remains disabled by default.

### Solo runtime checks

1. Run `/hg detection packets <player>` after joining. Teleport counts may
   include server-issued initial position synchronization.
2. Perform `/tp` and run the command again. Look for an outgoing teleport,
   a matching `TELEPORT_ACCEPT`, and a pending count that returns to zero.
3. If an outbound teleport ID cannot be decoded, `ID decode errors` should
   increase; this is a compatibility gap, **not** player misconduct.
4. Try legitimate knockback or a plugin-issued player velocity. Confirm
   `Self-velocity packets observed` increases only when an actual self-entity
   velocity packet is sent. `Unknown payload` means the vector layout needs
   per-version validation.
5. Reconnect and ensure all transport counters reset.
6. Check console for exceptions and test on supported Paper versions and
   ProtocolLib versions separately. Build-time API compatibility alone is
   not proof of packet compatibility.

A high pending count, an unmatched ACK or an observed packet delay must not
be used as Timer, Speed or Fly evidence until network ordering, server
corrections and server tick state are validated.

## Minecraft chat output compacted (2026-11-16)

The initial transport diagnostics overflowed the user's Minecraft chat
window: a long sequence of packet counts, coordinates, flags and matching
counters scrolled earlier results out of view.

The default `/hg detection packets <player>` now emits **five lines**.
Explicit `move` and `net` modes keep the detailed information available
in short, single-purpose views. Unit tests assert the maximum line counts
and retain important unmatched/pending/decoder-error information.
No packet capture, physics scoring, punishment or API behavior changed.

## Solo transport and chat diagnostic retest (2026-11-16)

The tester captured screenshots of all three compact views after a legitimate
teleport. Observed:

- Default `summary`: five lines, 15 received, 0 decoder failures, 1.4/s
  recent rate; on-ground false; collision UNKNOWN.
- `move`: 23 received, 23 retained, 0 overwritten, 0 decoder failures.
  Last client XYZ approximately `74.75, 105.37, 200.99` at packet #23;
  last yaw/pitch at packet #10 (not an atomic sample).
- `net`: one outgoing teleport observed, two client confirmations, one
  matched (ID 2), one unmatched, zero pending, zero resends or expirations,
  zero ID decode errors and zero movement observed before the matched ACK.
- Bukkit intent: one teleport event with cause COMMAND. The position matches
  the in-game teleport destination to the precision displayed.
- Neither the screenshot nor the counters establish that every outbound
  packet reached the client. A single unmatched confirmation could reflect
  connection-start timing, but its specific cause was not established.

**Result:** pass for the single-account teleport-correlation and compact-chat
smoke test. Outgoing self-velocity delivery/decoding, multiple-version
compatibility and stress behavior remain untested. This is not validation of
cheat detection or enforcement.

## Zombie knockback discrepancy: missing outgoing self-velocity (2026-11-16)

In the initial single-account zombie-hit test, Bukkit server-intent events
increased from 1 to 3, ending with `VELOCITY`, but
`Self-velocity packets observed` stayed at 0 and
`Unknown payload` stayed at 0. This does **not** prove that no velocity
packet was actually sent to the client: the packet listener may not have
received a callback, the entity-ID field may be incompatible with the
decoder, the packet might have targeted another entity, or a plugin/server
may have handled velocity through a different network path.

We added **read-only** transport diagnostics which count *all*
outbound `ENTITY_VELOCITY` callbacks associated with this player's session
**before** target-ID filtering:

- `Velocity wire`: observed callbacks of that packet type
- `self`: callbacks matching the cached player entity ID
- `other`: callbacks whose readable ID targets another entity
- `ID?`: callbacks where target entity ID could not be decoded
- `Self entity ID` and `last wire ID`: help diagnose an entity-ID mismatch
- `payload?`: self-directed packets with an unreadable velocity vector

The view remains at six lines and does not expose entity IDs beyond the
operator's existing `hg.admin.detection` permission.

### Follow-up solo test

Rebuild the same branch, restart, join and check
`/hg detection packets <player> net`. Get hit by a zombie in Survival mode
and rerun the command.

- `Velocity wire = 0` despite new Bukkit velocity intents: investigate
  listener registration, protocol packet type and whether the server sent
  such packets at all.
- `Velocity wire > 0`, `other > 0`: investigate target entity ID vs
  cached self entity ID and respawn/rejoin behavior.
- `Velocity wire > 0`, `ID? > 0`: investigate ProtocolLib field layout
  for the exact Minecraft/ProtocolLib versions; **do not guess IDs**.
- `self > 0`, `payload? > 0`: the self entity was recognized but
  the velocity payload layout needs version-specific support.
- `self > 0`, `payload? = 0`: mark the outbound self-velocity observation
  smoke test passed for this setup. No client delivery guarantee exists.

The diagnostic counters are **never** a Speed, Fly, or AntiKnockback verdict.

## Zombie knockback retest: outgoing self-velocity observed (2026-11-16)

The tester repeated the zombie-hit experiment after adding pre-filter
velocity callback counters. Two in-game `net` screenshots show:

- Before the hit: `Velocity wire 28 | self 0 | other 28 | ID? 0`,
  with `Bukkit intents 0`.
- After the hit: `Velocity wire 414 | self 1 | other 413 | ID? 0`,
  with `Bukkit intents 1 | last VELOCITY`.
- The cached self entity ID was `172`; the most recent outgoing velocity
  packet targeted entity `174`, which is a separate entity and not evidence
  of a self ID mismatch.
- `Unknown payload 0` after the hit: the matched self-targeting packet
  yielded a readable vector.
- Teleport tracking remained independent. One unmatched confirmation was
  still present, but no teleport was pending.

**Result:** pass for a **single-account, single-server** outgoing self-velocity
observation smoke test. The earlier zero self count was not enough to diagnose
the listener, because other-entity velocity callbacks were already being
observed. These are cumulative per-session counters: 414 is *not* the number
of knockback incidents. The test does not prove client receipt, correct
knockback physics, version-wide decoding compatibility, or anti-cheat efficacy.

## Server-tick-aware movement foundation (2026-11-16)

This increment adds a **read-only, 64-tick bounded server-state history**
to each active packet-observation session. One Bukkit scheduler callback per
server tick records immutable snapshots **on the main thread only**:

- Scheduled server tick index and a monotonic observation timestamp
- Server-side X/Y/Z position, velocity, world UUID
- Bukkit on-ground, flying, gliding, swimming, vehicle, and creative/spectator flags
- Lightweight block hints from feet, head and the block directly below
  (passability and liquids), without generating/loading new chunks

These are environment **hints**, not collision boxes or a predictive physics
model. The sampler skips invalid coordinates, outside-height locations and
unloaded chunks instead of guessing collision results.

Decoded inbound client positions are timestamped separately from server ticks.
The correlator attempts to find the **first server sample observed after**
the most recent client position. It only reports a raw coordinate difference
when the elapsed observation gap is at most 250 milliseconds and the sample
does not cross an invalidating context boundary. A position gap is an
**observed diagnostic difference**, *not* Speed/Fly evidence.

Teleport intent, outbound teleport packet observation, teleport confirmation,
Bukkit velocity intent, outbound self-velocity, respawn, and world change
each invalidate comparisons across their boundary. The first two **successfully captured**
server samples are treated as a correction window (skipped scheduler ticks
do not count). Any still-pending teleport confirmation also blocks the
comparison, even after the normal cooldown. Missing/late samples,
uncertain ordering, and world changes show a reason instead of an invented
position comparison. A scheduled tick is a local sampling index and is **not**
the client's simulation tick; actual tick duration can vary with server load.

A new short operator view is available (still under `hg.admin.detection`):

```text
/hg detection packets <player> tick
```

It shows at most six chat lines: server tick/history counters, server
coordinates and velocity, movement/ground and block hints, and either the
raw client/server coordinate delta with sample gap or a comparison status.
The default five-line summary, `move`, and `net` views stay unchanged
apart from mentioning the new `tick` option.

### First solo runtime checklist

1. Stand still, run `... tick`, and verify server XYZ and on-ground state.
2. Walk/sprint/jump, run `... tick` repeatedly; values should update. Raw
   delta is informational and may differ from zero, especially with lag.
3. Fly in Creative mode and check `flying=true`. Game mode itself is
   retained in the underlying snapshot but is not a cheat finding.
4. Run `/tp ~ ~10 ~`; expect a `CORRECTION_WINDOW` or
   `DIFFERENT_CONTEXT` transiently rather than a movement violation.
5. Take legitimate zombie knockback; its velocity intent and outbound
   self-velocity should fence overlapping comparisons.
6. Quit/rejoin. Tick history starts fresh; check no console exceptions.
7. Benchmark with multiple players and slow TPS before increasing sampling
   or using the data in a predictor. The current code is opt-in and
   memory-only: no packet or world data is persisted.

**Status:** CI passed for the server-tick foundation, and the following
single-account runtime smoke test passed. Precise client/server tick
synchronization, all correction scenarios, cross-version accuracy, and
multiplayer performance remain unverified.

### Single-account tick diagnostics retest (2026-11-16)

The tester supplied two screenshots from the `tick` view:

- **Standing:** server tick 551; 64 retained server samples, 174
  overwritten; XYZ approximately `110.38 68.00 191.01`;
  `ground=true`, `flying=false`; feet passable, below not passable;
  raw delta `0.000` and gap `39.0 ms`.
- **Creative flight:** server tick 598; 64 retained samples, 222
  overwritten; XYZ approximately `112.80 72.37 182.59`;
  `ground=false`, `flying=true`; feet and below passable;
  raw delta `0.000` and gap `36.6 ms`.
- The in-game output remained compact. The block hints and movement
  mode flags were consistent with the reported standing/flight scenarios.

**Result:** pass for these two *single-account, single-server* snapshot
cases. The raw deltas compare recorded client claims with following
server snapshots; neither 0.000 nor the gap timings constitute a
prediction-quality or latency measurement. Pending-teleport suppression,
knockback correction, unusual block shapes, skipped tick sampling, and
multi-version runtime correctness are not established by these screenshots.

## Continuous-flight comparison diagnostic fix (2026-11-16)

The tester reported multiple consecutive `tick` views during normal
Creative flight showing `AWAITING_NEXT_TICK` as the server tick increased
from 1990 to 2035 to 2073, alongside a displayed last correction of
`TELEPORT_CONFIRM`. This did **not** show repeated teleports: the
correction field was historical. The immediate issue was that the
snapshot tried to compare only the **latest** client position; a client
packet arriving after the most recent server sample naturally had no
following sample yet.

The timeline now keeps the latest **completed, validated diagnostic
pair** when the next client position is still waiting for a server tick.
Its recorded client packet order and following server sample remain
available; the pair is never described as the current client position.
Completed pairs expire after 750 ms, and teleport/velocity/respawn/world
changes immediately discard them. An outstanding teleport ID still
suppresses comparisons. No packets or samples are kept indefinitely.

The `tick` view now labels the measurement `Last pair delta` and does
not imply that a previous `TELEPORT_CONFIRM` is the current cause of
`AWAITING_NEXT_TICK`. Regression tests reproduce continuous incoming
movement, pair rollover, expiration, correction invalidation and pending
teleport suppression.

### Ground/Creative flight regression retest (2026-11-16)

The tester rebuilt the corrected branch and provided screenshots of repeated
`/hg detection packets HypersMC tick` readings while alternating between
Creative flight and being on the ground:

- Server tick 1289: `ground=false`, `flying=true`, feet/below passable,
  `Last pair delta 0.000`, gap 36.5 ms.
- Server tick 1361: `ground=true`, `flying=false`, feet passable,
  below not passable, `Last pair delta 0.000`, gap 39.3 ms.
- Server tick 1390: `ground=false`, `flying=true`, feet/below passable,
  `Last pair delta 0.000`, gap 40.3 ms.

**Result:** pass for this single-server continuous-movement/ground-flight
diagnostic regression test. Completed position comparisons remained available
instead of repeatedly showing `AWAITING_NEXT_TICK`, and the Bukkit movement
mode/block context changed consistently with the tester's actions. This does
NOT validate client physics prediction, lag/teleport/knockback boundary
handling, multi-version compatibility, or detection accuracy.

## Next increment

Test correction states during `/tp` and zombie knockback, world changes
and reconnects, plus server stress/slow TPS. Validate the on-disk/client
version fixtures and identify whether per-tick Bukkit block sampling
remains inexpensive at useful player counts. After that, build a narrow
**observe-only** horizontal/vertical motion plausibility prototype with
version-aware physics and explicit no-decision contexts; avoid using
raw coordinate deltas or packet arrival rates directly as Speed/Fly
verdicts. All detectors remain evidence-only until false-positive
handling is independently validated.
