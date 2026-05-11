import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import joblib
import numpy as np
import pandas as pd
from sqlalchemy import create_engine, text

from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

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

DEFAULT_VALIDATION_SETS = [
    {
        "name": "human_verified_150",
        "kind": "feature_score",
        "score_source": "HUMAN_VERIFIED",
        "score_model_version": "human_v1",
        "use_reason_json_weight": False,
    },
    {
        "name": "gpt_review_156",
        "kind": "label_review",
        "label_source": "GPT_PSEUDO",
        "label_batch_id": "GPT_review_v1",
        "use_reason_json_weight": True,
    },
    {
        "name": "deepseek_pro_408",
        "kind": "label_review",
        "label_source": "DEEPSEEK_PRO_PSEUDO",
        "label_batch_id": "DeepSeek_Pro_pseudo-label_batch",
        "use_reason_json_weight": True,
    },
]


def get_engine():
    return create_engine(SQLALCHEMY_DATABASE_URL, pool_pre_ping=True)


def as_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except Exception:
            return {}
    return {}


def calculate_rmse(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    return float(np.sqrt(mean_squared_error(y_true, y_pred)))


def safe_r2(y_true: np.ndarray, y_pred: np.ndarray) -> Optional[float]:
    if len(y_true) < 2:
        return None
    try:
        value = r2_score(y_true, y_pred)
        return float(value) if np.isfinite(value) else None
    except Exception:
        return None


def metric_dict(y_true: np.ndarray, y_pred: np.ndarray) -> Dict[str, Any]:
    abs_error = np.abs(y_pred - y_true)
    return {
        "count": int(len(y_true)),
        "mae": float(mean_absolute_error(y_true, y_pred)),
        "rmse": calculate_rmse(y_true, y_pred),
        "r2": safe_r2(y_true, y_pred),
        "mean_true": float(np.mean(y_true)),
        "mean_pred": float(np.mean(y_pred)),
        "mean_diff_pred_minus_true": float(np.mean(y_pred - y_true)),
        "p50_abs_error": float(np.percentile(abs_error, 50)),
        "p90_abs_error": float(np.percentile(abs_error, 90)),
    }


def base_profile_select() -> str:
    return """
        p.id AS persona_profile_id,
        p.age,
        p.age_group,
        p.gender,
        p.province,
        p.region,
        p.occupation,
        COALESCE(onorm.occupation_group, p.occupation) AS occupation_group,
        onorm.employment_status,
        onorm.occupation_domain,
        p.education_level,
        p.family_type,
        p.housing_type,
        p.persona_summary,
        p.search_text,
        p.interests,
        p.pain_points
    """


def score_select(alias: str, prefix: str = "") -> str:
    return ",\n        ".join([f"{alias}.{c} AS {prefix}{c}" for c in SCORE_COLUMNS])


def old_score_select(alias: str = "old_s") -> str:
    return ",\n        ".join([f"{alias}.{c} AS old_{c}" for c in SCORE_COLUMNS])


def load_feature_score_validation(engine, cfg: Dict[str, Any], old_score_source: str, old_score_model_version: str) -> pd.DataFrame:
    sql = f"""
    SELECT
        {base_profile_select()},
        {score_select("target_s")},
        target_s.score_source,
        target_s.score_model_version,
        NULL::text AS label_source,
        NULL::text AS label_batch_id,
        NULL::jsonb AS reason_json,
        {old_score_select("old_s")}
    FROM persona_feature_score target_s
    JOIN persona_profile p ON p.id = target_s.persona_profile_id
    LEFT JOIN persona_occupation_normalized onorm ON onorm.persona_profile_id = p.id
    LEFT JOIN persona_feature_score old_s
      ON old_s.persona_profile_id = target_s.persona_profile_id
     AND old_s.score_source = :old_score_source
     AND old_s.score_model_version = :old_score_model_version
    WHERE p.active = true
      AND target_s.score_source = :score_source
      AND target_s.score_model_version = :score_model_version
    """
    return pd.read_sql(
        text(sql),
        engine,
        params={
            "score_source": cfg["score_source"],
            "score_model_version": cfg["score_model_version"],
            "old_score_source": old_score_source,
            "old_score_model_version": old_score_model_version,
        },
    )


def load_label_review_validation(engine, cfg: Dict[str, Any], old_score_source: str, old_score_model_version: str) -> pd.DataFrame:
    sql = f"""
    SELECT
        {base_profile_select()},
        {score_select("lr")},
        NULL::text AS score_source,
        NULL::text AS score_model_version,
        lr.label_source,
        lr.label_batch_id,
        lr.reason_json,
        {old_score_select("old_s")}
    FROM persona_label_review lr
    JOIN persona_profile p ON p.id = lr.persona_profile_id
    LEFT JOIN persona_occupation_normalized onorm ON onorm.persona_profile_id = p.id
    LEFT JOIN persona_feature_score old_s
      ON old_s.persona_profile_id = lr.persona_profile_id
     AND old_s.score_source = :old_score_source
     AND old_s.score_model_version = :old_score_model_version
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
            "old_score_source": old_score_source,
            "old_score_model_version": old_score_model_version,
        },
    )


def load_validation_set(engine, cfg: Dict[str, Any], old_score_source: str, old_score_model_version: str) -> pd.DataFrame:
    if cfg["kind"] == "feature_score":
        return load_feature_score_validation(engine, cfg, old_score_source, old_score_model_version)
    if cfg["kind"] == "label_review":
        return load_label_review_validation(engine, cfg, old_score_source, old_score_model_version)
    raise ValueError(f"지원하지 않는 validation kind: {cfg['kind']}")


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


def predict_new_target(bundle: Dict[str, Any], target: str, x: pd.DataFrame) -> Optional[np.ndarray]:
    model = (bundle.get("models") or {}).get(target)
    if model is None:
        return None
    return np.clip(np.asarray(model.predict(x), dtype=float), 0, 100)


def get_train_weight(row: pd.Series, target: str) -> float:
    reason = as_dict(row.get("reason_json"))
    train_weight = reason.get("train_weight", {}) or {}
    try:
        return float(train_weight.get(target, 0.0) or 0.0)
    except Exception:
        return 0.0


def evaluate_validation_set(new_bundle: Dict[str, Any], df: pd.DataFrame, use_trainable_only: bool) -> Dict[str, Any]:
    prepared = prepare_features(df)
    x = prepared[NUMERIC_FEATURES + CATEGORICAL_FEATURES + [TEXT_FEATURE]]

    result = {}
    for target in SCORE_COLUMNS:
        y = pd.to_numeric(prepared[target], errors="coerce").astype(float).values
        old_pred = pd.to_numeric(prepared[f"old_{target}"], errors="coerce").astype(float).values
        new_pred = predict_new_target(new_bundle, target, x)

        if new_pred is None:
            result[target] = {"skipped": True, "reason": "new_model_missing"}
            continue

        base_mask = np.isfinite(y) & np.isfinite(old_pred) & np.isfinite(new_pred)

        if use_trainable_only:
            weights = prepared.apply(lambda row: get_train_weight(row, target), axis=1).astype(float).values
            mask = base_mask & np.isfinite(weights) & (weights > 0)
        else:
            mask = base_mask

        if mask.sum() == 0:
            result[target] = {"skipped": True, "reason": "no_valid_rows"}
            continue

        y_valid = np.clip(y[mask], 0, 100)
        old_valid = np.clip(old_pred[mask], 0, 100)
        new_valid = np.clip(new_pred[mask], 0, 100)

        old_metrics = metric_dict(y_valid, old_valid)
        new_metrics = metric_dict(y_valid, new_valid)

        result[target] = {
            "skipped": False,
            "count": int(mask.sum()),
            "old": old_metrics,
            "new": new_metrics,
            "comparison": {
                "mae_delta_new_minus_old": float(new_metrics["mae"] - old_metrics["mae"]),
                "rmse_delta_new_minus_old": float(new_metrics["rmse"] - old_metrics["rmse"]),
                "r2_delta_new_minus_old": (
                    None
                    if old_metrics.get("r2") is None or new_metrics.get("r2") is None
                    else float(new_metrics["r2"] - old_metrics["r2"])
                ),
                "new_better_by_mae": bool(new_metrics["mae"] < old_metrics["mae"]),
                "new_better_by_rmse": bool(new_metrics["rmse"] < old_metrics["rmse"]),
            },
        }

    return result


def flatten(result: Dict[str, Any]) -> pd.DataFrame:
    rows = []
    for validation_name, data in result["validation_sets"].items():
        for target, item in data.get("scores", {}).items():
            if item.get("skipped"):
                rows.append({
                    "validation_set": validation_name,
                    "score": target,
                    "row_count": data.get("row_count"),
                    "eval_count": 0,
                    "skipped": True,
                    "reason": item.get("reason"),
                })
                continue

            old = item["old"]
            new = item["new"]
            comp = item["comparison"]
            rows.append({
                "validation_set": validation_name,
                "score": target,
                "row_count": data.get("row_count"),
                "eval_count": item["count"],
                "old_mae": old["mae"],
                "new_mae": new["mae"],
                "mae_delta_new_minus_old": comp["mae_delta_new_minus_old"],
                "old_rmse": old["rmse"],
                "new_rmse": new["rmse"],
                "rmse_delta_new_minus_old": comp["rmse_delta_new_minus_old"],
                "old_r2": old["r2"],
                "new_r2": new["r2"],
                "r2_delta_new_minus_old": comp["r2_delta_new_minus_old"],
                "old_mean_pred": old["mean_pred"],
                "new_mean_pred": new["mean_pred"],
                "mean_true": new["mean_true"],
                "old_bias": old["mean_diff_pred_minus_true"],
                "new_bias": new["mean_diff_pred_minus_true"],
                "new_better_by_mae": comp["new_better_by_mae"],
                "new_better_by_rmse": comp["new_better_by_rmse"],
            })
    return pd.DataFrame(rows)


def load_validation_config(path: Optional[str]) -> List[Dict[str, Any]]:
    if not path:
        return DEFAULT_VALIDATION_SETS
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--new-model-path",
        default=str(MODEL_DIR / "persona_score_ridge_v2_mixed_gpt_pro_flash.joblib"),
    )
    parser.add_argument("--old-score-source", default="ML_PREDICTED")
    parser.add_argument("--old-score-model-version", default="persona_score_ridge_v1")
    parser.add_argument("--output-dir", default="persona_pipeline_output/model_compare")
    parser.add_argument("--validation-config", default=None)
    parser.add_argument(
        "--trainable-only",
        action="store_true",
        help="GPT/Pro 검증셋에서 train_weight > 0인 score만 비교한다.",
    )
    args = parser.parse_args()

    new_model_path = Path(args.new_model_path)
    if not new_model_path.exists():
        raise FileNotFoundError(f"신규 모델 파일을 찾을 수 없습니다: {new_model_path}")

    new_bundle = joblib.load(new_model_path)
    validation_sets = load_validation_config(args.validation_config)
    engine = get_engine()

    result = {
        "created_at": datetime.now().isoformat(),
        "old_score_source": args.old_score_source,
        "old_score_model_version": args.old_score_model_version,
        "new_model_version": new_bundle.get("model_version"),
        "trainable_only": bool(args.trainable_only),
        "validation_sets": {},
    }

    for cfg in validation_sets:
        name = cfg["name"]
        df = load_validation_set(engine, cfg, args.old_score_source, args.old_score_model_version)
        print(f"[{name}] loaded rows={len(df):,}")

        scores = evaluate_validation_set(
            new_bundle,
            df,
            use_trainable_only=bool(args.trainable_only and cfg.get("use_reason_json_weight")),
        )

        result["validation_sets"][name] = {
            "config": cfg,
            "row_count": int(len(df)),
            "scores": scores,
        }

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    suffix = "trainable_only" if args.trainable_only else "all"
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")

    json_path = output_dir / f"model_compare_old_db_vs_new_{suffix}_{ts}.json"
    csv_path = output_dir / f"model_compare_old_db_vs_new_{suffix}_{ts}.csv"

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    flat = flatten(result)
    flat.to_csv(csv_path, index=False, encoding="utf-8-sig")

    print("모델 비교 완료")
    print(json_path)
    print(csv_path)

    if not flat.empty:
        cols = [
            "validation_set",
            "score",
            "eval_count",
            "old_mae",
            "new_mae",
            "mae_delta_new_minus_old",
            "new_better_by_mae",
        ]
        print(flat[cols].to_string(index=False))


if __name__ == "__main__":
    main()
