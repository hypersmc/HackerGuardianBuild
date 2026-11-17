# HackerGuardian 2.0 — Movement physics validation: observation stage

**Scope:** existing movement-development PR #17 only. This stage does not validate an exact Vanilla Minecraft physics simulator, change a reachability coefficient, change an eligibility gate, or trigger enforcement.

## Purpose

The packet reachability model currently evaluates the displacement of three incoming decoded client POSITION / POSITION_LOOK claims on stable, consecutive **server tick hints**, with verified ordinary ground context. It predicts a conservative unknown-input disk:

`center = 0.546 * previous observed packet XZ displacement`

`radius = 0.1274 + 0.1000 = 0.2274 blocks`

`residual = |current packet XZ displacement - center|`

The coefficients are provisional model parameters for version-family 1.20.x / 1.21.x. A received position packet **is not a verified client physics tick**; a ground flag and a server-observed sprint flag are not authenticated controls. The 0.1000 uncertainty margin is deliberately generous and makes the model unsuitable for enforcement until independently calibrated.

The new `PacketPhysicsValidation` observer quantifies **how strongly accepted packets rely on that uncertainty allowance**, instead of labeling everything within the generous disk as proof of correct physics. It receives inputs only **after** the existing `PacketGroundReachability` model has accepted and calculated a triple, so it cannot alter eligibility or outcomes.

## Compact staff command

`/hg detection packets <player> physics`

The compact, five-line view (including header) shows:

- Lifetime modeled sample count divided into `base` (within 0.1274 nominal radius), `margin` (between nominal radius and 0.2274 conservative radius) and `outside` (beyond the provisional radius).
- Straight and turning walk/sprint packet counts, with recent p95 normalized residual `p95r`; `1.0` represents the conservative radius. Percentiles use only the most recent 128 eligible triples **within each category**.
- Separate counts for post-sprint-switch tails, near-zero current XZ displacement, and low-vector motion where an actual direction angle is not reliable.

For classification, a packet triple whose current displacement is at most 0.015 block is `QUIET`; if either displacement vector is under 0.075 block it is `LOW_VECTOR`; otherwise a turn is a heading change >=15 degrees in decoded packet XZ position displacement. A recent server-observed sprint switch is categorized as `POST_SWITCH` before other classifications. These labels describe observed packet motion, not camera rotation or player intent.

## Safety and interpretation

- An `outside` result is a research outlier, **not** a cheating verdict, violation, ML label, or punishment.
- `base` is not a proven Minecraft maximum; `margin` is not a detection threshold. The split merely measures where the provisional disk is being relied upon.
- A low p95 ratio is not proof of calibration. Selection by strict eligibility may systematically exclude difficult real gameplay.
- Packet timing ambiguity, world changes, teleports, velocity corrections, invalid ground claims, non-ordinary surfaces, and unsupported Minecraft versions remain undecidable under the existing gate.
- Groups are bounded (128 normalized residuals per group, constant-time inserts), with lifetime count retention and immutable read-only snapshots. They do not store raw packet coordinates.

## Synthetic differential fixture

Unit tests feed independent, deterministic, bounded-input trajectories from a simple recurrence `v(next)=0.546*v+u`, `|u|<=0.10`, through the **real packet-model entrypoint**. The suite checks that all eligible points remain within the nominal input radius, that normal synthetic turning and sprint-context transitions populate distinct groups, and that an injected displacement spike becomes research-outside without affecting any enforcement system. Additional unit tests verify ratio boundaries, bounded percentile windows, and invalid-number rejection.

**This test verifies consistency with the model's assumed recurrence, not the exact implementation of Vanilla Minecraft physics.** Actual calibration is a later research milestone and requires independent Mojang-version-specific physics references and real observed normal-play samples, including collisions, effects, diverse surfaces, tick irregularity, and lag.

## Next research milestone

Review passive gameplay p95 ratios, especially turning and sprint transition behavior. Correlate low model eligibility with `activity` and `timing` staff views rather than changing thresholds. Add more independent version-specific physics fixtures after understanding baseline observations; consider shadow detection only after false-positive validation.
