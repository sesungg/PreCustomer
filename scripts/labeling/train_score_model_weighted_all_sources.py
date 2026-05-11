#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
모든 라벨 소스를 함께 사용하는 score model 학습 스크립트.

우선순위:
Human > GPT > DeepSeek Pro > Flash v3 partial > Flash v1

중요:
- 같은 persona_profile_id가 여러 source에 있으면 높은 우선순위 source만 사용한다.
- 높은 우선순위 source에서 특정 score가 UNKNOWN이면 하위 source로 fallback하지 않는다.
- use_reason_json_weight=True인 source는 reason_json.train_weight를 사용한다.
"""

import os
import json
import argparse
from datetime import datetime
from typing import Any, Dict, List

import joblib
import numpy as np
import pandas as pd
from sqlalchemy import create_engine, text

from sklearn.compose import ColumnTransformer
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from config import SQLALCHEMY_DATABASE_URL, SCORE_COLUMNS, MODEL_DIR


FEATURE_TEXT_COLUMNS = ["occupation", "persona_summary", "search_text", "interests", "pain_points"]
NUMERIC_FEATURES = ["age"]
CATEGORICAL_FEATURES = [
    "age_group",
    "gender",
    "province",
    "region",
    "occupation_group",
    "employment_status",
    "occupation_domain",
    "education_level",
    "family_type",
    "housing_type",
]
TEXT_FEATURE = "combined_text"


DEFAULT_LABEL_CONFIG = [
    {
        "source_key": "human_v1",
        "kind": "feature_score",
        "score_source": "HUMAN_VERIFIED",
        "score_model_version": "human_v1",
        "priority": 500,
        "base_weight": 1.20,
        "use_reason_json_weight": False,
    },
    {
        "source_key": "gpt_5_5_xhigh",
        "kind": "label_review",
        "label_source": "GPT_PSEUDO",
        "label_batch_id": "GPT_review_v1",
        "priority": 400,
        "base_weight": 1.00,
        "use_reason_json_weight": True,
    },
    {
        "source_key": "deepseek_v4_pro",
        "kind": "label_review",
        "label_source": "DEEPSEEK_PRO_PSEUDO",
        "label_batch_id": "DeepSeek_Pro_pseudo-label_batch",
        "priority": 300,
        "base_weight": 0.65,
        "use_reason_json_weight": True,
    },
    {
        "source_key": "deepseek_v4_flash_v3_partial",
        "kind": "feature_score",
        "score_source": "DEEPSEEK_PSEUDO",
        "score_model_version": "deepseek_v4_flash_prompt_v3_partial",
        "label_source": "DEEPSEEK_PSEUDO",
        "label_batch_id": "deepseek_flash_more_partial_v3",
        "priority": 200,
        "base_weight": 0.25,
        "use_reason_json_weight": True,
        "score_weight_overrides": {
            "trust_sensitivity_score": 0.18,
            "health_safety_sensitivity_score": 0.18,
            "review_dependency_score": 0.08,
        },
    },
    {
        "source_key": "deepseek_v4_flash_v1",
        "kind": "feature_score",
        "score_source": "DEEPSEEK_PSEUDO",
        "score_model_version": "deepseek_v4_flash_prompt_v1",
        "label_source": "DEEPSEEK_PSEUDO",
        "label_batch_id": "deepseek_3000_v1",
        "priority": 100,
        "base_weight": 0.18,
        "use_reason_json_weight": True,
        "score_weight_overrides": {
            "trust_sensitivity_score": 0.12,
            "health_safety_sensitivity_score": 0.12,
            "review_dependency_score": 0.04,
        },
    },
]


def get_engine():
    return create_engine(SQLALCHEMY_DATABASE_URL, pool_pre_ping=True)


def parse_label_config() -> List[Dict[str, Any]]:
    raw = os.getenv("LABEL_CONFIG_JSON")
    if not raw:
        return DEFAULT_LABEL_CONFIG
    return json.loads(raw)


def score_select(alias: str) -> str:
    return ",\n        ".join([f"{alias}.{c} AS {c}" for c in SCORE_COLUMNS])


def base_profile_select() -> str:
    return """
        p.id AS persona_profile_id,
        p.age,
        p.age_group,
        p.gender,
        p.province,
        p.region,
        p.occupation,
        COALESCE(onorm.occupation_group, p.occupation, 'UNKNOWN') AS occupation_group,
        COALESCE(onorm.employment_status, 'UNKNOWN') AS employment_status,
        COALESCE(onorm.occupation_domain, 'UNKNOWN') AS occupation_domain,
        p.education_level,
        p.family_type,
        p.housing_type,
        p.persona_summary,
        p.search_text,
        p.interests,
        p.pain_points
    """


def load_feature_score_source(engine, cfg: Dict[str, Any]) -> pd.DataFrame:
    label_join_extra = ""
    params = {
        "score_source": cfg["score_source"],
        "score_model_version": cfg["score_model_version"],
    }

    if cfg.get("label_source"):
        label_join_extra += " AND lr.label_source = :label_source"
        params["label_source"] = cfg["label_source"]

    if cfg.get("label_batch_id"):
        label_join_extra += " AND lr.label_batch_id = :label_batch_id"
        params["label_batch_id"] = cfg["label_batch_id"]

    sql = f"""
    SELECT
        {base_profile_select()},
        {score_select("s")},
        s.score_source,
        s.score_model_version,
        lr.label_source,
        lr.label_batch_id,
        lr.reason_json
    FROM persona_feature_score s
    JOIN persona_profile p
      ON p.id = s.persona_profile_id
    LEFT JOIN persona_occupation_normalized onorm
      ON onorm.persona_profile_id = p.id
    LEFT JOIN persona_label_review lr
      ON lr.persona_profile_id = s.persona_profile_id
     {label_join_extra}
    WHERE p.active = true
      AND s.score_source = :score_source
      AND s.score_model_version = :score_model_version
    """

    return pd.read_sql(text(sql), engine, params=params)


def load_label_review_source(engine, cfg: Dict[str, Any]) -> pd.DataFrame:
    sql = f"""
    SELECT
        {base_profile_select()},
        {score_select("lr")},
        NULL::text AS score_source,
        NULL::text AS score_model_version,
        lr.label_source,
        lr.label_batch_id,
        lr.reason_json
    FROM persona_label_review lr
    JOIN persona_profile p
      ON p.id = lr.persona_profile_id
    LEFT JOIN persona_occupation_normalized onorm
      ON onorm.persona_profile_id = p.id
    WHERE p.active = true
      AND lr.label_source = :label_source
      AND lr.label_batch_id = :label_batch_id
    """

    return pd.read_sql(
        text(sql),
        engine,
        params={
            "label_source": cfg["label_source"],
            "label_batch_id": cfg["label_batch_id"],
        },
    )


def load_training_candidates(engine, label_config: List[Dict[str, Any]]) -> pd.DataFrame:
    frames = []

    for cfg in label_config:
        kind = cfg["kind"]
        if kind == "feature_score":
            df = load_feature_score_source(engine, cfg)
        elif kind == "label_review":
            df = load_label_review_source(engine, cfg)
        else:
            raise ValueError(f"지원하지 않는 kind: {kind}")

        if df.empty:
            print(f"[WARN] source empty: {cfg.get('source_key')}")
            continue

        df["source_key"] = cfg["source_key"]
        df["source_priority"] = int(cfg["priority"])
        df["base_weight"] = float(cfg.get("base_weight", 1.0))
        df["use_reason_json_weight"] = bool(cfg.get("use_reason_json_weight", False))
        df["score_weight_overrides"] = [cfg.get("score_weight_overrides", {}) for _ in range(len(df))]

        print(f"[LOAD] {cfg['source_key']}: rows={len(df):,}")
        frames.append(df)

    if not frames:
        return pd.DataFrame()

    all_df = pd.concat(frames, ignore_index=True)

    # 같은 persona_profile_id가 여러 source에 있으면 높은 우선순위 source만 사용한다.
    # score별 fallback을 하지 않는다. GPT가 UNKNOWN이라고 하위 Flash로 대체하면 검수 의미가 깨진다.
    all_df = all_df.sort_values(
        by=["persona_profile_id", "source_priority"],
        ascending=[True, False],
    )
    deduped = all_df.drop_duplicates(subset=["persona_profile_id"], keep="first").reset_index(drop=True)

    dropped = len(all_df) - len(deduped)
    if dropped:
        print(f"[DEDUP] dropped lower-priority duplicate rows={dropped:,}")

    print("[SOURCE COUNTS AFTER DEDUP]")
    print(deduped["source_key"].value_counts().to_string())

    return deduped


def as_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except Exception:
            return {}
    return {}


def get_score_base_weight(row: pd.Series, target: str) -> float:
    overrides = as_dict(row.get("score_weight_overrides"))
    if target in overrides:
        return float(overrides[target])
    return float(row.get("base_weight", 1.0))


def get_target_weight(row: pd.Series, target: str, mode: str) -> float:
    base_weight = get_score_base_weight(row, target)

    if not bool(row.get("use_reason_json_weight", False)):
        return base_weight

    reason = as_dict(row.get("reason_json"))
    train_weight = reason.get("train_weight", {}) or {}
    label_status = reason.get("label_status", {}) or {}

    raw_weight = float(train_weight.get(target, 0.0) or 0.0)
    status = label_status.get(target, "UNKNOWN")

    if mode == "strong_only":
        return base_weight if status == "STRONG" and raw_weight > 0 else 0.0

    if mode == "strong_weak":
        return base_weight * raw_weight

    raise ValueError(f"알 수 없는 training mode: {mode}")


def prepare_features(df: pd.DataFrame) -> pd.DataFrame:
    prepared = df.copy()
    for col in FEATURE_TEXT_COLUMNS:
        if col not in prepared.columns:
            prepared[col] = ""
        prepared[col] = prepared[col].fillna("").astype(str)
    prepared[TEXT_FEATURE] = prepared[FEATURE_TEXT_COLUMNS].agg(" ".join, axis=1)

    for col in CATEGORICAL_FEATURES:
        if col not in prepared.columns:
            prepared[col] = "UNKNOWN"
        prepared[col] = prepared[col].fillna("UNKNOWN").astype(str)

    if "age" not in prepared.columns:
        prepared["age"] = np.nan
    prepared["age"] = pd.to_numeric(prepared["age"], errors="coerce")
    return prepared


def calculate_rmse(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    mse = mean_squared_error(y_true, y_pred)
    return float(np.sqrt(mse))


def build_pipeline() -> Pipeline:
    numeric_pipeline = Pipeline([
        ("imputer", SimpleImputer(strategy="median")),
        ("scaler", StandardScaler()),
    ])

    preprocessor = ColumnTransformer(
        transformers=[
            ("num", numeric_pipeline, NUMERIC_FEATURES),
            ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL_FEATURES),
            ("text", TfidfVectorizer(max_features=5000, ngram_range=(1, 2), min_df=2, max_df=0.95), TEXT_FEATURE),
        ],
        remainder="drop",
    )

    return Pipeline([
        ("preprocess", preprocessor),
        ("model", Ridge(alpha=10.0, solver="lsqr", max_iter=10000, tol=1e-4)),
    ])


def train_models(df: pd.DataFrame, mode: str, min_rows_per_score: int) -> Dict[str, Any]:
    df = prepare_features(df)

    models = {}
    metrics = {}
    training_counts = {}

    for target in SCORE_COLUMNS:
        weights = df.apply(lambda row: get_target_weight(row, target, mode), axis=1).astype(float)
        target_df = df[weights > 0].copy()
        target_weights = weights[weights > 0].astype(float).values

        target_df[target] = pd.to_numeric(target_df[target], errors="coerce")
        valid_mask = (
            np.isfinite(target_df[target].astype(float).values)
            & np.isfinite(target_weights)
            & (target_weights > 0)
        )
        target_df = target_df.loc[valid_mask].copy()
        target_weights = target_weights[valid_mask]

        training_counts[target] = int(len(target_df))
        source_counts = target_df["source_key"].value_counts().to_dict() if "source_key" in target_df.columns else {}

        print()
        print(f"[{target}] trainable rows={len(target_df):,}")
        print(f"[{target}] source_counts={source_counts}")

        if len(target_df) < min_rows_per_score:
            print(f"[{target}] SKIP: 학습 row 부족")
            metrics[target] = {
                "skipped": True,
                "reason": "not_enough_rows",
                "trainable_count": int(len(target_df)),
                "source_counts": {str(k): int(v) for k, v in source_counts.items()},
            }
            continue

        x = target_df[NUMERIC_FEATURES + CATEGORICAL_FEATURES + [TEXT_FEATURE]]
        y = np.clip(target_df[target].astype(float).values, 0, 100)

        train_idx, test_idx = train_test_split(
            np.arange(len(target_df)),
            test_size=0.2,
            random_state=42,
            shuffle=True,
        )
        x_train = x.iloc[train_idx]
        x_test = x.iloc[test_idx]
        y_train = y[train_idx]
        y_test = y[test_idx]
        w_train = target_weights[train_idx]

        model = build_pipeline()
        model.fit(x_train, y_train, model__sample_weight=w_train)
        pred = np.clip(model.predict(x_test), 0, 100)

        rmse = calculate_rmse(y_test, pred)
        mae = mean_absolute_error(y_test, pred)
        try:
            r2 = r2_score(y_test, pred)
        except Exception:
            r2 = None

        models[target] = model
        metrics[target] = {
            "skipped": False,
            "trainable_count": int(len(target_df)),
            "source_counts": {str(k): int(v) for k, v in source_counts.items()},
            "mean_effective_weight": float(np.mean(target_weights)),
            "rmse": float(rmse),
            "mae": float(mae),
            "r2": float(r2) if r2 is not None else None,
            "test_count": int(len(y_test)),
        }
        print(f"[{target}] RMSE={rmse:.3f}, MAE={mae:.3f}, R2={r2}")

    return {
        "models": models,
        "metrics": metrics,
        "training_counts": training_counts,
        "score_columns": SCORE_COLUMNS,
        "feature_columns": NUMERIC_FEATURES + CATEGORICAL_FEATURES + [TEXT_FEATURE],
        "training_mode": mode,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--mode", choices=["strong_only", "strong_weak"], default="strong_weak")
    parser.add_argument("--min-rows-per-score", type=int, default=100)
    args = parser.parse_args()

    label_config = parse_label_config()
    engine = get_engine()
    df = load_training_candidates(engine, label_config)

    print()
    print(f"후보 학습 데이터 after priority dedup: {len(df):,}")
    if df.empty:
        raise RuntimeError("학습 후보 데이터가 없습니다.")

    bundle = train_models(df, args.mode, args.min_rows_per_score)
    bundle["model_version"] = args.model_version
    bundle["label_config"] = label_config
    bundle["created_at"] = datetime.now().isoformat()

    output_path = MODEL_DIR / f"{args.model_version}.joblib"
    metrics_path = MODEL_DIR / f"{args.model_version}_metrics.json"

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, output_path)
    with open(metrics_path, "w", encoding="utf-8") as f:
        json.dump(bundle["metrics"], f, ensure_ascii=False, indent=2)

    print()
    print("모델 저장 완료")
    print(output_path)
    print(metrics_path)


if __name__ == "__main__":
    main()
