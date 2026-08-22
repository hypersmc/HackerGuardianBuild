# HackerGuardian Learning Mode v2 and population normality

## Why this exists

HackerGuardian should not need a hand-labeled example of every cheat in existence. Learning Mode v2 builds a large, high-confidence picture of **normal Minecraft behavior** from a deliberately trusted player population, then trains a population anomaly model that asks:

> How unusual is this behavior compared with the legitimate-heavy population HackerGuardian has observed over time?

An anomaly score is evidence, not a cheat probability and never a direct punishment decision.

## Trust is explicit

Learning Mode is disabled by default. When enabled, a player contributes automatic candidate-normality data only when either:

- they have the configured `hg.learning.trusted` permission; or
- their UUID is explicitly present in `DetectionV2.learning.trusted_uuids`.

The recommended deployment is a small/high-confidence permission group managed with the server's normal permission system.

A trusted player is **not assumed infallible**. Every row is written as candidate data with provenance and a future eligibility timestamp. There is deliberately no code path from a detection finding to a training label.

## Player notification

With `notify_players: true`, trusted players receive a message when they join explaining that Learning Mode is collecting gameplay telemetry for a normal-behavior model. If probes are enabled, the message also explains that occasional client-side test entities may appear and that the tests do not punish the player.

## Quarantine and the trust manifest

Automatic samples are written to:

```text
plugins/HackerGuardian/ml/normality/candidate-behavior-v1.csv
```

Each row contains:

- FeatureSchemaV1 id and values;
- capture timestamp;
- UUIDv7 session id;
- player UUID/name;
- trust source;
- world/window metadata;
- `eligible_after_ms`.

The default quarantine is 14 days. A row cannot be used by the normality trainer until its quarantine timestamp has passed.

Learning state is persisted separately and a current manifest is generated at:

```text
plugins/HackerGuardian/ml/normality/trust-manifest-v1.csv
```

The trainer requires both conditions:

1. the row's quarantine has expired; and
2. the current manifest still says that player is trusted and has reached the configured minimum baseline hours.

This means old candidate files do not silently become ground truth just because they were captured once. Revoking trust excludes that player from future training runs without rewriting the large dataset.

## Population balance

Collection uses a fixed per-player sampling interval rather than event volume. The offline trainer additionally performs bounded reservoir sampling per player (`--max-samples-per-player`) so a player with thousands of hours cannot become most of the definition of normal simply because they were online longest.

## Behavioral probes

After a configurable amount of accumulated trusted play time, Learning Mode may perform a short client-side fake-player probe.

The probe:

- uses a UUIDv7 probe id and a separate UUIDv7 fake entity identity;
- is sent only to the target client using ProtocolLib;
- does not create a real Bukkit/world entity;
- does not collide, deal damage, or mutate the world;
- cancels `USE_ENTITY` packets aimed at its synthetic entity id;
- is removed automatically after a short duration;
- is avoided during recent combat by default;
- has a randomized side/rear angle, distance, and cooldown jitter.

The probe result records reaction measurements such as:

- initial angular offset and distance;
- first meaningful camera rotation latency;
- first time the view enters the configured field-of-view threshold;
- first swing latency;
- first direct attack packet latency;
- minimum crosshair angle reached;
- maximum observed rotation rate;
- ping/TPS context.

Attacking a probe is **not** proof of cheating. Ignoring it is **not** proof of legitimacy. Probe results are supplemental behavioral data intended for later population/personal normality modeling.

Operators can force a probe for runtime validation with:

```text
/hg detection probe <player>
```

Automatic probes remain gated by collected hours and cooldowns.

## Population-normality model

The first normality model is an Isolation Forest trained offline from eligible trusted-population rows. It does not use CHEAT labels.

Train it with:

```bash
python tools/ml/train_normality_model.py \
  --input plugins/HackerGuardian/ml/normality/candidate-behavior-v1.csv \
  --manifest plugins/HackerGuardian/ml/normality/trust-manifest-v1.csv \
  --output plugins/HackerGuardian/models/population-normality-v1.hgif
```

The trainer:

- refuses rows whose quarantine has not expired;
- refuses players not currently trusted/baseline-eligible in the manifest;
- caps per-player contribution with reservoir sampling;
- splits validation by session;
- trains a dependency-light Isolation Forest with NumPy;
- calibrates the review threshold from held-out **normal** scores using a configured target normal false-positive rate;
- exports a strict HGIF v1 artifact.

The default model threshold is therefore not "60% probability of cheating". It is a calibrated statistical outlier boundary against held-out trusted behavior.

## Java inference

Minecraft runtime inference remains Java-only. `IsolationForestModelLoader` validates the artifact version, model type, feature schema/order, tree bounds, child references, and cycles. `NormalityDetector` emits only a `DetectionFinding`.

Enable a trained artifact with:

```yaml
DetectionV2:
  normality:
    enabled: true
    model_file: "models/population-normality-v1.hgif"
```

Then inspect/reload with:

```text
/hg detection normality
/hg detection normality reload
```

## Learning Mode operator commands

```text
/hg detection learning
/hg detection learning <online-player>
/hg detection probe <online-player>
```

The status surface shows collected hours, current trust source, baseline-hour maturity, session UUIDv7, dataset paths, dropped rows, and active probes.

## Relationship to supervised ML

The existing supervised logistic-regression model is intentionally retained. The architecture is now an ensemble of independent evidence sources:

```text
BehaviorSnapshot
      |
      +--> deterministic detectors
      |
      +--> supervised known-pattern model
      |
      +--> population normality model
      |
      +--> future personal baseline / temporal models
      |
      +--> probe reaction evidence
              |
              v
       DetectionAssessment
```

Future personal baselines should answer "is this unusual for this specific player?" while population normality answers "is this unusual for the trusted population?". Neither should directly punish.
