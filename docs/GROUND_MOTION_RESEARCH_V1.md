# Ground-motion research v1 (observe-only)

This is **not a Minecraft physics simulator** and **not a Speed/Fly detector**.
It is a deliberately limited baseline experiment for **ordinary horizontal
movement on flat ground**. It measures the movement between two consecutive
server-side position samples, uses a very loose diagnostic envelope, and
counts potential *research outliers* for manual evaluation.

There is **no connection** to HackerGuardian's risk scores, deterministic
detections, ML/normality learning, evidence journal, reports, setbacks,
kicks, or bans. No change to existing enforcement policies is made.

## Enable deliberately

In `plugins/HackerGuardian/detection.yml`:

```yaml
DetectionV2:
  packet_observation:
    enabled: true
    ground_probe:
      enabled: true
```

Restart the server, then:

```text
/hg detection packets HypersMC probe
```

The probe is separately **disabled by default**, even when packet
observation is on. It runs only on already-supported versions with
ProtocolLib, using the existing packet-observer compatibility gate.
When disabled it does not perform its optional potion/speed/ground-profile
sampling or allocate a per-player probe.

This view fits in six chat lines. It exposes cumulative session tick samples,
eligible pairs, no-decision pairs, last status, measured horizontal delta,
loose research envelope, and research outlier count. Stats reset on reconnect
and are not persisted.

## Strict eligibility

A pair must have:
- two **successive server scheduler ticks** in the same world, within
  **25–125 ms** between captures;
- no intervening teleport, velocity, respawn, or world correction epoch;
- enough successfully captured samples after the last correction, with no
  pending teleport ID;
- a recently observed decoded inbound client position;
- Survival/Adventure (not Creative/Spectator), grounded at *both* samples;
  no flight, Elytra, swimming, or vehicle movement;
- an ordinary full block under the player's feet from a tiny allowlist
  (`STONE`, `DIRT`, `GRASS_BLOCK`, `COBBLESTONE`, `DEEPSLATE`);
- block passability/liquid hints compatible with ordinary flat ground,
  feet near an integer Y coordinate, no active potion effects,
  standard Bukkit walk speed, no plugin-granted flight or Riptide;
- negligible vertical displacement across the pair and no obviously
  high external Bukkit velocity.

**Everything else is `NO_DECISION`**. Slabs, stairs, ice, honey, slime,
water, soul sand, unusual speeds, altered terrain, high lag, boosts, flight,
and packets with unknown context deliberately skip evaluation. More precise
collision shapes and server-authoritative physics parameters are not yet
available. Some modded/plugin-altered movement may still satisfy the rough
eligibility checks, so even a research outlier is **not a cheating finding**.

## Output interpretation

The envelope is set to a deliberately permissive **1.2 blocks per 50 ms**
scaled by the observed server-sample interval. That number is an initial
research heuristic, **not** a proven maximum vanilla walk/sprint speed,
and it should never be used in any enforcement policy.

- `WITHIN_RESEARCH_ENVELOPE`: one eligible server-side horizontal
  displacement was below this loose research threshold; not proof of
  legitimate movement.
- `RESEARCH_OUTLIER`: an eligible sample exceeded this threshold;
  **not** proof of cheating or an actionable alert.
- `WARMUP`, `CORRECTION`, `TICK_GAP`, `UNSTABLE_TICK`,
  `NO_CLIENT_DATA`, `SPECIAL_MOVEMENT`, `UNSAFE_TERRAIN`, or
  `UNKNOWN_PHYSICS`: no decision. These are expected, not failures.
- `eligible` and `no-decision` count sampled tick pairs and do not
  measure detection accuracy, false-positive rate, or client packet loss.

## Solo test

1. Verify `/hg detection packets HypersMC probe` reports **OFF**
   before enabling the new switch.
2. On flat **stone/grass/dirt** with no potion effects, walk and sprint.
   A few eligible pairs should appear; ordinary movement should stay
   inside the deliberately generous envelope.
3. Jump, fly in Creative, ride a vehicle, swim, or move onto ice/slabs.
   Observe `SPECIAL_MOVEMENT`/`UNSAFE_TERRAIN` and no new outlier
   claim from these unsupported contexts.
4. Teleport with `/tp`, take a zombie hit, or reconnect. These must
   fence samples through `CORRECTION` and restart state at login.
   No automatic actions should occur.
5. Examine TPS loss and plugin-induced movement later under controlled
   conditions before expanding eligibility. Long sample gaps should
   become `UNSTABLE_TICK`, not velocity violations.
6. Do not use any research-outlier counters as training labels or
   moderation evidence.

## Future real detection work

Before a meaningful ground-speed check, build version-specific motion
predictions using client input/packet sequencing, friction and acceleration,
actual block collision shapes, potion/attribute modifiers, momentum,
teleport/velocity delivery reconciliation, and tick-aware network uncertainty.
Then compare against a **labeled legitimate gameplay corpus** and controlled
adversarial testing. Only then should independently reviewed evidence enter
HackerGuardian's staff-investigation engine.

The scope here is intentionally limited to validating that the upstream
observation and eligibility contracts are trustworthy.
