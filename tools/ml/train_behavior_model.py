#!/usr/bin/env python3
"""Offline trainer for HackerGuardian's first real behavior ML baseline.

The server writes deliberately labeled FeatureSchemaV1 rows. This tool keeps
training entirely offline, splits validation by capture session to reduce
window-to-window leakage, learns a class-balanced logistic regression model,
and exports a strict HGML v1 artifact for Java inference.
"""

from __future__ import annotations

import argparse
import csv
import math
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import numpy as np

SCHEMA_ID = "behavior-v1"
ARTIFACT_VERSION = "1"
MODEL_TYPE = "logistic-regression"

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

META_COLUMNS = [
    "schema_id",
    "captured_at_ms",
    "session_id",
    "player_uuid",
    "player_name",
    "label",
    "operator",
    "world",
    "window_ms",
]


@dataclass(frozen=True)
class Dataset:
    features: np.ndarray
    labels: np.ndarray
    sessions: np.ndarray


@dataclass(frozen=True)
class Metrics:
    accuracy: float
    precision: float
    recall: float
    f1: float
    auc: float


@dataclass(frozen=True)
class TrainedModel:
    means: np.ndarray
    scales: np.ndarray
    weights: np.ndarray
    bias: float
    threshold: float
    metrics: Metrics
    training_rows: int
    positive_rows: int
    training_sessions: int
    validation_rows: int
    validation_sessions: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train HackerGuardian FeatureSchemaV1 logistic-regression baseline"
    )
    parser.add_argument("--input", type=Path, help="dataset-v1.csv exported by HackerGuardian")
    parser.add_argument("--output", type=Path, help="destination .hgml artifact")
    parser.add_argument("--model-id", default="behavior-logreg-v1")
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--validation-fraction", type=float, default=0.20)
    parser.add_argument("--epochs", type=int, default=350)
    parser.add_argument("--learning-rate", type=float, default=0.015)
    parser.add_argument("--l2", type=float, default=0.001)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument(
        "--min-precision",
        type=float,
        default=0.98,
        help="prefer a review threshold reaching at least this validation precision",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="generate synthetic labeled sessions and verify the trainer/artifact pipeline",
    )
    return parser.parse_args()


def load_dataset(path: Path) -> Dataset:
    if not path.is_file():
        raise ValueError(f"Dataset does not exist: {path}")

    feature_rows: list[list[float]] = []
    labels: list[int] = []
    sessions: list[str] = []

    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        expected_header = META_COLUMNS + FEATURE_NAMES
        if reader.fieldnames != expected_header:
            raise ValueError(
                "Dataset header is incompatible with FeatureSchemaV1.\n"
                f"Expected: {','.join(expected_header)}\n"
                f"Received: {','.join(reader.fieldnames or [])}"
            )

        for line_number, row in enumerate(reader, start=2):
            if row["schema_id"].strip() != SCHEMA_ID:
                raise ValueError(
                    f"Line {line_number} uses schema {row['schema_id']!r}; expected {SCHEMA_ID!r}"
                )

            label_raw = row["label"].strip().upper()
            if label_raw == "LEGIT":
                label = 0
            elif label_raw == "CHEAT":
                label = 1
            else:
                raise ValueError(
                    f"Line {line_number} has unsupported label {row['label']!r}; use LEGIT or CHEAT"
                )

            session_id = row["session_id"].strip()
            if not session_id:
                raise ValueError(f"Line {line_number} has no session_id")

            values: list[float] = []
            for feature in FEATURE_NAMES:
                try:
                    value = float(row[feature])
                except (TypeError, ValueError) as exc:
                    raise ValueError(
                        f"Line {line_number} contains invalid {feature}: {row[feature]!r}"
                    ) from exc
                if not math.isfinite(value):
                    raise ValueError(
                        f"Line {line_number} contains non-finite {feature}: {row[feature]!r}"
                    )
                values.append(value)

            feature_rows.append(values)
            labels.append(label)
            sessions.append(session_id)

    if not feature_rows:
        raise ValueError("Dataset contains no samples")

    dataset = Dataset(
        features=np.asarray(feature_rows, dtype=np.float64),
        labels=np.asarray(labels, dtype=np.int8),
        sessions=np.asarray(sessions, dtype=object),
    )
    validate_dataset(dataset)
    return dataset


