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
   Detection v2 must not train from its own detections. False positives must never become future training labels just because the model produced them.

6. **Training labels require provenance.**
   Model training should use staff-reviewed evidence, controlled test sessions, or other deliberately labeled data where the source and label are known.

7. **Model inputs are versioned separately from telemetry.**
   ML code transforms `BehaviorSnapshot` into a versioned model-specific feature schema. Event collection is not coupled to a positional `double[]` owned by one model.

8. **Policy is separate from detection.**
   A future policy layer may decide that sustained strong evidence should trigger a replay or alert staff. Automatic punishment must never be a direct model callback.

## Current foundation

The foundation is deliberately observe-only. It contains:

- `BehaviorTelemetryCollector`
- `BehaviorSnapshot`
- `Detector`
- `DetectionFinding`
- `DetectionAssessment`
- `DetectionEngine`
- `DetectionRuntime`
- `ReachEnvelopeDetector`
- `ClickBurstDetector`
- `/hg detection [player]` for read-only inspection

The first two deterministic detectors are not intended to be a complete anti-cheat. They validate the telemetry, evidence, reliability, aggregation, history, configuration, and operator-inspection path before ML inference is introduced.

## Current detector philosophy

### Reach envelope

The server-observed attacker/victim distance is useful evidence but is not precise enough to be a verdict. Ping and low TPS reduce finding reliability.

### Click burst

High CPS alone is weak evidence. The detector intentionally has low reliability even when the observation is extreme.

## ML baseline

The original Neuroph subsystem has been removed completely. There is no legacy model file, online learning mode, legacy feature collector, AI SQL table, or duplicate AI command surface left to maintain.

The first real ML implementation should therefore start from a clean contract:

```text
BehaviorSnapshot
      |
      v
FeatureSchemaV1
      |
      v
normalization / validation
      |
      v
versioned model artifact
      |
      v
ML Detector
      |
      v
DetectionFinding
```

The model artifact must carry enough metadata to reject incompatible feature schemas rather than silently evaluating a vector with the wrong order or scale.

## Next ML steps

1. define `FeatureSchemaV1` and feature normalization rules;
2. define a versioned model-artifact format and compatibility checks;
3. persist/export labeled behavior samples with provenance;
4. build the offline training/evaluation workflow;
5. choose the first lightweight model family based on measured data rather than model novelty;
6. implement inference behind the existing `Detector` contract;
7. compare ML findings against deterministic findings and reviewed replay evidence;
8. only after validation, introduce the policy layer that can trigger evidence capture or staff review.
