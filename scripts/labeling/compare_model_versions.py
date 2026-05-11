import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

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
    mse = mean_squared_error(y_true, y_pred)
    return float(np.sqrt(mse))


def safe_r2(y_true: np.ndarray, y_pred: np.ndarray) -> Optional[float]:
    if len(y_true) < 2:
        return None
    try:
        value = r2_score(y_true, y_pred)
        if np.isfinite(value):
            return float(value)
        return None
    except Exception:
        return None


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


def score_select(alias: str = "src") -> str:
    return ",\n        ".join([f"{alias}.{c}" for c in SCORE_COLUMNS])


def load_feature_score_validation(engine, cfg: Dict[str, Any]) -> pd.DataFrame:
    sql = f"""
    SELECT
        {base_profile_select()},
        {score_select("s")},
        s.score_source,
        s.score_model_version,
        NULL::text AS label_source,
        NULL::text AS label_batch_id,
        NULL::jsonb AS reason_json
    FROM persona_feature_score s
    JOIN persona_profile p ON p.id = s.persona_profile_id
    LEFT JOIN persona_occupation_normalized onorm ON onorm.persona_profile_id = p.id
    WHERE p.active = true
      AND s.score_source = :score_source
      AND s.score_model_version = :score_model_version
    """
    return pd.read_sql(
        text(sql),
        engine,
        params={
            "score_source": cfg["score_source"],
            "score_model_version": cfg["score_model_version"],
        },
    )


def load_label_review_validation(engine, cfg: Dict[str, Any]) -> pd.DataFrame:
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
    JOIN persona_profile p ON p.id = lr.persona_profile_id
    LEFT JOIN persona_occupation_normalized onorm ON onorm.persona_profile_id = p.id
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


def load_validation_set(engine, cfg: Dict[str, Any]) -> pd.DataFrame:
    kind = cfg["kind"]
    if kind == "feature_score":
        return load_feature_score_validation(engine, cfg)
    if kind == "label_review":
        return load_label_review_validation(engine, cfg)
    raise ValueError(f"지원하지 않는 validation kind: {kind}")


def get_train_weight(row: pd.Series, target: str) -> float:
    reason = as_dict(row.get("reason_json"))
    train_weight = reason.get("train_weight", {}) or {}
    try:
        return float(train_weight.get(target, 0.0) or 0.0)
    except Exception:
        return 0.0


def predict_target(bundle: Dict[str, Any], target: str, x: pd.DataFrame) -> Optional[np.ndarray]:
    models = bundle.get("models", {})
    model = models.get(target)
    if model is None:
        return None
    pred = model.predict(x)
    return np.clip(np.asarray(pred, dtype=float), 0, 100)


def metric_dict(y_true: np.ndarray, y_pred: np.ndarray) -> Dict[str, Any]:
    return {
        "count": int(len(y_true)),
        "mae": float(mean_absolute_error(y_true, y_pred)),
        "rmse": calculate_rmse(y_true, y_pred),
        "r2": safe_r2(y_true, y_pred),
        "mean_true": float(np.mean(y_true)),
        "mean_pred": float(np.mean(y_pred)),
        "mean_diff_pred_minus_true": float(np.mean(y_pred - y_true)),
        "p50_abs_error": float(np.percentile(np.abs(y_pred - y_true), 50)),
        "p90_abs_error": float(np.percentile(np.abs(y_pred - y_true), 90)),
    }


def evaluate_one_model(
    bundle: Dict[str, Any],
    model_name: str,
    validation_df: pd.DataFrame,
    use_reason_json_weight: bool,
) -> Dict[str, Any]:
    prepared = prepare_features(validation_df)
    x_all = prepared[NUMERIC_FEATURES + CATEGORICAL_FEATURES + [TEXT_FEATURE]]

    result: Dict[str, Any] = {}

    for target in SCORE_COLUMNS:
        if target not in prepared.columns:
            result[target] = {"skipped": True, "reason": "target_missing"}
            continue

        y = pd.to_numeric(prepared[target], errors="coerce").astype(float)
        valid_mask = np.isfinite(y.values)

        pred = predict_target(bundle, target, x_all)
        if pred is None:
            result[target] = {"skipped": True, "reason": "model_missing"}
            continue

        valid_mask = valid_mask & np.isfinite(pred)

        if valid_mask.sum() == 0:
            result[target] = {"skipped": True, "reason": "no_valid_rows"}
            continue

        y_valid = np.clip(y.values[valid_mask], 0, 100)
        pred_valid = pred[valid_mask]

        item: Dict[str, Any] = {
            "skipped": False,
            "all": metric_dict(y_valid, pred_valid),
        }

        if use_reason_json_weight:
            weights = prepared.apply(lambda row: get_train_weight(row, target), axis=1).astype(float).values
            trainable_mask = valid_mask & np.isfinite(weights) & (weights > 0)
            if trainable_mask.sum() > 0:
                item["trainable_only"] = metric_dict(
                    np.clip(y.values[trainable_mask], 0, 100),
                    pred[trainable_mask],
                )
                item["trainable_count"] = int(trainable_mask.sum())
            else:
                item["trainable_only"] = None
                item["trainable_count"] = 0

        result[target] = item

    return result


