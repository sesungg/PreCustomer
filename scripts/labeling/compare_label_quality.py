import json
import argparse
from datetime import datetime
from typing import Any, Dict

import pandas as pd
from sqlalchemy import create_engine, text

from config import SQLALCHEMY_DATABASE_URL, SCORE_COLUMNS, COMPARE_DIR


def get_engine():
    return create_engine(SQLALCHEMY_DATABASE_URL, pool_pre_ping=True)


def load_reviews(engine, label_batch_id: str, label_source: str) -> pd.DataFrame:
    score_select = ",\n        ".join([f"lr.{c}" for c in SCORE_COLUMNS])
    sql = f"""
    SELECT
        lr.id AS label_review_id,
        lr.persona_profile_id,
        lr.label_batch_id,
        lr.label_source,
        p.age_group,
        p.gender,
        p.occupation,
        COALESCE(onorm.occupation_group, p.occupation) AS occupation_group,
        p.family_type,
        p.region,
        {score_select},
        lr.reason_json
    FROM persona_label_review lr
    JOIN persona_profile p ON p.id = lr.persona_profile_id
    LEFT JOIN persona_occupation_normalized onorm ON onorm.persona_profile_id = p.id
    WHERE lr.label_batch_id = :label_batch_id
      AND lr.label_source = :label_source
    """
    return pd.read_sql(text(sql), engine, params={"label_batch_id": label_batch_id, "label_source": label_source})


def load_old_new_scores(engine, old_source: str, old_version: str, new_source: str, new_version: str) -> pd.DataFrame:
    old_cols = ",\n        ".join([f"old.{c} AS old_{c}" for c in SCORE_COLUMNS])
    new_cols = ",\n        ".join([f"new.{c} AS new_{c}" for c in SCORE_COLUMNS])
    sql = f"""
    SELECT
        old.persona_profile_id,
        {old_cols},
        {new_cols}
    FROM persona_feature_score old
    JOIN persona_feature_score new
      ON new.persona_profile_id = old.persona_profile_id
    WHERE old.score_source = :old_source
      AND old.score_model_version = :old_version
      AND new.score_source = :new_source
      AND new.score_model_version = :new_version
    """
    return pd.read_sql(text(sql), engine, params={
        "old_source": old_source,
        "old_version": old_version,
        "new_source": new_source,
        "new_version": new_version,
    })


def as_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except Exception:
            return {}
    return {}


def add_reason_json_columns(df: pd.DataFrame) -> pd.DataFrame:
    result = df.copy()
    for col in SCORE_COLUMNS:
        result[f"confidence_{col}"] = result["reason_json"].apply(
            lambda x: as_dict(x).get("confidence", {}).get(col)
        )
        result[f"status_{col}"] = result["reason_json"].apply(
            lambda x: as_dict(x).get("label_status", {}).get(col, "UNKNOWN")
        )
        result[f"weight_{col}"] = result["reason_json"].apply(
            lambda x: as_dict(x).get("train_weight", {}).get(col, 0.0)
        )
    return result


def summarize(df: pd.DataFrame) -> Dict[str, Any]:
    summary = {"row_count": int(len(df)), "scores": {}}
    for col in SCORE_COLUMNS:
        status_counts = df[f"status_{col}"].value_counts(dropna=False).to_dict()
        summary["scores"][col] = {
            "status_counts": {str(k): int(v) for k, v in status_counts.items()},
            "mean_confidence": float(pd.to_numeric(df[f"confidence_{col}"], errors="coerce").mean()),
            "mean_train_weight": float(pd.to_numeric(df[f"weight_{col}"], errors="coerce").mean()),
            "trainable_count": int((pd.to_numeric(df[f"weight_{col}"], errors="coerce") > 0).sum()),
        }
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label-batch-id", required=True)
    parser.add_argument("--label-source", default="DEEPSEEK_PRO")
    parser.add_argument("--old-source", default=None)
    parser.add_argument("--old-version", default=None)
    parser.add_argument("--new-source", default=None)
    parser.add_argument("--new-version", default=None)
    args = parser.parse_args()

    engine = get_engine()
    df = load_reviews(engine, args.label_batch_id, args.label_source)
    if df.empty:
        raise RuntimeError("분석할 persona_label_review 데이터가 없습니다.")

    df = add_reason_json_columns(df)

    if all([args.old_source, args.old_version, args.new_source, args.new_version]):
        diff = load_old_new_scores(engine, args.old_source, args.old_version, args.new_source, args.new_version)
        if not diff.empty:
            df = df.merge(diff, on="persona_profile_id", how="left")
            for col in SCORE_COLUMNS:
                if f"old_{col}" in df.columns and f"new_{col}" in df.columns:
                    df[f"diff_{col}"] = df[f"new_{col}"] - df[f"old_{col}"]
                    df[f"abs_diff_{col}"] = df[f"diff_{col}"].abs()

    summary = summarize(df)
    now = datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = COMPARE_DIR / f"label_quality_{args.label_batch_id}_{now}.csv"
    json_path = COMPARE_DIR / f"label_quality_{args.label_batch_id}_{now}.json"

    df.to_csv(csv_path, index=False, encoding="utf-8-sig")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("분석 완료")
    print(csv_path)
    print(json_path)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
