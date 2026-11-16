# HackerGuardian packet observation v1

This is the **first narrow, read-only step** toward packet-aware movement validation.
It does not yet implement a movement physics model or a cheat check.

## Scope

When explicitly enabled, ProtocolLib observes these inbound play packets:

- `GROUND`: no position or rotation payload
- `POSITION`: position update
- `LOOK`: rotation update
- `POSITION_LOOK`: position and rotation update

The observer stores **packet kind, receive-order sequence, and a monotonic arrival timestamp** in a bounded per-player in-memory ring buffer. Each player's retained history is capped. Overwritten events are counted and exposed to operators.

The observer does **not** store the packet's position, rotation, on-ground flag or tick state yet. This is intentional: raw packet field normalization must be verified separately for each supported protocol family rather than assuming layout compatibility. It also does not persist raw packet history to the database.

This is reception timing, **not** a guaranteed client tick clock. Network/proxy batching, packet delays, unusual scheduling and ProtocolLib listener order can change the arrival distribution.

## Thread-safety

Inbound ProtocolLib packet callbacks generally execute off the Bukkit main thread.
The callback only:

1. obtains the already-associated player reference;
2. retrieves an immutable UUID captured on the Bukkit thread;
3. classifies the packet kind;
4. appends a tiny immutable observation through a synchronized per-player buffer.

It does not inspect world, inventory, player location, game mode or Bukkit state.
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

The recent breakdown is calculated from the **bounded retained observations**,
not from an independent unbounded event log. If old observations were overwritten
inside that five-second interval, the command flags the window as truncated;
the displayed rate and per-kind counts are then lower bounds. A high packet rate
**is not** a Timer finding and triggers no action.

Disable the switch and restart to turn off the observer.

## Validation checklist

- Confirm startup and shutdown on Paper with the supported ProtocolLib version.
- Join as a legitimate player; inspect packet counts while idle, moving, looking and sprinting.
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

## Next increment

Define a versioned packet-normalization contract for coordinates, rotation,
on-ground and sequence/timing context, plus server teleport and velocity
acknowledgments. Only then should we attempt client physics simulation and
a Timer/Speed/Fly detection policy. All detectors remain evidence-only until
false-positive handling is independently validated.
