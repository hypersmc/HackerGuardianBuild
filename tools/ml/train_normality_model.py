#!/usr/bin/env python3
"""Train HackerGuardian's population-normality Isolation Forest.

This model intentionally learns primarily from trusted-player behavior rather
than requiring a catalog of every cheat. Candidate rows are accepted only when
(1) their quarantine timestamp has passed and (2) the current trust manifest
still marks the player as trusted and baseline-eligible.
"""

from __future__ import annotations

import argparse
import csv
import math
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np

SCHEMA_ID = "behavior-v1"
ARTIFACT_VERSION = "1"
MODEL_TYPE = "isolation-forest"

FEATURE_NAMES = [
    "movement_samples_per_second",
    "average_horizontal_speed",
    "max_horizontal_speed",
    "max_horizontal_delta",
    "average_yaw_delta",
    "yaw_delta_std",
    "average_pitch_delta",
    "pitch_delta_std",
    "ground_ratio",
    "swing_cps",
    "hit_rate",
    "hits_per_second",
    "average_hit_distance",
    "max_hit_distance",
    "blocks_broken_per_second",
    "max_break_distance",
    "blocks_placed_per_second",
    "max_place_distance",
    "ping_ms",
    "tps",
    "sprinting",
    "sneaking",
    "in_water",
    "on_ladder",
    "speed_effect",
    "jump_boost_effect",
    "flying",
    "gliding",
    "in_vehicle",
    "game_mode_survival",
    "game_mode_adventure",
    "game_mode_creative",
    "game_mode_spectator",
]

CANDIDATE_META = [
    "schema_id",
    "captured_at_ms",
    "session_id",
    "player_uuid",
    "player_name",
    "trust_source",
    "eligible_after_ms",
    "world",
    "window_ms",
]

MANIFEST_HEADER = [
    "player_uuid",
    "player_name",
    "trusted",
    "first_trusted_ms",
    "last_seen_ms",
    "collected_ms",
    "collected_hours",
    "baseline_eligible",
    "last_probe_ms",
]

EULER_GAMMA = 0.5772156649015329


@dataclass(frozen=True)
class Dataset:
    features: np.ndarray
    players: np.ndarray
    sessions: np.ndarray


@dataclass(frozen=True)
class Forest:
    trees: list[list[tuple]]
    sample_size: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train HackerGuardian population-normality Isolation Forest")
    parser.add_argument("--input", type=Path, help="candidate-behavior-v1.csv from Learning Mode")
    parser.add_argument("--manifest", type=Path, help="trust-manifest-v1.csv from Learning Mode")
    parser.add_argument("--output", type=Path, help="destination .hgif artifact")
    parser.add_argument("--model-id", default="population-normality-iforest-v1")
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--trees", type=int, default=128)
    parser.add_argument("--sample-size", type=int, default=256)
    parser.add_argument("--validation-fraction", type=float, default=0.20)
    parser.add_argument("--target-normal-fpr", type=float, default=0.002)
    parser.add_argument("--max-samples-per-player", type=int, default=50_000)
    parser.add_argument("--max-validation-rows", type=int, default=20_000)
    parser.add_argument("--min-players", type=int, default=3)
    parser.add_argument("--min-rows", type=int, default=1_000)
    parser.add_argument("--as-of-ms", type=int, default=None,
                        help="training eligibility cutoff; defaults to current wall-clock milliseconds")
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def load_eligible_players(path: Path) -> set[str]:
    if not path.is_file():
        raise ValueError(f"Trust manifest does not exist: {path}")
    eligible: set[str] = set()
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != MANIFEST_HEADER:
            raise ValueError("Trust manifest header is incompatible with Learning Mode v2")
        for row in reader:
            if row["trusted"].strip().lower() == "true" and row["baseline_eligible"].strip().lower() == "true":
                player = row["player_uuid"].strip()
                if player:
                    eligible.add(player)
    if not eligible:
        raise ValueError("Trust manifest contains no currently trusted baseline-eligible players")
    return eligible


