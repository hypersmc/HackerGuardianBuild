# HackerGuardian Detection v2

Detection v2 replaces the original idea that one neural-network output should be treated as the anti-cheat decision.

The new direction is evidence-first:

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
heuristic detector      future ML detector
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

1. **Telemetry does not know about AI.**
   `BehaviorTelemetryCollector` only records bounded recent observations and creates a typed `BehaviorSnapshot`.

2. **Detectors do not punish.**
   `Detector` implementations can only emit `DetectionFinding` objects. They do not get access to punishment, replay, report, or staff-warning APIs.

3. **Unusual is not the same as cheating.**
   A finding has both a `score` and a `reliability`. A noisy signal can be very unusual while still carrying low reliability.

4. **The aggregate score is evidence, not a verdict.**
   `DetectionAssessment.riskScore` is intentionally not named `cheatProbability` or `confidenceOfCheating`.

5. **No online self-training.**
   Detection v2 must not train from its own detections. That creates a feedback loop where false positives become future training labels.

6. **Training labels should come from reviewed evidence.**
   The future model-training path should use staff-reviewed replays/reports or deliberately generated test data with provenance.

7. **Model inputs are versioned separately from telemetry.**
   Future ML code should transform `BehaviorSnapshot` into a model-specific feature schema. The event collector must not be coupled to a hard-coded neural-network input order.

8. **Policy is separate from detection.**
   A future policy layer may decide that sustained strong evidence should trigger a replay or alert staff. Automatic punishment must require a separately reviewed policy and must never be a direct model callback.

## Current foundation

The first v2 foundation is deliberately observe-only. It contains:

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

The first two detectors are not intended to be a complete anti-cheat. They validate that the telemetry, evidence, reliability, aggregation, history, configuration, and operator-inspection path work before more advanced detection is added.

## Current detector philosophy

### Reach envelope

The server-observed attacker/victim distance is useful evidence but is not precise enough to be a verdict. Ping and low TPS reduce the finding reliability.

### Click burst

High CPS alone is weak evidence. The detector intentionally has low reliability even when the observation is extreme.

## Next steps

A sensible next sequence is:

1. validate telemetry values on a real test server;
2. add packet-level telemetry as a separate optional source;
3. add environmental context needed to avoid movement false positives;
4. persist selected assessments/evidence with explicit schema versions;
5. connect replay IDs to assessment evidence;
6. create a reviewed-label workflow;
7. choose and implement the first ML model behind the `Detector` contract;
8. create a policy layer for replay/staff-alert decisions;
9. only after substantial validation, discuss whether any automatic enforcement should exist.

## Legacy AI

The existing Neuroph implementation remains temporarily available behind `Settings.EnableAI`, which defaults to `false`.

It is considered legacy/deprecated and is intentionally isolated from Detection v2. Once the useful pieces have been migrated or replaced, the Neuroph dependency, old feature collector, online learning commands, and old AI database path can be removed.
