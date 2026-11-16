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

## Packet residual distributions by ground movement mode (2026-11-16)

The packet-driven research model now keeps **separate rolling
distributions of the required unknown-input residual** for server-observed
walking and sprinting, without using the older Bukkit displacement
`profile`. The new optional view is:

```text
/hg detection packets HypersMC res
```

Equivalent suffixes: `residuals`, `predstats`, `predictstats`.

Each accepted client packet triple contributes once to exactly one mode.
Sprint state must be unchanged across the triple by the existing
`sameContext` checks; mixed-state, correction-crossing, airborne,
invalid-claim, unknown-terrain, stale server tick or ambiguous packet
timing cases are **not sampled into either distribution**.

The observed motion is calculated from decoded **incoming client position
packets**, not the Bukkit server-position snapshots. For each accepted
triple, using horizontal packet-to-packet displacement vectors:

```text
center = ground_drag * previous_packet_displacement
residual = magnitude(current_packet_displacement - center)
radius = max_unknown_input_acceleration + uncertainty
outside_excess = max(0, residual - radius)
```

`residual` is the magnitude of the **player-input contribution that the
provisional zero-input drag model needs to explain**. Calling it
"prediction error" or treating it as cheating is misleading: authentic
movement input is deliberately **unknown**. The `outside_excess`
measurement represents only how far a sample falls outside the chosen
research envelope (if any), not an authoritative Minecraft violation.

- **Walk/Sprint `n=retained/session_total`:** number of eligible
  client-packet triples in that mode, limited to the most recent **128**
  per group. Each group is a constant-memory ring of primitive values.
- **`r50`, `r95`, `max`:** nearest-rank median, 95th percentile,
  and maximum residual in the *retained* window, measured in blocks per
  accepted packet interval. Neither percentile nor maximum is a threshold.
- **`Outside W/S`:** cumulative session counts of modeled triples
  beyond the deliberately loose envelope for each observed mode.
- **`peak excess`:** maximum excess **among retained values** in each
  mode, not the lifetime maximum; a previous outlier can age out.
- **`no-decision`:** cumulative packets whose context/timing was
  unsuitable or which were needed only to warm up a triple. These are
  not absent or discarded from transport diagnostics.

No per-packet sorting, persistent storage, full coordinates in the
distribution history, event journal writes, ML training or punishment
paths are added. The statistics are generated on demand when staff request
the view. A player disconnect clears the session profile.

### Single-player runtime protocol

1. Enable the existing opt-in `packet_reachability` research switch.
   On a large, uniform **STONE** platform in Survival, walk steadily
   without jumping for 10–15 seconds.
2. Run `... predict` and `... res`. Walk `n` should increase,
   while Sprint `n` stays zero unless earlier sprint data exists.
3. Sprint steadily, no jumping; compare both views. Sprint `n` should
   now increase separately while the walking retained profile remains
   stable, apart from any intervening walking observations.
4. Switch walk/sprint repeatedly, then jump, teleport, take knockback,
   enter Creative and move onto slabs/other unsupported ground. The
   underlying `predict` no-decision/guard checks must still apply, and
   unsupported intervals must never add residual measurements.
5. Reconnect to reset the session counters. Check that the retained
   window stays at 128 while lifetime counts continue to grow.
6. Later collect multiple legitimate gameplay sessions, jitter/TPS
   conditions and Minecraft versions. The observed p95 may describe
   selection bias from the strict eligibility filter rather than
   general Minecraft movement physics.

**Status:** code and unit tests committed; CI and live testing required.
This is distribution instrumentation, **not** verification of the
research model's acceleration constants or a detection accuracy claim.

## First live walk/sprint client-packet residual profile (2026-11-16)

The tester supplied two `/hg detection packets HypersMC res` screenshots,
identifying the **top as the walking test** and the bottom as the reading
**after sprinting** on the same session:

