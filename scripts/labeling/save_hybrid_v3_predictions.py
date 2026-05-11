#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
persona_score_hybrid_v3 저장 스크립트.

목적:
- 기존 DB의 v1 예측 점수를 읽는다.
- v3 모델(persona_score_ridge_v3_all_sources.joblib)로 개선된 score만 예측한다.
- v1 + v3를 섞어서 새 버전으로 persona_feature_score에 저장한다.

저장 버전:
  score_source        = ML_PREDICTED
  score_model_version = persona_score_hybrid_v3

hybrid_v3 기본 구성:
  v3 사용:
    price_sensitivity_score
    trust_sensitivity_score
    quality_sensitivity_score
    local_affinity_score
    review_dependency_score

  v1 유지:
    digital_affinity_score
    convenience_need_score
    novelty_acceptance_score
    family_decision_score
    health_safety_sensitivity_score

실행 전 dry-run:
  python3 save_hybrid_v3_predictions.py \
    --v3-model-path models/persona_score_ridge_v3_all_sources.joblib \
    --limit 10 \
    --dry-run

전체 저장:
  python3 save_hybrid_v3_predictions.py \
    --v3-model-path models/persona_score_ridge_v3_all_sources.joblib \
    --batch-size 5000

기존 hybrid_v3를 지우고 다시 저장:
  python3 save_hybrid_v3_predictions.py \
    --v3-model-path models/persona_score_ridge_v3_all_sources.joblib \
    --batch-size 5000 \
    --delete-existing

저장 확인:
  select score_source, score_model_version, count(*)
  from persona_feature_score
  where score_source = 'ML_PREDICTED'
    and score_model_version = 'persona_score_hybrid_v3'
  group by score_source, score_model_version;