def validate_dataset(dataset: Dataset) -> None:
    if dataset.features.ndim != 2 or dataset.features.shape[1] != len(FEATURE_NAMES):
        raise ValueError(f"Expected {len(FEATURE_NAMES)} features per row")
    if not np.isfinite(dataset.features).all():
        raise ValueError("Dataset contains non-finite feature values")

    labels = set(int(value) for value in np.unique(dataset.labels))
    if labels != {0, 1}:
        raise ValueError("Training requires both LEGIT and CHEAT samples")

    session_labels: dict[str, int] = {}
    for session, label in zip(dataset.sessions, dataset.labels):
        key = str(session)
        numeric_label = int(label)
        previous = session_labels.setdefault(key, numeric_label)
        if previous != numeric_label:
            raise ValueError(f"Capture session {key!r} contains mixed LEGIT/CHEAT labels")

    by_label = {0: 0, 1: 0}
    for label in session_labels.values():
        by_label[label] += 1
    if by_label[0] < 2 or by_label[1] < 2:
        raise ValueError(
            "Training/validation requires at least two independently labeled sessions per class"
        )


def split_by_session(
    dataset: Dataset, validation_fraction: float, rng: np.random.Generator
) -> tuple[np.ndarray, np.ndarray]:
    if not 0.05 <= validation_fraction <= 0.50:
        raise ValueError("validation_fraction must be between 0.05 and 0.50")

    session_labels: dict[str, int] = {}
    for session, label in zip(dataset.sessions, dataset.labels):
        session_labels[str(session)] = int(label)

    validation_sessions: set[str] = set()
    for target_label in (0, 1):
        candidates = np.asarray(
            sorted(session for session, label in session_labels.items() if label == target_label),
            dtype=object,
        )
        rng.shuffle(candidates)
        validation_count = int(round(len(candidates) * validation_fraction))
        validation_count = max(1, min(validation_count, len(candidates) - 1))
        validation_sessions.update(str(value) for value in candidates[:validation_count])

    validation_mask = np.asarray(
        [str(session) in validation_sessions for session in dataset.sessions], dtype=bool
    )
    validation_indices = np.flatnonzero(validation_mask)
    training_indices = np.flatnonzero(~validation_mask)

    if training_indices.size == 0 or validation_indices.size == 0:
        raise ValueError("Session split produced an empty training or validation set")
    if set(int(value) for value in np.unique(dataset.labels[training_indices])) != {0, 1}:
        raise ValueError("Training split lost one label class")
    if set(int(value) for value in np.unique(dataset.labels[validation_indices])) != {0, 1}:
        raise ValueError("Validation split lost one label class")

    return training_indices, validation_indices