| Counter | Walking snapshot | Later snapshot |
|---|---:|---:|
| Walk retained / total packet triples | 128 / 256 | 128 / 261 |
| Walk residual r50, r95 | 0.098, 0.098 | 0.098, 0.098 |
| Walk residual maximum | 0.098 | 0.127 |
| Sprint retained / total packet triples | 0 / 0 | 128 / 261 |
| Sprint residual r50, r95, maximum | n/a | 0.127, 0.127, 0.127 |
| Cumulative research outside W / S | 0 / 0 | 0 / 0 |
| Retained peak research excess W / S | 0 / n/a | 0 / 0 |

The rolling rings correctly stopped at 128 entries while session totals
continued increasing. Between screenshots the walk session total increased
by only **5** while sprint gained **261** samples. This confirms the
basic walk/sprint data split in a real client-packet collection test.
The increase in the walking **maximum** from 0.098 to 0.127 warrants
investigation around mode transitions and server sprint-state timing;
it is not evidence of cheating or proof of a labeling bug.

For **steady-state** packet displacement `v`, the experimental disk
center implies `residual = (1 - 0.546) * v` when velocity direction and
magnitude remain unchanged. Using earlier Bukkit-observed steady speeds
as a rough reference gives `0.216 * 0.454 = 0.0981` for walking and
`0.281 * 0.454 = 0.1276` for sprinting, close to these recorded
client-packet residuals.

**Result:** pass for the **packet-derived residual distribution collection**
smoke test and bounded walk/sprint grouping. This agreement is mainly
an internal consistency check: the residual is **defined using the
assumed drag** and does not independently validate the drag coefficient,
maximum acceleration, server/client tick correspondence or cheating
detection accuracy. Research outside counts of zero are unsurprising
under the permissive 0.2274-block input-plus-uncertainty radius.

**Next:** explicitly study acceleration and deceleration transitions,
turns, and the rare high walking residual after sprinting; capture
no-decision timing explanations while still making no enforcement
decisions. Do not treat any retained percentile as a violation threshold.

## Client-packet acceleration and sprint-switch transition study (2026-11-16)

The first real residual profile showed walking `r50/r95=0.098`, then
sprinting `r50/r95=0.127`. After sprinting, the **rolling walking
maximum** increased to `0.127` while its median stayed `0.098`.
This is consistent with transient momentum still being observed after the
server's sprint flag switches off, but the snapshots do not prove whether
that was the exact cause. The sprint flag is **Bukkit-sampled server
state**, not a decoded key-down/key-up event.

The packet predictor now keeps **post-switch residuals separate** from
the rolling steady walking/sprinting distribution. The new, read-only
command:

```text
/hg detection packets HypersMC trans
```

Aliases: `transitions`, `switches`, `switch`.

The six-line view shows:

- Server-observed **walk→sprint** and **sprint→walk** flag changes
  detected between consecutive genuine client position-packet claims,
  on the same world, correction epoch and ground block material.
- Count of modeled packet triples attributed to a **six server-tick-hint
  window** following a sprint-state change. The window is a deliberate
  *research convention*; tick hints are not authenticated client ticks.
- Phase counts for **accelerating, slowing, turning and steady**,
  classified from changes in two successive decoded client displacement
  vectors. Thresholds (0.025 block speed change and 35-degree turning
  angle) are provisional **research labels**, not vanilla movement
  constraints or detection limits.
- Separate bounded post-switch residual distributions for entering walk
  versus entering sprint, with per-mode counts, rolling p95 and max.
  Each tail retains no more than **128** values; lifetime counts continue.

The existing `... res` view now reports **steady-mode residuals only**.
The new excluded-tail counters on that view explain why adding its two
mode sample counts may yield a lower total than `... predict modeled`.
The underlying `modeled`, `outside research` and `no-decision`
counters and the original conservative reachable disk are **unchanged**;
only the grouping of research telemetry differs.

### Critical provenance and no-decision rules

A sprint-state flip breaks the active three-packet reachability chain.
The new mode needs normal warmup before any triple is modeled. Invalid
client ground claims, missing or stale server sample context, unsupported
terrain/game mode, unusual vertical displacement, teleport/knockback,
cross-world/material changes, duplicate or skipped server tick hints all
preserve the existing **no-decision** behavior. Such uncertainty also
**aborts** any unfinished post-switch attribution window.

