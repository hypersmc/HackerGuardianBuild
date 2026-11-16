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

## Walk/sprint distribution runtime validation (2026-11-16)

The tester provided two in-game `/hg detection packets HypersMC profile`
readings: first after walking, then after sprinting on the test server.

| Profile observation | Walk-only screenshot | After sprinting |
|---|---:|---:|
| Walk retained / session total | 128 / 311 | 128 / 334 |
| Walk p50, p95 (blocks per 50ms) | 0.216, 0.216 | 0.216, 0.216 |
| Walk maximum | 0.218 | 0.216 |
| Sprint retained / session total | 0 / 0 | 128 / 253 |
| Sprint p50, p95 (blocks per 50ms) | n/a | 0.281, 0.281 |
| Sprint maximum | n/a | 0.282 |
| Eligible total | 311 | 587 |
| Sprint transitions excluded | 0 | 2 |
| All other no-decisions | 52 | 65 |

**Consistency:** walk+sprint lifetime group totals match eligible counts in
both screenshots (311 + 0 = 311; 334 + 253 = 587). Each group correctly
retains at most the last 128 entries. These are *server-observed,
time-normalized, opt-in research samples*, not client prediction errors.

The walking median 0.216 blocks/50ms and sprinting median 0.281
blocks/50ms correspond closely to the commonly cited default vanilla
walk/sprint rates of about 4.317 and 5.612 blocks/s at 20 TPS (about
0.2159 and 0.2806 blocks/50ms). The observed sprint/walk ratio is roughly
1.30. Reference: https://minecraft.wiki/w/The_player

**Result:** pass for a single-server walking/sprinting *profile collection*
smoke test. Both mode labels populated independently, with two transition
skips and no cross-contamination evident in these aggregates. The screenshot
does not validate latency, changing TPS, acceleration transitions,
nonstandard block friction, speed attributes/effects, custom movement
plugins or multi-version support. Near-identical p50/p95 values reflect the
retained steady-state samples and do **not** justify using either statistic
as a speed limit or cheat classification cutoff.

Next work: assess eligible speed during starts, stops and changes of
direction, record ground friction/collision details and separate sustained
steady-state motion from acceleration transients. Keep all observations
read-only until protocol/version-aware physics prediction has independent
false-positive validation.

## Start/stop/turn and apparent slowdown dynamics (2026-11-16)

A new, separately requested **research-only** staff view is available under
the existing opt-in ground probe:

```text
/hg detection packets HypersMC dyn
```

It reports counts of **start, stop, turning, acceleration, deceleration and
steady** transitions observed in consecutive pairs of eligible server-side
horizontal displacement vectors (three server position samples). It also
shows the median/p95 *absolute change in displacement speed* (blocks/50ms)
and a **slowdown retention ratio**. Statistics are bounded to the most
recent 128 values per metric and computed when staff request a snapshot.

The classification uses intentionally provisional descriptive thresholds:
0.025 blocks/50ms to consider a player nearly still; 0.075 blocks/50ms as
moving; 0.025 blocks/50ms speed change for acceleration/deceleration; and
35 degrees or more as a turn. **These are research categories, not
authoritative Minecraft physics constants or anti-cheat thresholds.**

The slowdown retention ratio is defined as the newer horizontal speed
divided by the older speed during an observed slowdown or stop. It is
*not a friction coefficient*. The system has **no authenticated information
about which keys the player is pressing or releasing**, and a slowdown
could be braking, changing direction, hitting an obstruction, or simply
settling after acceleration. A future friction estimator must account for
player inputs, block-specific friction, server/client simulation timing,
collision geometry and external momentum first.

### Continuity safeguards

- No dynamics transition is recorded unless **two successive eligible
  ground-displacement pairs** have been measured. Any `NO_CLIENT_DATA`,
  teleport, knockback, respawn, fluid, airborne movement, unsupported
  collision context or lag gap breaks the chain.
- The same world, correction epoch, sprint state and supported full-block
  **material** must be consistent. If a pair spans two unlike or unknown
  materials, the movement distribution can still count it if otherwise
  eligible, but the directional/retention study skips it.
- Sprint toggles are excluded rather than misclassified as acceleration.
  A stationary or nearly stopped player might not send client position
  packets: missing stop samples are expected, not suspicious.
- All samples are per player, memory-only, independent of detection
  scores, ML labels, reports, replay triggers or punishment actions.

### Recommended single-account test

1. In Survival on an extended uniform `STONE` platform, start walking from
   a stop, move steadily for several seconds, then release movement keys.
   Inspect `/hg detection packets HypersMC dyn`. Start, steady or slowing
   counts may increase depending on what client packets were observed.
2. Repeat with sprinting (without jumping), make several sharp 90-degree
   turns, and inspect the same view. The turn count should increase when
   adjacent eligible ground vectors form large heading changes.
3. Run `... profile` alongside `... dyn` to compare the settled walk/sprint
   speed populations to changes during acceleration.
4. Switch to Creative flight, move across dirt paths/slabs, teleport, then
   take zombie knockback. These must never bridge the dynamics timeline.
   Stats from *before* each event remain in the rolling session history.
5. Try an ordinary mixed-material path and observe that the dynamics
   continuity guard stops mixing different ground blocks.
6. Quit and rejoin; in-memory counters should restart. Test more versions
   and realistic server lag before treating any categorization as reliable.

**Validation status:** code and pure Java regression tests added; this
dynamic classification still needs a live Paper/ProtocolLib retest and
independent evaluation of input timing. The default chat view remains
five lines. No automatic detection or enforcement is connected.

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