def compare_models(
    old_bundle: Dict[str, Any],
    new_bundle: Dict[str, Any],
    validation_sets: List[Dict[str, Any]],
) -> Dict[str, Any]:
    engine = get_engine()
    output: Dict[str, Any] = {
        "created_at": datetime.now().isoformat(),
        "old_model_version": old_bundle.get("model_version"),
        "new_model_version": new_bundle.get("model_version"),
        "validation_sets": {},
    }

    for cfg in validation_sets:
        name = cfg["name"]
        df = load_validation_set(engine, cfg)
        print(f"[{name}] loaded rows={len(df):,}")

        if df.empty:
            output["validation_sets"][name] = {
                "config": cfg,
                "row_count": 0,
                "error": "empty_validation_set",
            }
            continue

        old_metrics = evaluate_one_model(
            old_bundle,
            "old",
            df,
            use_reason_json_weight=bool(cfg.get("use_reason_json_weight", False)),
        )
        new_metrics = evaluate_one_model(
            new_bundle,
            "new",
            df,
            use_reason_json_weight=bool(cfg.get("use_reason_json_weight", False)),
        )

        comparison = {}
        for target in SCORE_COLUMNS:
            old_all = old_metrics.get(target, {}).get("all")
            new_all = new_metrics.get(target, {}).get("all")
            if old_all and new_all:
                comparison[target] = {
                    "mae_delta_new_minus_old": float(new_all["mae"] - old_all["mae"]),
                    "rmse_delta_new_minus_old": float(new_all["rmse"] - old_all["rmse"]),
                    "r2_delta_new_minus_old": (
                        None
                        if old_all.get("r2") is None or new_all.get("r2") is None
                        else float(new_all["r2"] - old_all["r2"])
                    ),
                    "new_better_by_mae": bool(new_all["mae"] < old_all["mae"]),
                    "new_better_by_rmse": bool(new_all["rmse"] < old_all["rmse"]),
                }

        output["validation_sets"][name] = {
            "config": cfg,
            "row_count": int(len(df)),
            "old": old_metrics,
            "new": new_metrics,
            "comparison": comparison,
        }

    return output


def flatten_comparison(result: Dict[str, Any]) -> pd.DataFrame:
    rows = []
    for val_name, val_data in result.get("validation_sets", {}).items():
        for target in SCORE_COLUMNS:
            old_item = val_data.get("old", {}).get(target, {})
            new_item = val_data.get("new", {}).get(target, {})
            comp = val_data.get("comparison", {}).get(target, {})

            old_all = old_item.get("all") if not old_item.get("skipped") else None
            new_all = new_item.get("all") if not new_item.get("skipped") else None

            row = {
                "validation_set": val_name,
                "score": target,
                "row_count": val_data.get("row_count"),
                "old_mae": old_all.get("mae") if old_all else None,
                "new_mae": new_all.get("mae") if new_all else None,
                "mae_delta_new_minus_old": comp.get("mae_delta_new_minus_old"),
                "old_rmse": old_all.get("rmse") if old_all else None,
                "new_rmse": new_all.get("rmse") if new_all else None,
                "rmse_delta_new_minus_old": comp.get("rmse_delta_new_minus_old"),
                "old_r2": old_all.get("r2") if old_all else None,
                "new_r2": new_all.get("r2") if new_all else None,
                "r2_delta_new_minus_old": comp.get("r2_delta_new_minus_old"),
                "new_better_by_mae": comp.get("new_better_by_mae"),
                "new_better_by_rmse": comp.get("new_better_by_rmse"),
            }

            old_trainable = old_item.get("trainable_only")
            new_trainable = new_item.get("trainable_only")
            if old_trainable and new_trainable:
                row.update({
                    "old_trainable_mae": old_trainable.get("mae"),
                    "new_trainable_mae": new_trainable.get("mae"),
                    "trainable_count": new_item.get("trainable_count"),
                })

            rows.append(row)

    return pd.DataFrame(rows)


def load_validation_config(path: Optional[str]) -> List[Dict[str, Any]]:
    if not path:
        return DEFAULT_VALIDATION_SETS
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--old-model-path",
        default=str(MODEL_DIR / "persona_score_ridge_v1.joblib"),
        help="기존 모델 joblib 경로",
    )
    parser.add_argument(
        "--new-model-path",
        default=str(MODEL_DIR / "persona_score_ridge_v2_mixed_gpt_pro_flash.joblib"),
        help="신규 모델 joblib 경로",
    )
    parser.add_argument(
        "--output-dir",
        default="persona_pipeline_output/model_compare",
        help="비교 결과 저장 디렉토리",
    )
    parser.add_argument(
        "--validation-config",
        default=None,
        help="검증셋 설정 JSON 파일 경로. 생략 시 기본 HUMAN/GPT/PRO 설정 사용",
    )
    args = parser.parse_args()

    old_model_path = Path(args.old_model_path)
    new_model_path = Path(args.new_model_path)

    if not old_model_path.exists():
        raise FileNotFoundError(f"기존 모델 파일을 찾을 수 없습니다: {old_model_path}")
    if not new_model_path.exists():
        raise FileNotFoundError(f"신규 모델 파일을 찾을 수 없습니다: {new_model_path}")

    old_bundle = joblib.load(old_model_path)
    new_bundle = joblib.load(new_model_path)

    validation_sets = load_validation_config(args.validation_config)
    result = compare_models(old_bundle, new_bundle, validation_sets)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    json_path = output_dir / f"model_compare_{ts}.json"
    csv_path = output_dir / f"model_compare_{ts}.csv"

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    flat = flatten_comparison(result)
    flat.to_csv(csv_path, index=False, encoding="utf-8-sig")

    print("모델 비교 완료")
    print(json_path)
    print(csv_path)

    if not flat.empty:
        print("\n=== 요약: validation_set / score / old_mae / new_mae / delta ===")
        display_cols = [
            "validation_set",
            "score",
            "old_mae",
            "new_mae",
            "mae_delta_new_minus_old",
            "new_better_by_mae",
        ]
        print(flat[display_cols].to_string(index=False))


if __name__ == "__main__":
    main()