def standardize(
    train_features: np.ndarray, other_features: np.ndarray
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    means = train_features.mean(axis=0)
    scales = train_features.std(axis=0)
    scales = np.where(scales < 1.0e-9, 1.0, scales)

    train_normalized = np.clip((train_features - means) / scales, -8.0, 8.0)
    other_normalized = np.clip((other_features - means) / scales, -8.0, 8.0)
    return train_normalized, other_normalized, means, scales


def sigmoid(logits: np.ndarray) -> np.ndarray:
    clipped = np.clip(logits, -50.0, 50.0)
    return 1.0 / (1.0 + np.exp(-clipped))


def train_logistic_regression(
    features: np.ndarray,
    labels: np.ndarray,
    *,
    epochs: int,
    learning_rate: float,
    l2: float,
    batch_size: int,
    rng: np.random.Generator,
) -> tuple[np.ndarray, float]:
    if epochs < 1:
        raise ValueError("epochs must be positive")
    if learning_rate <= 0.0:
        raise ValueError("learning_rate must be positive")
    if l2 < 0.0:
        raise ValueError("l2 must be non-negative")

    rows, feature_count = features.shape
    batch_size = max(1, min(batch_size, rows))

    positives = max(1, int(np.sum(labels == 1)))
    negatives = max(1, int(np.sum(labels == 0)))
    positive_weight = rows / (2.0 * positives)
    negative_weight = rows / (2.0 * negatives)
    sample_weights = np.where(labels == 1, positive_weight, negative_weight).astype(np.float64)

    weights = np.zeros(feature_count, dtype=np.float64)
    bias = 0.0

    first_moment_w = np.zeros_like(weights)
    second_moment_w = np.zeros_like(weights)
    first_moment_b = 0.0
    second_moment_b = 0.0
    beta1 = 0.9
    beta2 = 0.999
    epsilon = 1.0e-8
    step = 0

    indices = np.arange(rows)
    labels_float = labels.astype(np.float64)

    for _ in range(epochs):
        rng.shuffle(indices)
        for start in range(0, rows, batch_size):
            batch_indices = indices[start : start + batch_size]
            x_batch = features[batch_indices]
            y_batch = labels_float[batch_indices]
            batch_weights = sample_weights[batch_indices]

            probabilities = sigmoid(x_batch @ weights + bias)
            error = (probabilities - y_batch) * batch_weights
            denominator = max(float(np.sum(batch_weights)), 1.0)

            gradient_w = (x_batch.T @ error) / denominator + (l2 * weights)
            gradient_b = float(np.sum(error) / denominator)

            step += 1
            first_moment_w = beta1 * first_moment_w + (1.0 - beta1) * gradient_w
            second_moment_w = beta2 * second_moment_w + (1.0 - beta2) * (gradient_w**2)
            first_moment_b = beta1 * first_moment_b + (1.0 - beta1) * gradient_b
            second_moment_b = beta2 * second_moment_b + (1.0 - beta2) * (gradient_b**2)

            corrected_m_w = first_moment_w / (1.0 - beta1**step)
            corrected_v_w = second_moment_w / (1.0 - beta2**step)
            corrected_m_b = first_moment_b / (1.0 - beta1**step)
            corrected_v_b = second_moment_b / (1.0 - beta2**step)

            weights -= learning_rate * corrected_m_w / (np.sqrt(corrected_v_w) + epsilon)
            bias -= learning_rate * corrected_m_b / (math.sqrt(corrected_v_b) + epsilon)

    if not np.isfinite(weights).all() or not math.isfinite(bias):
        raise ValueError("Training produced non-finite model parameters")
    return weights, bias


def binary_metrics(labels: np.ndarray, probabilities: np.ndarray, threshold: float) -> Metrics:
    predictions = probabilities >= threshold
    positives = labels == 1
    negatives = ~positives

    true_positive = int(np.sum(predictions & positives))
    true_negative = int(np.sum(~predictions & negatives))
    false_positive = int(np.sum(predictions & negatives))
    false_negative = int(np.sum(~predictions & positives))

    total = max(1, labels.size)
    accuracy = (true_positive + true_negative) / total
    precision = true_positive / max(1, true_positive + false_positive)
    recall = true_positive / max(1, true_positive + false_negative)
    f1 = 0.0 if precision + recall == 0.0 else 2.0 * precision * recall / (precision + recall)
    auc = roc_auc(labels, probabilities)
    return Metrics(accuracy, precision, recall, f1, auc)


def roc_auc(labels: np.ndarray, probabilities: np.ndarray) -> float:
    positive_count = int(np.sum(labels == 1))
    negative_count = int(np.sum(labels == 0))
    if positive_count == 0 or negative_count == 0:
        return float("nan")

    order = np.argsort(probabilities, kind="mergesort")
    sorted_scores = probabilities[order]
    ranks = np.empty(probabilities.size, dtype=np.float64)

    start = 0
    while start < sorted_scores.size:
        end = start + 1
        while end < sorted_scores.size and sorted_scores[end] == sorted_scores[start]:
            end += 1
        # Ranks are 1-based; ties receive their average rank.
        average_rank = ((start + 1) + end) / 2.0
        ranks[order[start:end]] = average_rank
        start = end

    positive_rank_sum = float(np.sum(ranks[labels == 1]))
    return (
        positive_rank_sum - (positive_count * (positive_count + 1) / 2.0)
    ) / (positive_count * negative_count)


def f_beta(precision: float, recall: float, beta: float = 0.5) -> float:
    beta_squared = beta * beta
    denominator = beta_squared * precision + recall
    if denominator <= 0.0:
        return 0.0
    return (1.0 + beta_squared) * precision * recall / denominator


def choose_threshold(
    labels: np.ndarray, probabilities: np.ndarray, minimum_precision: float
) -> tuple[float, Metrics]:
    if not 0.50 <= minimum_precision <= 1.0:
        raise ValueError("min_precision must be between 0.50 and 1.00")

    candidates = np.unique(
        np.concatenate(
            [
                np.linspace(0.05, 0.95, 181, dtype=np.float64),
                probabilities,
            ]
        )
    )
    candidates = candidates[(candidates > 0.0) & (candidates < 1.0)]

    preferred: list[tuple[tuple[float, ...], float, Metrics]] = []
    fallbacks: list[tuple[tuple[float, ...], float, Metrics]] = []
    for threshold in candidates:
        metrics = binary_metrics(labels, probabilities, float(threshold))
        f05 = f_beta(metrics.precision, metrics.recall, 0.5)
        if metrics.precision >= minimum_precision and metrics.recall > 0.0:
            # False positives are expensive. First satisfy the requested
            # precision, then recover as much recall as possible.
            key = (metrics.recall, metrics.precision, f05, float(threshold))
            preferred.append((key, float(threshold), metrics))
        else:
            key = (f05, metrics.precision, metrics.recall, float(threshold))
            fallbacks.append((key, float(threshold), metrics))

    pool = preferred if preferred else fallbacks
    if not pool:
        raise ValueError("Unable to select a decision threshold")
    _, threshold, metrics = max(pool, key=lambda item: item[0])
    return threshold, metrics


def fit_model(dataset: Dataset, args: argparse.Namespace) -> TrainedModel:
    rng = np.random.default_rng(args.seed)
    train_indices, validation_indices = split_by_session(
        dataset, args.validation_fraction, rng
    )

    train_x = dataset.features[train_indices]
    train_y = dataset.labels[train_indices]
    validation_x = dataset.features[validation_indices]
    validation_y = dataset.labels[validation_indices]

    train_normalized, validation_normalized, means, scales = standardize(
        train_x, validation_x
    )
    weights, bias = train_logistic_regression(
        train_normalized,
        train_y,
        epochs=args.epochs,
        learning_rate=args.learning_rate,
        l2=args.l2,
        batch_size=args.batch_size,
        rng=rng,
    )

    validation_probabilities = sigmoid(validation_normalized @ weights + bias)
    threshold, metrics = choose_threshold(
        validation_y, validation_probabilities, args.min_precision
    )

    return TrainedModel(
        means=means,
        scales=scales,
        weights=weights,
        bias=float(bias),
        threshold=float(threshold),
        metrics=metrics,
        training_rows=int(train_indices.size),
        positive_rows=int(np.sum(train_y == 1)),
        training_sessions=len(set(str(v) for v in dataset.sessions[train_indices])),
        validation_rows=int(validation_indices.size),
        validation_sessions=len(set(str(v) for v in dataset.sessions[validation_indices])),
    )


def format_vector(values: Iterable[float]) -> str:
    return ",".join(format(float(value), ".17g") for value in values)


def write_artifact(path: Path, model_id: str, model: TrainedModel) -> None:
    if not model_id.strip():
        raise ValueError("model_id must not be blank")

    path = path.expanduser().resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    created_at = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")

    lines = [
        "# HackerGuardian Machine Learning artifact (HGML)",
        "# Generated offline. Do not hand-edit feature order or normalization.",
        f"artifact.version={ARTIFACT_VERSION}",
        f"model.type={MODEL_TYPE}",
        f"model.id={model_id.strip()}",
        f"schema.id={SCHEMA_ID}",
        f"features.names={','.join(FEATURE_NAMES)}",
        f"normalization.mean={format_vector(model.means)}",
        f"normalization.scale={format_vector(model.scales)}",
        f"model.weights={format_vector(model.weights)}",
        f"model.bias={format(model.bias, '.17g')}",
        f"decision.threshold={format(model.threshold, '.17g')}",
        f"training.samples={model.training_rows}",
        f"training.positive_samples={model.positive_rows}",
        f"training.sessions={model.training_sessions}",
        f"training.created_at={created_at}",
        f"validation.samples={model.validation_rows}",
        f"validation.sessions={model.validation_sessions}",
        f"metrics.validation_accuracy={format(model.metrics.accuracy, '.17g')}",
        f"metrics.validation_precision={format(model.metrics.precision, '.17g')}",
        f"metrics.validation_recall={format(model.metrics.recall, '.17g')}",
        f"metrics.validation_f1={format(model.metrics.f1, '.17g')}",
        f"metrics.validation_auc={format(model.metrics.auc, '.17g')}",
        "",
    ]
    temporary.write_text("\n".join(lines), encoding="utf-8")
    temporary.replace(path)


def print_summary(model: TrainedModel, output: Path) -> None:
    print("HackerGuardian ML training complete")
    print(f"  schema:              {SCHEMA_ID}")
    print(f"  training rows:       {model.training_rows}")
    print(f"  training sessions:   {model.training_sessions}")
    print(f"  validation rows:     {model.validation_rows}")
    print(f"  validation sessions: {model.validation_sessions}")
    print(f"  review threshold:    {model.threshold:.4f}")
    print(f"  validation accuracy: {model.metrics.accuracy:.4f}")
    print(f"  validation precision:{model.metrics.precision:.4f}")
    print(f"  validation recall:   {model.metrics.recall:.4f}")
    print(f"  validation F1:       {model.metrics.f1:.4f}")
    print(f"  validation ROC-AUC:  {model.metrics.auc:.4f}")
    print(f"  artifact:            {output}")
    print()
    print("The artifact is an evidence model, not an automatic punishment policy.")


def write_synthetic_dataset(path: Path, seed: int) -> None:
    rng = np.random.default_rng(seed)
    path.parent.mkdir(parents=True, exist_ok=True)

    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(META_COLUMNS + FEATURE_NAMES)

        timestamp = 1_700_000_000_000
        for label_name, label_value in (("LEGIT", 0), ("CHEAT", 1)):
            for session_number in range(14):
                session_id = f"synthetic-{label_name.lower()}-{session_number}"
                player_id = f"00000000-0000-0000-{label_value:04d}-{session_number:012d}"
                for row_number in range(18):
                    values = rng.normal(0.0, 0.15, len(FEATURE_NAMES))

                    # Start with plausible benign ranges and inject several
                    # correlated cheat signals instead of one trivial feature.
                    values[:] = np.maximum(values, 0.0)
                    values[0] = rng.normal(20.0, 2.0)  # movement samples/s
                    values[1] = rng.normal(4.2, 0.5)
                    values[2] = rng.normal(5.5, 0.7)
                    values[4] = rng.normal(5.0, 2.0)
                    values[5] = rng.normal(3.0, 1.0)
                    values[8] = np.clip(rng.normal(0.75, 0.10), 0.0, 1.0)
                    values[9] = rng.normal(7.0, 2.0)
                    values[10] = np.clip(rng.normal(0.45, 0.12), 0.0, 1.0)
                    values[11] = rng.normal(3.0, 1.0)
                    values[12] = rng.normal(2.7, 0.25)
                    values[13] = rng.normal(3.2, 0.30)
                    values[18] = rng.normal(70.0, 20.0)
                    values[19] = rng.normal(19.8, 0.12)
                    values[20:29] = (rng.random(9) < 0.15).astype(float)
                    values[29:33] = 0.0
                    values[29] = 1.0

                    if label_value == 1:
                        values[4] += rng.normal(11.0, 2.0)
                        values[5] = max(0.05, values[5] - rng.normal(1.8, 0.4))
                        values[9] += rng.normal(9.0, 1.5)
                        values[10] = np.clip(values[10] + rng.normal(0.32, 0.06), 0.0, 1.0)
                        values[11] += rng.normal(4.0, 0.8)
                        values[12] += rng.normal(0.65, 0.10)
                        values[13] += rng.normal(1.0, 0.15)

                    writer.writerow(
                        [
                            SCHEMA_ID,
                            timestamp,
                            session_id,
                            player_id,
                            f"synthetic-{label_name.lower()}",
                            label_name,
                            "ci-self-test",
                            "synthetic-world",
                            5000,
                            *[format(float(v), ".12g") for v in values],
                        ]
                    )
                    timestamp += 1000


def run_self_test(args: argparse.Namespace) -> None:
    with tempfile.TemporaryDirectory(prefix="hg-ml-self-test-") as temp_directory:
        root = Path(temp_directory)
        dataset_path = root / "dataset-v1.csv"
        artifact_path = root / "behavior-v1.hgml"
        write_synthetic_dataset(dataset_path, args.seed)
        dataset = load_dataset(dataset_path)
        model = fit_model(dataset, args)
        write_artifact(artifact_path, "ci-synthetic-logreg-v1", model)

        artifact_text = artifact_path.read_text(encoding="utf-8")
        required_fragments = [
            "artifact.version=1",
            "model.type=logistic-regression",
            f"schema.id={SCHEMA_ID}",
            f"features.names={','.join(FEATURE_NAMES)}",
        ]
        for fragment in required_fragments:
            if fragment not in artifact_text:
                raise AssertionError(f"Self-test artifact is missing {fragment!r}")

        if model.metrics.auc < 0.90:
            raise AssertionError(
                f"Synthetic self-test expected ROC-AUC >= 0.90, got {model.metrics.auc:.4f}"
            )
        if model.metrics.precision < 0.80:
            raise AssertionError(
                f"Synthetic self-test expected precision >= 0.80, got {model.metrics.precision:.4f}"
            )
        print_summary(model, artifact_path)
        print("ML tooling self-test passed.")


def main() -> int:
    args = parse_args()

    if args.self_test:
        run_self_test(args)
        return 0

    if args.input is None or args.output is None:
        raise SystemExit("--input and --output are required unless --self-test is used")

    dataset = load_dataset(args.input.expanduser().resolve())
    model = fit_model(dataset, args)
    output = args.output.expanduser().resolve()
    write_artifact(output, args.model_id, model)
    print_summary(model, output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