A server-observed sprint flag change is not proof that the player
pressed or released a sprint key. A high walking residual immediately
after such a change is not an anomaly by itself, and a low residual is
not evidence of legitimacy. The model still lacks true client input
state, packet-to-client-tick equivalence and full version-aware physics
parameters. Neither tail nor phase statistics feed reports, alerts,
punishments, ML labels or evidence journaling.

### Retest protocol

1. In Survival on uniform flat `STONE`, walk steadily and run
   `/hg detection packets HypersMC res` (steady Walk population).
2. Sprint steadily; run `... res` and `... trans`. Expect a
   walk→sprint flag event, some post-switch sprint samples, and more
   steady sprint observations after the initial window.
3. Release sprint **while continuing to walk**, then check both views.
   The new `sprint→walk` tail should collect residuals while steady
   walking p95/max should no longer be inflated *by the attributed
   post-switch packets*. Verify the distinction rather than assuming
   all high values must disappear.
4. Alternate start/stop, direction changes and several sharp turns.
   `trans` should accumulate phase observations from real decoded
   client position packets where three consecutive packet claims and
   eligible server tick hints exist.
5. Teleport, jump, enter Creative, change block material or take
   zombie knockback. Inspect `predict`, `res` and `trans`:
   uncertain packets must not contribute to new residual/phase samples.
6. Reconnect to verify session reset. Test slow ticks/network jitter
   separately. All measurements are observational and non-actionable.

**Status:** committed as research-only, subject to CI and runtime testing.

## Live packet transition and residual-grouping retest: passed (2026-11-16)

The tester provided four screenshots of the same Survival session, with
two walk/sprint/switch-back sequences (first two screenshots, then a
repeat). The screenshots show:

| Observation | Reading 1 | Reading 2 | Reading 3 | Reading 4 |
|---|---:|---:|---:|---:|
| Walk residual retained/total | 128/165 | 128/337 | 128/515 | 128/679 |
| Walk r50/r95/max | .098/.098/.098 | .098/.098/.098 | .098/.098/.098 | .098/.098/.098 |
| Sprint residual retained/total | 0/0 | 128/159 | 128/159 | 128/324 |
| Sprint r50/r95/max | n/a | .127/.127/.127 | .127/.127/.127 | .127/.127/.127 |
| Walk→sprint observed switches | 1 | 1 | 2 | 2 |
| Sprint→walk observed switches | 1 | 1 | 2 | 2 |
| Post-switch tagged packets | 9 | 9 | 18 | 18 |
| After sprint→walk retained/session total | 4/4 | 4/4 | 8/8 | 8/8 |
| After sprint→walk p95/max | 0.000/0.000 | 0.000/0.000 | 0.000/0.000 | 0.000/0.000 |
| After walk→sprint retained/session total | 5/5 | 5/5 | 10/10 | 10/10 |
| After walk→sprint p95/max | .127/.127 | .127/.127 | .127/.127 | .127/.127 |
| Accelerating/slow/turn/steady | 2/4/0/328 | 3/7/0/495 | 4/11/0/842 | 5/14/0/1014 |
| Outside research envelope W/S | 0/0 | 0/0 | 0/0 | 0/0 |

**Result:** first real-server packet transition grouping regression
**passed**. The number of observed sprint switches and tagged windows
doubled on repetition, and the steady walking residual maximum stayed
at **0.098** rather than rising to the earlier **0.127**; sprinting
steady residual stayed at 0.127. The new bounded post-switch rings
recorded 4 then 8 walking-tail samples and 5 then 10 sprinting-tail
samples. This validates the observed *separation* of the research
populations on this one test server, not the accuracy of the assumed
physics constants.

**Important: a 0.000 residual is not synonymous with no movement.**
The model defines residual as
`|v_current - 0.546 * v_previous|`. A packet sequence following the
assumed drag with little input, including decaying motion, may produce
near-zero residual while the player is moving. Stationarity could also
produce zero. These screenshots do not distinguish them. They are not
proof of key release, genuine friction, or a perfect prediction.
They do show a systematic contrast between the two switch directions.

