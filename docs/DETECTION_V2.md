# HackerGuardian Detection v2

Detection v2 replaces the original idea that one neural-network output should be treated as the anti-cheat decision.

The direction is evidence-first:

```text
Bukkit / packet observations
        |
        v
bounded telemetry window
        |
        v
BehaviorSnapshot
        |
        +-------------------+
        |                   |
        v                   v
heuristic detector       ML detector(s)
        |                   |
        +---------+---------+
                  |
                  v
          DetectionFinding(s)
                  |
                  v
          DetectionAssessment
                  |
                  v
        future policy / evidence layer
                  |
          +-------+-------+
          |               |
       replay          staff review
          |               |
          +-------+-------+
                  |
             possible action
```

## Rules of the design

1. **Telemetry does not know about ML.**
   `BehaviorTelemetryCollector` records bounded recent observations and creates a typed `BehaviorSnapshot`.

2. **Detectors do not punish.**
   `Detector` implementations can only emit `DetectionFinding` objects. They do not get access to punishment, replay, report, or staff-warning APIs.

3. **Unusual is not the same as cheating.**
   A finding has both a `score` and a `reliability`. A noisy signal can be very unusual while still carrying low reliability.

4. **The aggregate score is evidence, not a verdict.**
   `DetectionAssessment.riskScore` is intentionally not named `cheatProbability` or `confidenceOfCheating`.

5. **No online self-training.**
   Detection v2 never trains from its own detections. False positives must never become future training labels because the model produced them.

6. **Training labels require provenance.**
   Model training uses deliberately labeled capture sessions. A `CHEAT` label should mean known/controlled cheat behavior, not "the current model disliked this player".

7. **Model inputs are versioned separately from telemetry.**
   ML code transforms `BehaviorSnapshot` into a versioned model-specific feature schema. Event collection is not coupled to a positional vector owned by one model.

8. **Policy is separate from detection.**
   A future policy layer may decide that sustained strong evidence should trigger a replay or alert staff. Automatic punishment is never a direct model callback.

## Current foundation

Detection v2 currently contains:

- `BehaviorTelemetryCollector`
- `BehaviorSnapshot`
- `Detector`
- `DetectionFinding`
- `DetectionAssessment`
- `DetectionEngine`
- `DetectionRuntime`
- `ReachEnvelopeDetector`
- `ClickBurstDetector`
- `FeatureSchemaV1`
- `MlDatasetRecorder`
- `LogisticRegressionModel`
- `MlBehaviorDetector`
- strict HGML model-artifact loading
- an offline Python trainer
- `/hg detection ...` operator tooling

The deterministic detectors remain useful as independent evidence. The ML model does not replace them; the engine can compare and aggregate both kinds of findings.

## First real ML baseline

The first learned model is deliberately **logistic regression**, trained offline from labeled behavior windows.

That is a real supervised ML model: the weights and bias are learned from data rather than hand-written thresholds. It is the first model because it provides a strong engineering baseline before adding complexity:

- extremely cheap inference on the Minecraft server;
- deterministic and easy to test;
- interpretable per-feature contributions;
- straightforward probability-like score for ranking evidence;
- easy to compare against future MLP/tree/sequence models;
- no native runtime or heavyweight ML framework inside the plugin.

A neural network is not automatically a better detector. A future model should replace this baseline only if it demonstrates materially better held-out performance and acceptable false-positive behavior on real HackerGuardian data.

## FeatureSchemaV1

ML inputs use schema id `behavior-v1`. The exact feature order is part of the contract:

```text
movement_samples_per_second
average_horizontal_speed
max_horizontal_speed
max_horizontal_delta
average_yaw_delta
yaw_delta_std
average_pitch_delta
pitch_delta_std
ground_ratio
swing_cps
hit_rate
hits_per_second
average_hit_distance
max_hit_distance
blocks_broken_per_second
max_break_distance
blocks_placed_per_second
max_place_distance
ping_ms
tps
sprinting
sneaking
in_water
on_ladder
speed_effect
jump_boost_effect
flying
gliding
in_vehicle
game_mode_survival
game_mode_adventure
game_mode_creative
game_mode_spectator
```

Raw telemetry is clamped to documented physical/operational ranges before becoming a feature vector. Training then learns per-feature mean and scale from the training split. Runtime applies those exact artifact normalization values and clips standardized values to `[-8, 8]`.

Changing feature order, meaning, units, or normalization semantics requires a new feature schema version instead of silently changing `behavior-v1`.

## HGML v1 model artifact

The server loads a dependency-free text artifact such as:

```text
plugins/HackerGuardian/models/behavior-v1.hgml
```