"""

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

import joblib
import numpy as np
import pandas as pd
from sqlalchemy import create_engine, text
from psycopg2.extras import execute_values

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


DEFAULT_V3_SCORE_COLUMNS = [
    "price_sensitivity_score",
    "trust_sensitivity_score",
    "quality_sensitivity_score",
    "local_affinity_score",
    "review_dependency_score",
]


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)


def parse_csv_columns(value: Optional[str]) -> Optional[List[str]]:
    if not value:
        return None
    cols = [v.strip() for v in value.split(",") if v.strip()]
    unknown = sorted(set(cols) - set(SCORE_COLUMNS))
    if unknown:
        raise ValueError(f"알 수 없는 score column: {unknown}")
    return cols


def old_score_select(alias: str = "old_s") -> str:
    return ",\n        ".join([f"{alias}.{c} AS old_{c}" for c in SCORE_COLUMNS])


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


def get_engine():
    return create_engine(SQLALCHEMY_DATABASE_URL, pool_pre_ping=True)


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
        prepared.loc[prepared[col].str.strip() == "", col] = "UNKNOWN"

    if "age" not in prepared.columns:
        prepared["age"] = np.nan
    prepared["age"] = pd.to_numeric(prepared["age"], errors="coerce")

    return prepared


def fetch_batch(
    engine,
    last_id: int,
    batch_size: int,
    old_score_source: str,
    old_score_model_version: str,
    limit_remaining: Optional[int],
) -> pd.DataFrame:
    actual_batch_size = batch_size
    if limit_remaining is not None:
        actual_batch_size = min(batch_size, limit_remaining)
    if actual_batch_size <= 0:
        return pd.DataFrame()

    sql = f"""
    SELECT
        {base_profile_select()},
        {old_score_select("old_s")}
    FROM persona_profile p
    JOIN persona_feature_score old_s
      ON old_s.persona_profile_id = p.id
     AND old_s.score_source = :old_score_source
     AND old_s.score_model_version = :old_score_model_version
    LEFT JOIN persona_occupation_normalized onorm
      ON onorm.persona_profile_id = p.id
    WHERE p.active = true
      AND p.id > :last_id
    ORDER BY p.id
    LIMIT :batch_size
    """

    return pd.read_sql(
        text(sql),
        engine,
        params={
            "old_score_source": old_score_source,
            "old_score_model_version": old_score_model_version,
            "last_id": last_id,
            "batch_size": actual_batch_size,
        },
    )


def predict_v3(bundle: Dict[str, Any], prepared: pd.DataFrame, v3_score_columns: Sequence[str]) -> Dict[str, np.ndarray]:
    models = bundle.get("models") or {}
    x = prepared[NUMERIC_FEATURES + CATEGORICAL_FEATURES + [TEXT_FEATURE]]

    predictions: Dict[str, np.ndarray] = {}
    missing = []

    for score_col in v3_score_columns:
        model = models.get(score_col)
        if model is None:
            missing.append(score_col)
            continue
        pred = np.asarray(model.predict(x), dtype=float)
        predictions[score_col] = np.clip(pred, 0, 100)

    if missing:
        raise RuntimeError(f"v3 모델에 없는 score model: {missing}")

    return predictions


def make_hybrid_rows(
    df: pd.DataFrame,
    v3_predictions: Dict[str, np.ndarray],
    v3_score_columns: Sequence[str],
    target_score_source: str,
    target_score_model_version: str,
    round_scores: bool,
) -> List[Dict[str, Any]]:
    rows = []
    v3_set = set(v3_score_columns)

    for i, row in df.reset_index(drop=True).iterrows():
        item: Dict[str, Any] = {
            "persona_profile_id": int(row["persona_profile_id"]),
            "score_source": target_score_source,
            "score_model_version": target_score_model_version,
        }

        for score_col in SCORE_COLUMNS:
            if score_col in v3_set:
                value = float(v3_predictions[score_col][i])
            else:
                value = row.get(f"old_{score_col}")
                if pd.isna(value):
                    value = None
                else:
                    value = float(value)

            if value is None:
                item[score_col] = None
            elif round_scores:
                item[score_col] = int(round(max(0, min(100, value))))
            else:
                item[score_col] = round(float(max(0, min(100, value))), 4)

        rows.append(item)

    return rows


def get_persona_feature_score_columns(engine) -> set:
    sql = """
    SELECT column_name
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'persona_feature_score'
    """
    with engine.connect() as conn:
        result = conn.execute(text(sql)).fetchall()
    return {r[0] for r in result}


def filter_insert_columns(table_columns: set) -> List[str]:
    required = ["persona_profile_id", "score_source", "score_model_version"] + list(SCORE_COLUMNS)
    optional = ["created_at", "updated_at"]

    missing_required = [col for col in required if col not in table_columns]
    if missing_required:
        raise RuntimeError(f"persona_feature_score 테이블에 필수 컬럼이 없습니다: {missing_required}")

    columns = required[:]
    for col in optional:
        if col in table_columns:
            columns.append(col)

    return columns


def upsert_rows(engine, rows: List[Dict[str, Any]], insert_columns: List[str]) -> None:
    if not rows:
        return

    now = datetime.now()

    values = []
    for row in rows:
        value = []
        for col in insert_columns:
            if col in {"created_at", "updated_at"}:
                value.append(now)
            else:
                value.append(row.get(col))
        values.append(tuple(value))

    columns_sql = ", ".join(insert_columns)
    update_columns = [
        col for col in insert_columns
        if col not in {"persona_profile_id", "score_source", "score_model_version", "created_at"}
    ]
    update_sql = ",\n        ".join([f"{col} = EXCLUDED.{col}" for col in update_columns])

    sql = f"""
    INSERT INTO persona_feature_score (
        {columns_sql}
    )
    VALUES %s
    ON CONFLICT (persona_profile_id, score_source, score_model_version)
    DO UPDATE SET
        {update_sql}
    """

    raw_conn = engine.raw_connection()
    try:
        with raw_conn.cursor() as cur:
            execute_values(cur, sql, values, page_size=len(values))
        raw_conn.commit()
    except Exception:
        raw_conn.rollback()
        raise
    finally:
        raw_conn.close()


def delete_existing_version(engine, score_source: str, score_model_version: str) -> int:
    sql = """
    DELETE FROM persona_feature_score
    WHERE score_source = :score_source
      AND score_model_version = :score_model_version
    """
    with engine.begin() as conn:
        result = conn.execute(
            text(sql),
            {
                "score_source": score_source,
                "score_model_version": score_model_version,
            },
        )
        return int(result.rowcount or 0)


def print_sample(rows: List[Dict[str, Any]], limit: int = 5) -> None:
    print()
    print("=" * 120)
    print("HYBRID SAMPLE")
    print("=" * 120)
    for row in rows[:limit]:
        sample = {
            "persona_profile_id": row["persona_profile_id"],
            **{col: row[col] for col in SCORE_COLUMNS},
        }
        print(json.dumps(sample, ensure_ascii=False, indent=2))
    print("=" * 120)


def write_batch_audit(
    audit_path: Optional[str],
    batch_no: int,
    rows: List[Dict[str, Any]],
    v3_score_columns: Sequence[str],
) -> None:
    if not audit_path:
        return

    path = Path(audit_path)
    path.parent.mkdir(parents=True, exist_ok=True)

    if not rows:
        return

    score_means = {}
    for col in SCORE_COLUMNS:
        values = [r[col] for r in rows if r[col] is not None]
        score_means[col] = round(float(np.mean(values)), 4) if values else None

    summary = {
        "batch_no": batch_no,
        "row_count": len(rows),
        "min_persona_profile_id": min(r["persona_profile_id"] for r in rows),
        "max_persona_profile_id": max(r["persona_profile_id"] for r in rows),
        "v3_score_columns": list(v3_score_columns),
        "score_means": score_means,
    }

    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(summary, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="v1 + v3 hybrid score 저장")
    parser.add_argument(
        "--v3-model-path",
        default=str(MODEL_DIR / "persona_score_ridge_v3_all_sources.joblib"),
    )
    parser.add_argument("--old-score-source", default="ML_PREDICTED")
    parser.add_argument("--old-score-model-version", default="persona_score_ridge_v1")
    parser.add_argument("--target-score-source", default="ML_PREDICTED")
    parser.add_argument("--target-score-model-version", default="persona_score_hybrid_v3")
    parser.add_argument(
        "--v3-score-columns",
        default=",".join(DEFAULT_V3_SCORE_COLUMNS),
        help="v3 예측값을 사용할 score 컬럼 CSV",
    )
    parser.add_argument("--batch-size", type=int, default=5000)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--start-id", type=int, default=0)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--delete-existing", action="store_true")
    parser.add_argument("--no-round-scores", action="store_true")
    parser.add_argument("--audit-jsonl", default=None)
    args = parser.parse_args()

    v3_score_columns = parse_csv_columns(args.v3_score_columns) or DEFAULT_V3_SCORE_COLUMNS
    v1_score_columns = [col for col in SCORE_COLUMNS if col not in set(v3_score_columns)]

    model_path = Path(args.v3_model_path)
    if not model_path.exists():
        raise FileNotFoundError(f"v3 모델 파일을 찾을 수 없습니다: {model_path}")

    log(f"v3 model load: {model_path}")
    bundle = joblib.load(model_path)

    log(f"target version: {args.target_score_source}/{args.target_score_model_version}")
    log(f"v3 columns: {v3_score_columns}")
    log(f"v1 columns: {v1_score_columns}")

    engine = get_engine()
    table_columns = get_persona_feature_score_columns(engine)
    insert_columns = filter_insert_columns(table_columns)

    if args.delete_existing:
        if args.dry_run:
            log("[DRY-RUN] delete-existing 요청됨. 실제 삭제하지 않음.")
        else:
            deleted = delete_existing_version(
                engine,
                args.target_score_source,
                args.target_score_model_version,
            )
            log(f"기존 target version 삭제 완료: rows={deleted:,}")

    processed = 0
    saved = 0
    last_id = args.start_id
    batch_no = 0

    while True:
        limit_remaining = None
        if args.limit is not None:
            limit_remaining = args.limit - processed
            if limit_remaining <= 0:
                break

        batch = fetch_batch(
            engine=engine,
            last_id=last_id,
            batch_size=args.batch_size,
            old_score_source=args.old_score_source,
            old_score_model_version=args.old_score_model_version,
            limit_remaining=limit_remaining,
        )

        if batch.empty:
            break

        batch_no += 1
        last_id = int(batch["persona_profile_id"].max())

        prepared = prepare_features(batch)
        v3_predictions = predict_v3(bundle, prepared, v3_score_columns)
        rows = make_hybrid_rows(
            df=batch,
            v3_predictions=v3_predictions,
            v3_score_columns=v3_score_columns,
            target_score_source=args.target_score_source,
            target_score_model_version=args.target_score_model_version,
            round_scores=not args.no_round_scores,
        )

        processed += len(rows)

        if args.dry_run:
            print_sample(rows)
            log(f"[DRY-RUN] batch={batch_no}, rows={len(rows):,}, processed={processed:,}, last_id={last_id}")
            break

        upsert_rows(engine, rows, insert_columns)
        write_batch_audit(args.audit_jsonl, batch_no, rows, v3_score_columns)

        saved += len(rows)
        log(f"[OK] batch={batch_no}, saved={saved:,}, processed={processed:,}, last_id={last_id}")

    log(f"[DONE] processed={processed:,}, saved={saved:,}, last_id={last_id}")

    if args.dry_run:
        print()
        print("dry-run이 정상이라면 전체 저장:")
        print(
            "python3 save_hybrid_v3_predictions.py "
            f"--v3-model-path {args.v3_model_path} "
            f"--batch-size {args.batch_size}"
        )


if __name__ == "__main__":
    main()
