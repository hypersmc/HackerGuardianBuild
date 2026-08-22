# HackerGuardian deterministic checks

Deterministic checks are the non-ML side of Detection v2. They run whether or not supervised ML, population normality, or Learning Mode is enabled.

They follow the same evidence-first rule as the rest of Detection v2:

```text
Bukkit event
    |
    v
deterministic check
    |
    v
recent event evidence
    |
    +---- BehaviorSnapshot detector findings
    |
    v
DetectionAssessment
    |
    v
future policy / replay / staff action
```

The checks in this foundation do **not** directly punish players. A physically implausible observation is recorded as strong evidence; policy remains a separate layer.

## Evidence strength

`DetectionFinding` now carries a semantic `EvidenceStrength` in addition to numeric score/reliability:

- `HARD` - a physics/protocol invariant is exceeded far enough that there is essentially no normal vanilla explanation;
- `STRONG` - deterministic evidence with deliberately conservative tolerances, but server/plugin edge cases can still exist;
- `SOFT` - supporting evidence that should normally be corroborated;
- `HEURISTIC` - statistical/model/behavioral evidence where unusual is not itself proof of cheating.

Existing detectors remain source-compatible and default to `HEURISTIC` until explicitly classified.

## Current checks

### `mining.fast-break`

Uses Bukkit `Block#getBreakSpeed(Player)`, which already accounts for the block, held tool, enchantments, potion effects, water/airborne state and other server-side break modifiers.

HackerGuardian records the initial `BlockDamageEvent`, compares it with the successful `BlockBreakEvent`, and calculates a deliberately conservative lower-bound break duration. One progress tick is removed before comparison and the configured ratio/tolerance plus a small latency allowance are applied before a finding is possible.

Very fast blocks are ignored by default because they are poor fast-break evidence.

### `mining.multi-break`

Tracks successful blocks that individually require meaningful mining time. A finding is produced when several such blocks all complete inside a short server-side window that cannot accommodate their individual break durations.

Instant-mine blocks are excluded, avoiding the common false positive where a legitimate high-tier tool rapidly clears soft blocks.

### `mining.reach` / `world.place-reach`

Reach is measured from the player's eye to the **closest point on the block bounding box**, not to the block center. Defaults are intentionally more generous than ordinary vanilla interaction range.

A moderate excess is `STRONG`; an additional configurable excess is classified `HARD`.

### `mining.through-wall` / `world.place-through-wall`

The check samples seven rays from the player's eye to the target block bounding box (center plus face samples). A finding is produced only when every sampled ray collides with another block first.

This is intentionally more conservative than a single center ray, which can misclassify legitimate edge/corner interaction.

## Event buffering

Deterministic checks are event-driven while the existing DetectionEngine is snapshot-driven. `DeterministicEvidenceBuffer` bridges the two without creating a second assessment system.

Repeated events from the same check inside one assessment window are collapsed to the strongest finding and annotated with `recent_events`. This prevents a noisy check from raising aggregate risk merely by producing many duplicate findings.

## Configuration

All settings live below:

```yaml
DetectionV2:
  deterministic:
```

Each check family can be disabled independently. The defaults are intentionally conservative and should be runtime-tested before any future policy is allowed to automate actions from them.
