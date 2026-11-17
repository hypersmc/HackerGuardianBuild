# HackerGuardian 2.0 — Guided packet course (research v1)

The guided course replaces repeated, manual `timing`, `runs`, and `heading` command screenshots with **one opt-in run**. It does not add a check, alter packet eligibility, change physics constants, or generate cheating evidence.

## Commands

- `/hg detection course start` — Survival player starts a six-leg particle-only course on an available flat, loaded platform.
- `/hg detection course stop` — Abort while keeping a partial in-memory report.
- `/hg detection course report` — Show the most recent report for this online player.

The course has three walking legs (straight, 90-degree turn, 90-degree turn) and three sprinting legs (straight, 90-degree turn, 90-degree turn). The third-to-fourth leg remains straight to observe walk-to-sprint transition. Geometry uses the player's approximate cardinal facing direction and picks whichever of the two mirrored routes passes flat-surface and loaded-chunk checks. It **never writes world blocks**; the particles are shown only to the player running the test.

Start/end are checkpoint-driven, bounded to three minutes. A few small navigation errors do not prevent completing the course. Large deviations may stop the course with a partial report, without punishment.

## Packet evidence and geometry

The measured positions come exclusively from decoded incoming client POSITION/POSITION_LOOK packets. Bukkit only supplies ground-context hints, segment checkpoints, and the server-observed sprint state. A server-tick hint **is not an authenticated client tick**; a camera yaw change **is not a movement heading**.

Every segment has independent modeled/rejected packet totals, timing counts, same-mode/mismatched-mode observations, modeled-run lengths, and inside-segment heading samples.

**New:** each phase also reports a count of route-position samples outside a 2.3-block corridor, reverse-direction movement samples, and a route-quality classification.

To avoid losing the turn at the phase boundary, the course retains at most 64 recent decoded, grounded client positions and calculates signed directional displacement on both sides of the checkpoint. The corner view is **descriptive raw-position research** — intentionally separate from the stricter eligibility-gated reachability model. It reports expected LEFT/RIGHT, observed LEFT/RIGHT/STRAIGHT/UNKNOWN, measured signed angle (when available), and the number of opposite-side motion steps. If a player begins turning the wrong way, turns back, then completes the checkpoint, the report can flag a DETOUR rather than silently labeling it clean. A course that only has camera rotation with no position change will not manufacture a 90-degree corner.

A correction epoch or explicit server teleport/velocity correction fences the cross-boundary calculation. Missing usable before/after motion yields UNMEASURED rather than an invented turn.

## Quality labels

- **CLEAN** — Enough samples, no material route deviations or sprint-mode mismatches, and (if expected) a measurable turn in the expected direction.
- **MIXED_MODE** — The server-observed sprint state did not consistently match the intended walk/sprint phase.
- **DEVIATION** — Reversed motion, repeated off-route samples, an observed wrong-direction / atypical turn, or opposite-side movement around the checkpoint.
- **LIMITED** — Too few modeled packets or insufficient motion to measure the corner.

These are **test-quality classifications**, not indications of cheating or trusted proof of specific movement keys. A left/right label indicates the geometry of reported position displacement in X/Z, not the direction the user's camera faced.

## Validation

Tests cover signed left and right changes across checkpoints, a wrong-way-then-corrected detour, absent movement, straight walk-to-sprint transitions, corrections, per-phase route deviations, and server-state sprint mismatches. The existing Paper API compatibility matrix and Java/ML suites must remain green before in-game retesting.

## Limitations

- Checkpoints are observed on the Bukkit scheduler, while movement packets arrive on an asynchronous ProtocolLib callback. Phase boundaries are **approximately** synchronized with checkpoint events, not client input frames.
- The per-phase heading percentile is about instantaneous consecutive packet displacement, whereas the corner angle is from aggregated movement **across** a checkpoint. They represent different measurements; low p95 values do not contradict a valid 90-degree path change.
- Heading and route geometry use client-supplied position claims and are not anti-cheat proof. The raw-position buffer and course summary have bounded memory; no raw position history is persisted after a session.
- If a player departs far from the marked course or is teleported, only partial metrics are available.

Nothing in this course feeds violations, staff reports, sanctions, risk scores, or ML labels.