The phase counters increased as the session progressed; turning
remained zero. A separate controlled turning test is needed before
the turn classifier can be called runtime-validated. These phases
measure changes in **decoded client packet displacement**, with the
sprint-mode label separately sampled via Bukkit; they are not decoded
client input commands.

Suggested next instrumentation: in each post-switch window, report
**the distribution of actual packet horizontal displacement** alongside
the residual. That would distinguish stationary packets from
friction-like coasting without claiming to know the keyboard state.
Then test sprint-key release while deliberately holding forward versus
releasing both forward and sprint, including controlled 90-degree
turns. Never use either outcome for moderation.

## Post-switch packet displacement: separating idle from drag-like motion (2026-11-17)

The first repeated live walk/sprint tests showed **zero residual** (at
three-decimal precision) in the post-sprint-to-walk window, but this
cannot distinguish true stationary movement from motion that follows
the provisional drag equation. This increment adds **actual horizontal
displacement from the same accepted client packet triples**, grouped
with the existing residuals (no new Bukkit movement-position source).

The existing compact admin command remains:

```text
/hg detection packets HypersMC trans
```

The final two lines are now shortened into paired stats for the
post-switch-to-walk (`S>W`) and post-switch-to-sprint (`W>S`) windows:

```text
S>W n8 r95 0.000 d95 0.032 near0 2 drag~ 6
W>S n10 r95 0.127 d95 0.281 near0 0 drag~ 0
```

*Illustrative numbers above, not the test server's measurements.*

- **`n`**: cumulative accepted modeled packet triples tagged within
  that switch direction. The retained distributions contain at most
  the latest **128** per direction. Statistics are read-time only.
- **`r95`**: 95th-percentile required *unknown-input residual*
  `|v_current - 0.546 * v_previous|` among the retained triples.
- **`d95`**: 95th-percentile **actual decoded client-packet XZ
  displacement magnitude** `|v_current|` among those same retained
  triples, in **blocks per accepted packet interval**, not blocks/50ms.
- **`near0`**: number of retained intervals with actual displacement
  at most **0.002 blocks**. This is a measured near-zero packet delta,
  *not* a guaranteed client key-release or real-world rest state.
- **`drag~`**: number of retained *non-near-zero* intervals where
  the provisional residual is at most **0.005 blocks**. This identifies
  observed movement approximately following `0.546*v_previous`,
  *not* a verified friction event or a key-up declaration.

The `near0` and `drag~` thresholds are purely **research labeling
conventions**, not Minecraft physics guarantees or Speed/Fly thresholds.
Residual and displacement are collected together only from eligible,
same-ground, same-epoch, same-sprint-state, correctly ordered incoming
client-position packet triples inside the existing six
**server-tick-hint** post-switch window. No change to that window or
the original prediction/no-decision/correction behavior.

A fully stationary player might stop sending position packets: that
absence never becomes a fabricated zero-displacement packet sample.
Conversely, a true packet delta of zero with near-zero residual is
different from a positive packet delta with near-zero residual, even
though both display `r95 0.000`.

### Controlled follow-up

1. In Survival on a long level STONE platform, sprint steadily,
   **release sprint but continue holding forward** for several seconds;
   read `trans` and `res` and note the changes to `S>W`.
2. In the same session, sprint again, then **release both sprint and
   forward**, without jumping, and compare the new `S>W` samples.
   Contrast `d95`, `near0`, and `drag~`. Avoid declaring either
   behavior a verified client key state.
3. Walk/sprint in a straight line, then make distinct **90-degree turns**
   while continuing to move on the same uniform ground. The turn
   counter should begin recording eligible abrupt direction changes,
   but zero can still result from gradual turning or missed packets.
4. Jump, teleport, switch to Creative, take knockback, or cross unsupported
   materials; verify the existing packet predictor exclusions are
   unchanged and no new post-switch displacement samples cross
   those boundaries.
5. Quit/rejoin to verify that all bounded session counters restart.

**Validation status:** Java regression fixtures distinguish genuinely
near-stationary packet deltas from positive, drag-like displacement
despite nearly identical residuals. A new live Paper test is still
required to establish how often either occurs in real gameplay.
All observations remain opt-in, in-memory and non-enforcing.

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
