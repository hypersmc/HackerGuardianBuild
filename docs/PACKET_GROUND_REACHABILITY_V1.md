# Packet-driven horizontal ground reachability v1 — research only

**Status:** implementation under PR #17, requires GitHub CI and real-server
runtime validation. This is not a Speed/Fly detector and makes no cheating
judgments. All counters are session-memory-only. It has no path into
HackerGuardian reports, risk scores, ML training, learning datasets,
notifications, automated setbacks, kicks or bans.

## What is actually packet-derived?

The observed X/Y/Z positions fed to `PacketGroundReachability` are decoded
from genuine **incoming Minecraft Java Edition `POSITION` and
`POSITION_LOOK` client packets**, received through ProtocolLib. The
packet's client-claimed on-ground bit must also decode as `true`.

This is **not** the older Bukkit walking/sprinting `profile` or `dyn`
research: those measure differences between Bukkit-sampled server positions.
Bukkit is still used here, but **only as separately sampled context**:
server-scheduler tick hint, game mode, ordinary full-block support material,
grounded/flying state, potion-effect absence, normal walk speed, no liquids,
velocity/teleport correction boundaries, etc. A client ground bit by itself
does not qualify movement for modeling.

No player Bukkit APIs or world lookups occur on the ProtocolLib packet
thread; immutable sample data is captured once per tick on the Bukkit
scheduler thread and read behind per-session locks.

## Enable separately

Requires ProtocolLib, packet observation and the existing experimental
ground probe enabled. Both nested features are **off by default**:

```yaml
DetectionV2:
  packet_observation:
    enabled: true
    ground_probe:
      enabled: true
      packet_reachability:
        enabled: true
```

After server restart, inspect:

```text
/hg detection packets HypersMC predict
```

The six-line staff view shows: version profile, incoming position packet
count, successfully modeled triples, no-decision count, repeated-tick
ambiguity, correction count, last outcome, and last measured movement
and research-only envelope/excess.

## Physics hypothesis — explicitly not validated

For three distinct, contiguous, **server-scheduler tick-hinted** decoded
position claims, let `v_prev = P[t-1] - P[t-2]` and let
`v_now = P[t] - P[t-1]` (horizontal XZ vectors).

Model a conservative unknown-input displacement disk:

```text
predicted_center = ordinary_ground_drag * v_prev
reachable_radius = maximum_unknown_input_acceleration + uncertainty
residual = length(v_now - predicted_center)
research_outside_if residual > reachable_radius
```

The first registered research profiles use drag `0.546` and an unknown
input acceleration bound `0.1274` blocks per tick, plus a deliberately
generous `0.10` block uncertainty margin. The 0.546 and default
walking/sprinting acceleration magnitudes are consistent with the broad
vanilla description at
https://minecraft.wiki/w/Player#Movement_speed

**These are not separately validated against every client/server patch
or physics modifier.** The input direction is unconstrained: the model
does not assume forward movement or know keys, diagonal inputs or key-up
events. The disk is an intentionally loose *research reachability
hypothesis*, not an exact physics solver. In particular:

- incoming packet observation order is **not** authenticated client
  simulation order;
- a local scheduler tick hint is **not** a client simulation tick;
- network delay, client tick cadence, packet scheduling and server
  processing can misalign the tick-hint mapping;
- server-side state may have already incorporated that packet or may
  reflect an older client input;
- collision geometry, speed attributes, item-use slowdown, equipment,
  jumping, sneak effects, local client motion integration, velocity
  acknowledgments and many server plugins are not modeled.

The profile registry is **explicit per version family**:
`1.20.x` and `1.21.x` have preliminary research constants;
`26.x`, future/unverified and legacy versions are **model-unavailable**.
Successful compilation on a version is not physics verification.

`CONSERVATIVE_REACHABLE` and `OUTSIDE_RESEARCH_ENVELOPE` are **both
non-actionable research telemetry**, never positive/negative cheat verdicts.
An outlier could easily be caused by a wrong timing assumption.

## Strict no-decision cases

Any of these withholds a physical comparison:

- no following available Bukkit server tick sample;
- the last two server samples were not consecutive scheduled ticks,
  or their observation interval is outside 25–125 ms;
- the newest Bukkit sample is older than 125 ms (or has invalid timing);
- any unknown/undecodable position or on-ground bit, or client ground=false;
- the player is in Creative/Spectator, flying, gliding, swimming, in a
  vehicle, on nonstandard or unknown ground, near liquid, or has changed
  speed/effects/permissions;
- teleport pending/unconfirmed, correction recovery, knockback,
  world change, respawn or a changed correction epoch;
- client position packet arrives twice with the same scheduler tick hint,
  or packet tick hints skip a scheduler tick;
- world/ground material/sprint context changes across the three samples;
- vertical displacement exceeds 0.03 blocks over a packet pair or the
  previous horizontal displacement exceeds 0.8 blocks.

The position history is only two previous immutable claims per session.
No growing logs, raw packet retention outside the existing optional bounded
movement buffer, time-sorting, or packet-thread Bukkit calls were added.

## First real in-game smoke tests

1. Enable the explicit `packet_reachability` toggle and restart. In
   Survival, walk steadily on a large flat stone platform. Compare
   `/hg detection packets HypersMC predict` with `... profile`.
   The `predict` number is based on **client packets**, not the
   server-position profile. The `modeled` counter may be much smaller
   than the number of `position packets`.
