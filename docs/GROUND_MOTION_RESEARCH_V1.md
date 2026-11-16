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
  (`STONE`, `DIRT`, `GRASS_BLOCK`, `COBBLESTONE`, `DEEPSLATE`,
  `STONE_BRICKS`, common vanilla planks, `ANDESITE`, `DIORITE`,
  `GRANITE`, `SANDSTONE`);
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

## Survival retest: zero eligible samples (2026-11-16)

The tester ran the probe in Survival and saw **0 eligible / 1037
no-decision samples**. Repeated last statuses included `UNSAFE_TERRAIN`
and `NO_CLIENT_DATA`; research outliers remained 0.

This is **not proof of legitimate or illegitimate movement**, and does not
mean the packet observer itself failed. An eligible sample must pass a chain
of conservative conditions. The original allowlist (only five ground block
types) and strict full-block Y test could exclude normal-looking surfaces
such as stone-brick paths, wood floors, dirt paths, partial blocks, and
players still granted flight permission by another plugin. The screenshot
alone cannot identify which of these applied.

To diagnose rather than blindly enable all surfaces:

- Extend the allowlist modestly to ordinary, common full blocks with
  conventional ground friction. Keep partial blocks, ice, slime, honey,
  dirt paths and water outside this first version.
- Store **cumulative terrain skips, missing-client skips, and special
  movement skips**, not just the last status.
- Preserve the **last precise terrain explanation**, such as
  `feet=DIRT_PATH`, `below=UNKNOWN_BLOCK`, `feetY-offset=0.938`
  or `flight permission enabled`, even when the latest tick has
  `NO_CLIENT_DATA`.
- Keep the `probe` view at six lines. The **Surface clue** is historical
  and must not be mistaken for the current block if the player has moved.

For the next test, use `/hg detection packets HypersMC probe` in
Survival while walking on a large level platform of full `STONE` or
`GRASS_BLOCK` blocks. If eligible remains 0, read the surface clue and
the cumulative skip counters. A result like `feet=DIRT_PATH` is
correctly ineligible, while `flight permission enabled` means the
player still has an altered movement capability in Survival.

**No default enablement, detection scoring, report events or enforcement
was changed.** CI and a fresh Paper runtime retest are needed to confirm
the updated explanations and eligibility.

## Survival eligibility regression retest: passed (2026-11-16)

After the terrain-reason and modest ordinary-full-block allowlist changes,
the tester provided three in-game `probe` screenshots:

| Tick samples | Eligible | No-decision | Terrain skips | No-client | Special | Research outliers |
|---:|---:|---:|---:|---:|---:|---:|
| 389 | 4 | 385 | 23 | 154 | 181 | 0 |
| 471 | 43 | 428 | 29 | 187 | 205 | 0 |
| 534 | 72 | 462 | 29 | 219 | 217 | 0 |

The first screenshot showed a retained `Surface clue: below=GRAVEL`;
the later screenshots showed `Surface clue: below=SAND`. Both remain
outside the narrow allowlist. These are *historical exclusion clues*, not
proof of which block the tester was standing on when each command ran.

**Result: pass for the single-server Survival eligibility regression.**
The previous test had 0 eligible out of 1,037 samples; this test showed
eligible measurements increasing from 4 to 43 to 72. The probe can now
produce eligible grounded samples while still returning no-decision on
unsupported terrain or missing input.

Zero research outliers is expected to be common with the very permissive
envelope. It does **not** validate detection effectiveness, rule out
false positives on other scenarios, or prove any client is legitimate.
The screenshot does not independently identify the exact evaluated
block materials or establish per-version physics accuracy.

Next: inspect actual eligible displacement distributions under
walking/sprinting, improve observational coverage carefully, and add
controlled abnormal-motion fixtures **without** linking the probe to
punishments, reports, or ML labels.

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

## Walking and sprinting measurement profile (2026-11-16)

After the passing Survival eligibility retest, a second **descriptive-only**
measurement view was added, without changing the existing `probe` view:

```text
/hg detection packets HypersMC profile
```

This shows a short profile of **eligible server-observed horizontal movement**
separated by Bukkit's sprinting flag:

- **Walk**: the player reported `isSprinting() == false` at both samples.
- **Sprint**: `isSprinting() == true` at both samples.
- If the sprint flag differs across the pair, mark
  `SPRINT_TRANSITION` (a **no-decision** sample) and exclude it from
  both groups. This is a server state label, not verified client input.
- Each group retains the **most recent 128 eligible observations** in a
  fixed-size in-memory ring. The `n=retained/total` field distinguishes
  current rolling sample size from lifetime observations for the session.
  Disconnecting or disabling the observer clears both groups.
- Measurements are normalized to **blocks per 50 ms** using the monotonic
  elapsed time between successive sampled server ticks. We still reject
  intervals outside 25–125 ms, skipped server samples, recent teleports,
  knockback, invalid movement context, and unsupported ground.
- The view shows the **median (p50), 95th percentile (p95), and maximum**
  among retained samples, using nearest-rank percentiles. With very small
  sample counts, percentiles are not reliable estimates of normal play.

These distributions do **not** use the old 1.2-block research envelope
to decide which eligible observations enter the sample ring. That legacy
heuristic still exists in the optional `probe` view for historical
continuity, but it is not a physics limit, a statistical cutoff, or
a punishment threshold.

**Interpretation constraints:** stationary samples can legitimately record
zero displacement; a player can remain sprint-flagged while slowing down;
a server tick is not an authenticated client simulation tick; and the
surface/correction filters create selection bias. Therefore these p50/p95
numbers do not establish a universal walking or sprinting distribution,
and must **not** be used to train ML or flag other players.

### Manual comparison protocol

1. Keep ground-probe observation enabled. In Survival on a large level
   `STONE` or `GRASS_BLOCK` platform, walk steadily for 10–20 seconds;
   run `... profile` and inspect **Walk n**.
2. Sprint steadily on the same surface without jumping; inspect
   **Sprint n**. If a mode remains at zero, confirm the server is actually
   reporting its sprint flag (or collect more consecutive eligible samples).
3. Switch repeatedly between walking and sprinting. Confirm that
   **transition skips** may increase and neither group is mixed across
   a single toggle.
4. Use `... probe` to identify any excess `NO_CLIENT_DATA`,
   `UNSAFE_TERRAIN` or `SPECIAL_MOVEMENT` exclusions.
5. Fly in Creative, teleport, take knockback, jump, or stand on unsupported
   materials: no new **eligible** values should be recorded from those
   contexts. The rolling profile remains available for retrospective study.
6. Reconnect and verify both sample histories restart at zero.

**Status:** code + pure Java unit tests added; the precise behavior of
Bukkit sprint state, real-world sample bias, and multiplayer performance
still require in-game verification. No scoring, bans, alerts, database
writes, web frontend, reports or replay integration were added.

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
