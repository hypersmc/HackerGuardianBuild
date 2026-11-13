# HackerGuardian 2.0 engineering roadmap

Status: working plan, November 2026. This is a plan, **not** a claim of detection parity with any competitor.

## Product goal

Build a reliable, server-side Minecraft anti-cheat and staff investigation tool that can be **competitive in selected areas** with mature free and commercial alternatives. We do not need to win every benchmark. We do need independently repeatable evidence that specific checks are effective and safe.

Preserve these design rules:
- Detectors emit **evidence**, not punishments. Scores are not probabilities of cheating.
- Model detections and punishment records must never become self-generated training labels.
- Unusual behavior without corroboration is not proof of cheating.
- Staff workflows are **command-first**, with clickable chat actions where useful. A mandatory staff inventory GUI and the separate web frontend are out of scope.
- Prefer incremental changes, back-compatible data migrations, and a green build over large rewrites.
- Stay within legitimate server-side observation and approved test clients; do not rely on undisclosed competitor code.

## Milestone A - trustworthy operations and case evidence (first)

- [ ] Persist sanitized detection findings without requiring the web API/website switch.
- [ ] Make `/hg reports` a paginated, filtered global queue; preserve existing report commands.
- [ ] Add assignment/claim and release with race-safe updates and an audit trail.
- [ ] Link reports, stored detection events and replay records by explicit evidence references.
- [ ] Audit staff permissions, console access, public announcement flags, and data exposure.
- [ ] Retain/expire evidence deliberately, with bounded asynchronous storage.

**Exit test:** A staff member can find an unassigned report, investigate it from chat/console, review real persisted findings, and trace case actions after restart. All evidence stays available with the website disabled. Both MySQL and PostgreSQL integration tests pass.

## Milestone B - packet-aware observation

- [ ] Define versioned, typed packet/tick observations with monotonic ordering and bounded per-player queues.
- [ ] Record relevant player input, transaction/ack timing, teleports, velocity, collisions, inventory and block state.
- [ ] Add client-version-specific capability gates (no assuming compatibility from successful compilation).
- [ ] Create deterministic, replayable fixtures from legitimate and controlled adversarial sessions.
- [ ] Document Bukkit/ProtocolLib thread boundaries and loss/overflow behavior.

**Exit test:** Same captured session yields the same normalized event stream after replay; packet loss, resends, teleport, version boundaries, high ping and low TPS have coverage.

## Milestone C - movement prediction and mitigation research

- [ ] Implement one narrow movement-physics family first (ground acceleration/friction and vertical motion), with server-state-aware tolerances.
- [ ] Add collision, velocity/knockback, effects, vehicles, fluids and surface-specific physics incrementally.
- [ ] Compare against public, behavior-based baseline cases; measure bypass rate, alert delay and false-positive rate.
- [ ] Prototype reversible, non-punitive mitigation only once prediction is validated.

**Exit test:** Accurate behavior on a reviewed corpus across supported versions and realistic ping/TPS distributions, including plugin-caused legitimate movement. No automatic punishments without a separately reviewed policy.

## Milestone D - combat and world interactions

- [ ] Improve attack reach with player/entity hitboxes, temporal position history and latency compensation.
- [ ] Add rotation, attack timing, aim consistency and combat-state checks as independent evidence.
- [ ] Harden fast-break/multi-break/reach/occlusion for enchantments, minigames and server-side plugins.
- [ ] Test against legitimate high-skill gameplay as well as controlled test cheats.

**Exit test:** Per-check false positives and detection latency are measured and reproducible. Staff can inspect the originating samples and replay context.

## Milestone E - useful ML and case automation

- [ ] Collect deliberate LEGIT/CHEAT sessions and trusted-population normality data with provenance.
- [ ] Hold out **players**, sessions and servers for model evaluation; avoid label leakage.
- [ ] Compare trained models against deterministic-only baselines. Keep ML disabled if it does not improve outcomes.
- [ ] Add a review-only correlation/policy layer: repeated independent findings can suggest replays or staff cases, never unreviewed direct bans.

**Exit test:** Out-of-sample precision/recall, alert rate, drift, cost and inference time are reported from real datasets. Staff can see which sources contributed.

## Milestone F - production hardening

- [ ] Run real Paper/Spigot smoke tests for the declared version range, not just compilation.
- [ ] Performance-test 20 Hz replay and detection at 20/50/100+ players with CPU, RAM, DB throughput and dropped evidence metrics.
- [ ] Fault-inject DB outages, proxy reconnects, restarts, high load and corrupt model/replay files.
- [ ] Publish migration notes, least-privilege permission map, operational diagnostics and rollback procedure.
- [ ] Enable any automated enforcement only after a documented false-positive review and explicit operator opt-in.

## Benchmark discipline

Maintain a labeled evaluation corpus containing legitimate players and controlled cheat scenarios. Track for each release:
- detection coverage by check family and Minecraft version;
- median/p95 detection delay;
- false alerts per 1,000 legitimate player-hours (with confidence bounds);
- performance overhead per player and storage growth;
- fraction of findings with reproducible replay/evidence provenance;
- operator workflow duration (find, triage, close one case).

A check is competitive only when the same test workload and metric definition support the comparison. Hypixel and other closed systems are operational reference points, **not** sources for unverifiable algorithmic rankings.

## First implementation increment: Milestone A1

1. Decouple the persistent detection journal from `Settings.UseWebsiteFunction`.
2. Expose a global, status-filtered report queue with `/hg reports [inbox [status] [page]]`.
3. Test the queue against MySQL and PostgreSQL; verify the existing report-by-player commands remain intact.
4. Keep the rest of the staff workflow, assignment, GUI and the website unchanged.