def load_dataset(path: Path,
                 eligible_players: set[str],
                 as_of_ms: int,
                 max_samples_per_player: int,
                 rng: np.random.Generator) -> Dataset:
    if not path.is_file():
        raise ValueError(f"Candidate dataset does not exist: {path}")
    if max_samples_per_player < 1:
        raise ValueError("max_samples_per_player must be positive")

    reservoirs: dict[str, list[tuple[list[float], str]]] = {}
    seen: dict[str, int] = {}

    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        expected = CANDIDATE_META + FEATURE_NAMES
        if reader.fieldnames != expected:
            raise ValueError("Candidate dataset header is incompatible with FeatureSchemaV1/Learning Mode v2")

        for line_number, row in enumerate(reader, start=2):
            if row["schema_id"].strip() != SCHEMA_ID:
                raise ValueError(f"Line {line_number} uses unsupported schema {row['schema_id']!r}")
            player = row["player_uuid"].strip()
            if player not in eligible_players:
                continue
            try:
                eligible_after = int(row["eligible_after_ms"])
            except ValueError as exc:
                raise ValueError(f"Line {line_number} has invalid eligible_after_ms") from exc
            if eligible_after > as_of_ms:
                continue
            session = row["session_id"].strip()
            if not session:
                raise ValueError(f"Line {line_number} has no session id")

            values: list[float] = []
            for feature in FEATURE_NAMES:
                try:
                    value = float(row[feature])
                except (TypeError, ValueError) as exc:
                    raise ValueError(f"Line {line_number} has invalid {feature}") from exc
                if not math.isfinite(value):
                    raise ValueError(f"Line {line_number} has non-finite {feature}")
                values.append(value)

            count = seen.get(player, 0) + 1
            seen[player] = count
            bucket = reservoirs.setdefault(player, [])
            if len(bucket) < max_samples_per_player:
                bucket.append((values, session))
            else:
                replacement = int(rng.integers(0, count))
                if replacement < max_samples_per_player:
                    bucket[replacement] = (values, session)

    rows: list[list[float]] = []
    players: list[str] = []
    sessions: list[str] = []
    for player in sorted(reservoirs):
        for values, session in reservoirs[player]:
            rows.append(values)
            players.append(player)
            sessions.append(session)

    if not rows:
        raise ValueError("No quarantined candidate rows are currently eligible for training")
    return Dataset(
        features=np.asarray(rows, dtype=np.float64),
        players=np.asarray(players, dtype=object),
        sessions=np.asarray(sessions, dtype=object),
    )


def split_by_session(dataset: Dataset,
                     validation_fraction: float,
                     rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray]:
    if not 0.05 <= validation_fraction <= 0.50:
        raise ValueError("validation_fraction must be between 0.05 and 0.50")
    unique_sessions = np.asarray(sorted(set(str(s) for s in dataset.sessions)), dtype=object)
    if unique_sessions.size < 2:
        raise ValueError("Normality training requires at least two independent sessions")
    rng.shuffle(unique_sessions)
    validation_count = int(round(unique_sessions.size * validation_fraction))
    validation_count = max(1, min(validation_count, unique_sessions.size - 1))
    validation_sessions = set(str(value) for value in unique_sessions[:validation_count])
    validation_mask = np.asarray([str(s) in validation_sessions for s in dataset.sessions], dtype=bool)
    training = np.flatnonzero(~validation_mask)
    validation = np.flatnonzero(validation_mask)
    if training.size == 0 or validation.size == 0:
        raise ValueError("Session split produced an empty training or validation set")
    return training, validation


def average_path_length(size: int) -> float:
    if size <= 1:
        return 0.0
    if size == 2:
        return 1.0
    return 2.0 * (math.log(size - 1.0) + EULER_GAMMA) - 2.0 * (size - 1.0) / size