2. Sprint without jumping and repeat; expect many conservative
   `CONSERVATIVE_REACHABLE` outcomes, but no enforcement. A lack of
   modeled samples is diagnostic, not a failed physics verdict.
3. Stop, start, turn, jump, fly in Creative, change block material and
   run `/tp ~ ~10 ~`. Check last no-decision states; never assume that
   correction/airborne jumps are legitimate physics samples.
4. Take zombie knockback in Survival. Ensure no cross-knockback triplet
   is modeled and no punitive action occurs.
5. Check on a busy/lagged server and with unusual packet timing. Frequent
   `AMBIGUOUS_TICK` or `TICK_GAP` is useful evidence that server tick
   hints cannot safely serve as client simulation ticks; never solve that
   by silently comparing multiple packets as separate physics ticks.
6. Repeat on separately supported game versions before expanding profile
   registry. Retest decoder boolean layout where ProtocolLib/NMS changes.

## First in-game packet-predictor smoke test: airborne movement (2026-11-16)

The tester ran `/hg detection packets HypersMC predict` while jumping
in Survival. Three screenshots of the **same session** showed:

| Position packets | Modeled triples | No-decision | Ambiguous tick | Research outside | Last status |
|---:|---:|---:|---:|---:|---|
| 5 | 0 | 5 | 0 | 0 | TICK_GAP |
| 58 | 11 | 47 | 0 | 0 | TICK_GAP |
| 116 | 16 | 100 | 0 | 0 | UNSUPPORTED_CONTEXT |

The model identified the active profile as `1.20.x`. The correction
counter remained 3 across the three readings, indicating previously
recorded correction boundaries rather than new jump-caused teleports.

**Observed:** the independently decoded client-position stream reached
the predictor and at least 16 research triples qualified during the
session. A mid-jump reading produced `UNSUPPORTED_CONTEXT` rather than
applying the ground model to that observation. The final status describes
*only the most recent packet*, not whether every earlier modeled triple
was grounded; the screenshots cannot independently prove the model's
safety on every airborne transition.

**Important limitation:** only 16 of the first 116 received position
packets formed modeled triples; 100 were no-decisions. Frequent
`TICK_GAP` statuses suggest the server-scheduler-tick hint is a weak
proxy for actual client simulation ticks in this test. No ambiguous-tick
events were recorded, but that does not establish correct client tick
synchronization. Research outliers stayed zero; this is **not** a
detection-accuracy claim.

**Next live test:** on a large flat `STONE` platform, walk steadily in
Survival without jumping for 10–15 seconds and inspect `predict`
several times; repeat sprinting, then compare with `profile`.
Record modeled and no-decision count differences for uninterrupted
ground movement separately from airborne movement. Do not weaken the
tick-sequencing guard merely to increase modeled counts.

## First continuous ground walk/sprint packet comparison (2026-11-16)

The tester supplied two `predict` screenshots, labeled **walking**
(first) and **after sprinting** (second), with cumulative same-session
counters:

| Research counter | After walking | After sprinting | Delta |
|---|---:|---:|---:|
| Incoming decoded-position observations | 414 | 589 | +175 |
| Modeled consecutive client-position triples | 176 | 344 | +168 |
| No-decision observations | 238 | 245 | +7 |
| Ambiguous scheduler-tick hints | 0 | 0 | 0 |
| Correction boundaries | 3 | 3 | 0 |
| Outside research envelope | 0 | 0 | 0 |
| Most recent status | CONSERVATIVE_REACHABLE | CONSERVATIVE_REACHABLE | — |
| Last measured packet displacement | 0.003 blocks | 0.007 blocks | — |
| Last printed drift-plus-radius | 0.231 blocks | 0.235 blocks | — |
| Last research excess | 0.000 | 0.000 | — |

**Result:** pass for a single-server, normal ground-movement packet
*collection and reachability eligibility* smoke test. From the walking
snapshot to the sprinting snapshot, **168 of the next 175** received
position observations (96%) were modeled. This suggests the restrictive
scheduler-tick alignment can be useful under steady ground movement,
unlike the prior jumping test; it does **not** prove that scheduler tick
hints equal client physics ticks.

These are cumulative snapshots, not separate isolated walking and
sprinting datasets; the **96% pertains only to the interval between
screenshots** and may include transition or command-entry movement.
The last displayed displacements of 0.003 and 0.007 blocks are the
*latest modeled interval* and should not be compared with the steady
walking/sprinting median measured by the independent Bukkit profile.
A later command entry or slowing down may explain the small values,
but the screenshots do not prove the exact cause.

Neither `CONSERVATIVE_REACHABLE` nor zero research outliers validates
the unknown-input disk constants or proves client legitimacy. The
research uncertainty is deliberately generous. Next measure
**distributions of packet-coordinate residuals**, separated by observed
ground walk/sprint context and with explicit timing/no-decision
breakdowns. Maintain all correction boundaries and keep outliers
non-actionable until tick synchronization and physics accuracy are
independently validated.

## Next actual physics work

Capture trustworthy per-player packet order and *validated tick equivalence*,
then test a version-aware integrator against a legitimate gameplay corpus.
Input must remain an uncertainty set rather than an invented key stream.
Use independently resolved collision boxes, surface friction/attributes,
vertical movement, jumping, sprint/sneak changes and external velocity
state. Only after robust false-positive evaluation should any
movement prediction be considered for investigation evidence.

This first increment establishes a narrow, auditable *packet-position
prediction research path*, not a production anti-cheat check.
