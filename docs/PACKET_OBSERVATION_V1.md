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

## Next increment

Validate outgoing self-velocity observation on the tested server and examine
the isolated unmatched teleport confirmation without assuming bad client
behavior. Add server tick, physics and collision/world context, packet-version
fixtures, and stronger consistency checks before attempting a movement
predictor. **Do not use raw coordinates or arrival rates for Speed/Fly
verdicts** until the server/client state timeline is sufficiently accurate.
All detectors remain evidence-only until false-positive handling is
independently validated.