The HGML v1 artifact contains:

- artifact format version;
- model type and model id;
- required feature schema id;
- exact ordered feature-name list;
- training-set normalization mean and scale;
- learned weights and bias;
- selected review threshold;
- training sample metadata;
- validation accuracy, precision, recall, F1, and ROC-AUC.

The Java loader rejects unknown artifact versions, model types, incompatible schema ids, reordered features, wrong vector lengths, invalid numbers, or invalid normalization scales. A mismatched model therefore fails closed as an unavailable ML detector instead of evaluating the wrong vector.

No pre-trained production model is shipped with HackerGuardian yet. Shipping weights trained on synthetic/random data and calling them a cheat detector would provide false confidence. Real model artifacts should come from deliberately collected data.

## Collecting labeled behavior

Dataset collection is available independently of ML inference. By default it writes:

```text
plugins/HackerGuardian/ml/dataset-v1.csv
```

Start a deliberate capture:

```text
/hg detection capture <player> LEGIT [minutes]
/hg detection capture <player> CHEAT [minutes]
```

Inspect or stop captures:

```text
/hg detection capture list
/hg detection capture stop <player>
```

Each row includes schema id, timestamp, capture-session id, player UUID/name, label, operator, world, window size, and all `FeatureSchemaV1` values. The writer is bounded and asynchronous so dataset disk I/O does not occur on the main Bukkit thread.

A capture label remains fixed for its session. Model output, heuristic findings, reports, punishments, and staff alerts never create training labels automatically.

### Data collection guidance

Useful training data should contain both controlled normal play and known cheat/test behavior across different people and conditions. In particular, collect variation in:

- player skill and input style;
- ping and network quality;
- TPS/server load;
- combat and non-combat activity;
- movement states and potion effects;
- different legitimate high-CPS / high-skill edge cases;
- multiple cheat clients/settings where testing is authorized.

Do not assume that a punishment means `CHEAT`, and do not assume that an unpunished player means `LEGIT`. Those are policy outcomes, not ground-truth labels.

## Offline training

Install the tiny trainer dependency set:

```bash
python -m pip install -r tools/ml/requirements.txt
```

Train a model from a copied/exported dataset:

```bash
python tools/ml/train_behavior_model.py \
  --input /path/to/dataset-v1.csv \
  --output /path/to/behavior-v1.hgml
```

Important properties of the trainer:

- `LEGIT=0`, `CHEAT=1` supervised labels;
- validation is split by **capture session**, not individual adjacent rows, reducing temporal leakage;
- both classes must have at least two independent sessions;
- normalization is learned from the training split only;
- class-balanced logistic-regression training uses Adam plus L2 regularization;
- the review threshold prioritizes validation precision because false positives are expensive;
- evaluation reports accuracy, precision, recall, F1, and ROC-AUC;
- output is a strict HGML v1 artifact consumed by the Java runtime.

CI also runs:

```bash
python tools/ml/train_behavior_model.py --self-test
```

The self-test uses synthetic data only to verify the training/artifact pipeline. Its generated weights are never committed or treated as a real anti-cheat model.

## Enabling inference

Copy a real trained artifact to the path configured in `detection.yml`, then enable:

```yaml
DetectionV2:
  ml:
    enabled: true
    model_file: "models/behavior-v1.hgml"
```

Restart the server, or if ML was already enabled, replace the artifact and run:

```text
/hg detection ml reload
```

Model status and validation metadata can be inspected with:

```text
/hg detection ml
```

Runtime inference also reduces/omits ML evidence for insufficient activity, excessive ping, low TPS, Creative mode, or Spectator mode. A model score becomes a `DetectionFinding` only; it still cannot warn, replay-trigger, kick, ban, or punish by itself.

## Explainability

For a logistic-regression prediction the runtime can calculate each standardized feature's signed contribution to the model logit. The strongest contributions are included in the finding evidence alongside:

- model score;
- artifact review threshold;
- activity sample count;
- ping;
- TPS;
- validation ROC-AUC when present.

This does not make every prediction automatically correct, but it makes the first ML baseline inspectable rather than opaque.

## What comes after the baseline

Once enough reviewed data exists, candidates such as category-specific models, gradient-boosted trees, small MLPs, or temporal/sequence models can be evaluated. They should be tested against the exact same held-out sessions/players and compared to logistic regression on false-positive rate, precision, recall, ROC/PR behavior, inference cost, robustness to ping/TPS, and calibration.

The next architecture milestone after model quality is proven is the policy/evidence layer: sustained strong findings may trigger replay capture or staff review. Automatic punishment should remain a separate, deliberately conservative decision even then.