def build_tree(features: np.ndarray,
               row_indices: np.ndarray,
               max_depth: int,
               rng: np.random.Generator) -> list[tuple]:
    nodes: list[tuple | None] = []

    def grow(indices: np.ndarray, depth: int) -> int:
        node_index = len(nodes)
        nodes.append(None)
        count = int(indices.size)
        if count <= 1 or depth >= max_depth:
            nodes[node_index] = ("L", max(1, count))
            return node_index

        subset = features[indices]
        mins = subset.min(axis=0)
        maxs = subset.max(axis=0)
        candidates = np.flatnonzero(maxs > mins)
        if candidates.size == 0:
            nodes[node_index] = ("L", count)
            return node_index

        for _ in range(min(16, candidates.size * 2)):
            feature = int(candidates[int(rng.integers(0, candidates.size))])
            threshold = float(rng.uniform(mins[feature], maxs[feature]))
            mask = subset[:, feature] < threshold
            if mask.any() and (~mask).any():
                left = grow(indices[mask], depth + 1)
                right = grow(indices[~mask], depth + 1)
                nodes[node_index] = ("S", feature, threshold, left, right)
                return node_index

        nodes[node_index] = ("L", count)
        return node_index

    root = grow(row_indices, 0)
    if root != 0:
        raise AssertionError("Isolation tree root must be node zero")
    return [node for node in nodes if node is not None]


def train_forest(features: np.ndarray,
                 tree_count: int,
                 sample_size: int,
                 rng: np.random.Generator) -> Forest:
    rows = features.shape[0]
    if rows < 2:
        raise ValueError("Isolation Forest requires at least two rows")
    tree_count = max(1, min(tree_count, 512))
    sample_size = max(2, min(sample_size, rows))
    max_depth = int(math.ceil(math.log2(sample_size)))
    trees: list[list[tuple]] = []
    all_indices = np.arange(rows)
    for _ in range(tree_count):
        sample = rng.choice(all_indices, size=sample_size, replace=False)
        trees.append(build_tree(features, sample, max_depth, rng))
    return Forest(trees=trees, sample_size=sample_size)


def score_row(forest: Forest, row: np.ndarray) -> float:
    paths = 0.0
    for tree in forest.trees:
        index = 0
        depth = 0
        for _ in range(len(tree) + 1):
            node = tree[index]
            if node[0] == "L":
                paths += depth + average_path_length(int(node[1]))
                break
            _, feature, threshold, left, right = node
            index = int(left if row[int(feature)] < float(threshold) else right)
            depth += 1
        else:
            raise ValueError("Isolation tree contains a traversal cycle")
    mean_path = paths / len(forest.trees)
    normalizer = average_path_length(forest.sample_size)
    return float(2.0 ** (-mean_path / normalizer)) if normalizer > 0.0 else 0.0


def score_rows(forest: Forest, features: np.ndarray) -> np.ndarray:
    return np.asarray([score_row(forest, row) for row in features], dtype=np.float64)


def percentile(values: np.ndarray, q: float) -> float:
    return float(np.quantile(values, q, method="higher"))


