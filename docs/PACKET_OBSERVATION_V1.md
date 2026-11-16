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

The command shows the accumulated received count for the current session,
retained history length, overwritten count, latest movement packet kind, and
sequence. It also displays a **recent five-second breakdown** of POSITION,
POSITION_LOOK, LOOK and GROUND packets, plus their receive rate.

The command also shows the latest raw client position/look/ground/collision fields, their packet sequence provenance, `decodeFailures`, and the latest Bukkit server intent where available. The recent breakdown is calculated from the **bounded retained observations**,
not from an independent unbounded event log. If old observations were overwritten
inside that five-second interval, the command flags the window as truncated;
the displayed rate and per-kind counts are then lower bounds. A high packet rate
**is not** a Timer finding and triggers no action.

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

## Next increment

Validate this normalized raw-state capture against actual supported Minecraft/ProtocolLib combinations. Next add explicit outbound teleport IDs, client teleport confirmations, delivered velocity context, server collision/physics snapshots and protocol-version-specific fixtures. **Do not use raw coordinates or arrival rates for speed/fly verdicts** until the server/client state timeline is sufficiently accurate. All detectors remain evidence-only until false-positive handling is independently validated.