def write_artifact(path: Path,
                   model_id: str,
                   forest: Forest,
                   threshold: float,
                   training_samples: int,
                   training_players: int,
                   training_sessions: int,
                   validation_scores: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"artifact.version={ARTIFACT_VERSION}",
        f"model.type={MODEL_TYPE}",
        f"model.id={model_id}",
        f"schema.id={SCHEMA_ID}",
        f"features.names={','.join(FEATURE_NAMES)}",
        f"tree.count={len(forest.trees)}",
        f"sample.size={forest.sample_size}",
        f"training.samples={training_samples}",
        f"training.players={training_players}",
        f"training.sessions={training_sessions}",
        f"decision.threshold={threshold:.17g}",
        f"metrics.validation_normal_mean={float(validation_scores.mean()):.17g}",
        f"metrics.validation_normal_p95={percentile(validation_scores, 0.95):.17g}",
        f"metrics.validation_normal_p99={percentile(validation_scores, 0.99):.17g}",
        f"metrics.validation_normal_p999={percentile(validation_scores, 0.999):.17g}",
    ]
    for tree_index, tree in enumerate(forest.trees):
        lines.append(f"tree.{tree_index}.node_count={len(tree)}")
        for node_index, node in enumerate(tree):
            if node[0] == "L":
                value = f"L,{int(node[1])}"
            else:
                _, feature, split, left, right = node
                value = f"S,{int(feature)},{float(split):.17g},{int(left)},{int(right)}"
            lines.append(f"tree.{tree_index}.node.{node_index}={value}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def train(dataset: Dataset, args: argparse.Namespace, rng: np.random.Generator) -> tuple[Forest, float, np.ndarray, np.ndarray]:
    unique_players = set(str(p) for p in dataset.players)
    if len(unique_players) < args.min_players:
        raise ValueError(f"Need at least {args.min_players} trusted players; found {len(unique_players)}")
    if dataset.features.shape[0] < args.min_rows:
        raise ValueError(f"Need at least {args.min_rows} eligible rows; found {dataset.features.shape[0]}")

    train_indices, validation_indices = split_by_session(dataset, args.validation_fraction, rng)
    train_features = dataset.features[train_indices]
    validation_features = dataset.features[validation_indices]
    if validation_features.shape[0] > args.max_validation_rows:
        selected = rng.choice(np.arange(validation_features.shape[0]), size=args.max_validation_rows, replace=False)
        validation_features = validation_features[selected]

    forest = train_forest(train_features, args.trees, args.sample_size, rng)
    validation_scores = score_rows(forest, validation_features)
    threshold = percentile(validation_scores, 1.0 - args.target_normal_fpr)
    threshold = max(0.01, min(0.99, threshold))
    return forest, threshold, validation_scores, train_indices


def run_training(args: argparse.Namespace) -> Path:
    if args.input is None or args.manifest is None or args.output is None:
        raise ValueError("--input, --manifest and --output are required unless --self-test is used")
    if not 0.0001 <= args.target_normal_fpr <= 0.10:
        raise ValueError("target_normal_fpr must be between 0.0001 and 0.10")

    rng = np.random.default_rng(args.seed)
    eligible = load_eligible_players(args.manifest)
    as_of_ms = int(time.time() * 1000) if args.as_of_ms is None else args.as_of_ms
    dataset = load_dataset(args.input, eligible, as_of_ms, args.max_samples_per_player, rng)
    forest, threshold, validation_scores, train_indices = train(dataset, args, rng)

    training_players = len(set(str(p) for p in dataset.players[train_indices]))
    training_sessions = len(set(str(s) for s in dataset.sessions[train_indices]))
    write_artifact(
        args.output,
        args.model_id,
        forest,
        threshold,
        len(train_indices),
        training_players,
        training_sessions,
        validation_scores,
    )

    print("HackerGuardian population-normality training complete")
    print(f"  schema:                {SCHEMA_ID}")
    print(f"  eligible rows:         {dataset.features.shape[0]}")
    print(f"  training rows:         {len(train_indices)}")
    print(f"  training players:      {training_players}")
    print(f"  training sessions:     {training_sessions}")
    print(f"  trees/sample size:     {len(forest.trees)}/{forest.sample_size}")
    print(f"  review threshold:      {threshold:.4f}")
    print(f"  validation normal p95: {percentile(validation_scores, 0.95):.4f}")
    print(f"  validation normal p99: {percentile(validation_scores, 0.99):.4f}")
    print(f"  artifact:              {args.output}")
    print("\nThis score means statistical outlier, not 'cheat probability'.")
    return args.output


def synthetic_row(rng: np.random.Generator) -> list[float]:
    row = np.zeros(len(FEATURE_NAMES), dtype=np.float64)
    row[0] = np.clip(rng.normal(18.0, 4.0), 0.0, 100.0)
    row[1] = np.clip(rng.normal(3.8, 0.8), 0.0, 20.0)
    row[2] = np.clip(rng.normal(5.5, 1.2), 0.0, 50.0)
    row[3] = np.clip(rng.normal(0.28, 0.08), 0.0, 10.0)
    row[4:8] = np.clip(rng.normal([4.5, 5.0, 2.0, 2.5], [1.5, 1.5, 0.8, 0.8]), 0.0, 180.0)
    row[8] = np.clip(rng.normal(0.75, 0.18), 0.0, 1.0)
    row[9] = np.clip(rng.normal(7.0, 2.0), 0.0, 100.0)
    row[10] = np.clip(rng.normal(0.35, 0.15), 0.0, 1.0)
    row[11] = np.clip(rng.normal(1.5, 0.8), 0.0, 100.0)
    row[12] = np.clip(rng.normal(2.6, 0.35), 0.0, 20.0)
    row[13] = np.clip(rng.normal(3.1, 0.35), 0.0, 20.0)
    row[14:18] = np.maximum(0.0, rng.normal([0.4, 3.5, 0.3, 3.5], [0.4, 0.5, 0.3, 0.5]))
    row[18] = np.clip(rng.normal(55.0, 25.0), 0.0, 5000.0)
    row[19] = np.clip(rng.normal(19.8, 0.18), 0.0, 20.5)
    row[20:29] = rng.binomial(1, [0.55, 0.08, 0.03, 0.01, 0.08, 0.06, 0.0, 0.0, 0.0])
    row[29:33] = [1.0, 0.0, 0.0, 0.0]
    return row.tolist()


def write_self_test_dataset(root: Path, rng: np.random.Generator) -> tuple[Path, Path]:
    manifest = root / "trust-manifest-v1.csv"
    candidate = root / "candidate-behavior-v1.csv"
    players = [f"00000000-0000-7000-8000-{i:012x}" for i in range(8)]

    with manifest.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(MANIFEST_HEADER)
        for i, player in enumerate(players):
            writer.writerow([player, f"player{i}", "true", 1, 2, 36_000_000, 10.0, "true", 0])

    with candidate.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(CANDIDATE_META + FEATURE_NAMES)
        timestamp = 1_000_000
        for player_index, player in enumerate(players):
            for session_index in range(3):
                session = f"019c0000-0000-7000-8000-{player_index:04x}{session_index:08x}"
                for _ in range(100):
                    writer.writerow([
                        SCHEMA_ID, timestamp, session, player, f"player{player_index}",
                        "SELF_TEST", 0, "world", 5000,
                    ] + synthetic_row(rng))
                    timestamp += 1000
    return candidate, manifest


def run_self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="hg-normality-self-test-") as temp:
        root = Path(temp)
        rng = np.random.default_rng(4242)
        candidate, manifest = write_self_test_dataset(root, rng)
        artifact = root / "population-normality-v1.hgif"
        args = argparse.Namespace(
            input=candidate,
            manifest=manifest,
            output=artifact,
            model_id="normality-self-test",
            seed=4242,
            trees=64,
            sample_size=128,
            validation_fraction=0.20,
            target_normal_fpr=0.01,
            max_samples_per_player=10_000,
            max_validation_rows=5_000,
            min_players=3,
            min_rows=500,
            as_of_ms=10_000_000_000,
        )
        output = run_training(args)
        if not output.is_file() or output.stat().st_size < 1000:
            raise AssertionError("normality trainer did not produce a usable artifact")

        eligible = load_eligible_players(manifest)
        dataset = load_dataset(candidate, eligible, args.as_of_ms, args.max_samples_per_player, np.random.default_rng(args.seed))
        train_indices, _ = split_by_session(dataset, args.validation_fraction, np.random.default_rng(args.seed))
        forest = train_forest(dataset.features[train_indices], 64, 128, np.random.default_rng(args.seed))
        normal_scores = score_rows(forest, dataset.features[:200])
        outliers = dataset.features[:100].copy()
        outliers[:, 1] = 18.0
        outliers[:, 2] = 45.0
        outliers[:, 4] = 175.0
        outliers[:, 9] = 70.0
        outlier_scores = score_rows(forest, outliers)
        if float(np.median(outlier_scores)) <= float(np.median(normal_scores)):
            raise AssertionError("synthetic outliers were not more anomalous than the normal population")
        print("Population normality tooling self-test passed.")


def main() -> None:
    args = parse_args()
    if args.self_test:
        run_self_test()
    else:
        run_training(args)


if __name__ == "__main__":
    main()
