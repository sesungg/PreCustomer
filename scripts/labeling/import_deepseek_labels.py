import json
import argparse
from typing import Any, Dict, List, Optional

import psycopg2
from psycopg2.extras import Json, execute_batch

from config import (
    PSYCOPG2_DATABASE_URL,
    SCORE_COLUMNS,
    NEW_DEEPSEEK_LABEL_SOURCE,
    NEW_DEEPSEEK_SCORE_SOURCE,
    NEW_DEEPSEEK_SCORE_MODEL_VERSION,
)


def read_jsonl(path: str) -> List[Dict[str, Any]]:
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except Exception as e:
                raise ValueError(f"JSONL 파싱 실패 line={line_no}: {e}")
    return rows


def clamp_score(value: Any) -> int:
    try:
        number = int(round(float(value)))
    except Exception:
        number = 0
    return max(0, min(100, number))


def get_result(row: Dict[str, Any]) -> Dict[str, Any]:
    result = row.get("result")
    if not isinstance(result, dict):
        return {}
    return result


def get_scores(row: Dict[str, Any]) -> Dict[str, int]:
    scores = get_result(row).get("scores") or {}
    return {col: clamp_score(scores.get(col)) for col in SCORE_COLUMNS}


def build_reason_json(row: Dict[str, Any]) -> Dict[str, Any]:
    result = get_result(row)
    return {
        "reasons": result.get("reasons") or {},
        "confidence": result.get("confidence") or {},
        "label_status": result.get("label_status") or {},
        "train_weight": result.get("train_weight") or {},
        "label_quality": result.get("label_quality") or {},
        "prompt_version": row.get("prompt_version"),
        "model_name": row.get("model_name"),
        "model_version": row.get("model_version"),
        "elapsed_seconds": row.get("elapsed_seconds"),
    }


def validate_rows(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    valid = []
    for row in rows:
        result = get_result(row)
        if row.get("persona_profile_id") is None:
            continue
        if not isinstance(result.get("scores"), dict):
            continue
        if not isinstance(result.get("confidence"), dict):
            continue
        if not isinstance(result.get("label_status"), dict):
            continue
        if not isinstance(result.get("train_weight"), dict):
            continue
        valid.append(row)
    return valid


def upsert_label_batch(
    conn,
    label_batch_id: str,
    sample_size: int,
    label_source: str,
    model_name: str,
    prompt_version: str,
):
    sql = """
    INSERT INTO persona_label_batch (
        label_batch_id,
        batch_name,
        sample_size,
        label_source,
        model_name,
        prompt_version,
        status
    )
    VALUES (%s, %s, %s, %s, %s, %s, 'COMPLETED')
    ON CONFLICT (label_batch_id)
    DO UPDATE SET
        sample_size = EXCLUDED.sample_size,
        label_source = EXCLUDED.label_source,
        model_name = EXCLUDED.model_name,
        prompt_version = EXCLUDED.prompt_version,
        status = 'COMPLETED',
        updated_at = CURRENT_TIMESTAMP
    """
    with conn.cursor() as cur:
        cur.execute(
            sql,
            (label_batch_id, label_batch_id, sample_size, label_source, model_name, prompt_version),
        )


def build_label_review_tuple(row: Dict[str, Any], label_batch_id: str, label_source: str) -> tuple:
    scores = get_scores(row)
    return (
        int(row["persona_profile_id"]),
        label_batch_id,
        label_source,
        scores["digital_affinity_score"],
        scores["price_sensitivity_score"],
        scores["trust_sensitivity_score"],
        scores["convenience_need_score"],
        scores["quality_sensitivity_score"],
        scores["novelty_acceptance_score"],
        scores["local_affinity_score"],
        scores["family_decision_score"],
        scores["health_safety_sensitivity_score"],
        scores["review_dependency_score"],
        Json(build_reason_json(row)),
        None,
        False,
        Json({
            "raw_content": row.get("raw_content"),
            "raw_api_response": row.get("raw_api_response"),
            "result": row.get("result"),
        }),
        None,
    )


def upsert_label_reviews(conn, rows: List[Dict[str, Any]], label_batch_id: str, label_source: str, batch_size: int):
    sql = """
    INSERT INTO persona_label_review (
        persona_profile_id,
        label_batch_id,
        label_source,
        digital_affinity_score,
        price_sensitivity_score,
        trust_sensitivity_score,
        convenience_need_score,
        quality_sensitivity_score,
        novelty_acceptance_score,
        local_affinity_score,
        family_decision_score,
        health_safety_sensitivity_score,
        review_dependency_score,
        reason_json,
        reviewer,
        reviewed,
        raw_response_json,
        error_message
    )
    VALUES (
        %s, %s, %s,
        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
        %s, %s, %s, %s, %s
    )
    ON CONFLICT (label_batch_id, persona_profile_id, label_source)
    DO UPDATE SET
        digital_affinity_score = EXCLUDED.digital_affinity_score,
        price_sensitivity_score = EXCLUDED.price_sensitivity_score,
        trust_sensitivity_score = EXCLUDED.trust_sensitivity_score,
        convenience_need_score = EXCLUDED.convenience_need_score,
        quality_sensitivity_score = EXCLUDED.quality_sensitivity_score,
        novelty_acceptance_score = EXCLUDED.novelty_acceptance_score,
        local_affinity_score = EXCLUDED.local_affinity_score,
        family_decision_score = EXCLUDED.family_decision_score,
        health_safety_sensitivity_score = EXCLUDED.health_safety_sensitivity_score,
        review_dependency_score = EXCLUDED.review_dependency_score,
        reason_json = EXCLUDED.reason_json,
        raw_response_json = EXCLUDED.raw_response_json,
        error_message = EXCLUDED.error_message,
        updated_at = CURRENT_TIMESTAMP
    """
    tuples = [build_label_review_tuple(row, label_batch_id, label_source) for row in rows]
    with conn.cursor() as cur:
        execute_batch(cur, sql, tuples, page_size=batch_size)


def build_feature_score_tuple(row: Dict[str, Any], score_source: str, score_model_version: str) -> tuple:
    scores = get_scores(row)
    return (
        int(row["persona_profile_id"]),
        scores["digital_affinity_score"],
        scores["price_sensitivity_score"],
        scores["trust_sensitivity_score"],
        scores["convenience_need_score"],
        scores["quality_sensitivity_score"],
        scores["novelty_acceptance_score"],
        scores["local_affinity_score"],
        scores["family_decision_score"],
        scores["health_safety_sensitivity_score"],
        scores["review_dependency_score"],
        score_source,
        score_model_version,
    )


def upsert_feature_scores(
    conn,
    rows: List[Dict[str, Any]],
    score_source: str,
    score_model_version: str,
    batch_size: int,
):
    sql = """
    INSERT INTO persona_feature_score (
        persona_profile_id,
        digital_affinity_score,
        price_sensitivity_score,
        trust_sensitivity_score,
        convenience_need_score,
        quality_sensitivity_score,
        novelty_acceptance_score,
        local_affinity_score,
        family_decision_score,
        health_safety_sensitivity_score,
        review_dependency_score,
        score_source,
        score_model_version
    )
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    ON CONFLICT (persona_profile_id, score_source, score_model_version)
    DO UPDATE SET
        digital_affinity_score = EXCLUDED.digital_affinity_score,
        price_sensitivity_score = EXCLUDED.price_sensitivity_score,
        trust_sensitivity_score = EXCLUDED.trust_sensitivity_score,
        convenience_need_score = EXCLUDED.convenience_need_score,
        quality_sensitivity_score = EXCLUDED.quality_sensitivity_score,
        novelty_acceptance_score = EXCLUDED.novelty_acceptance_score,
        local_affinity_score = EXCLUDED.local_affinity_score,
        family_decision_score = EXCLUDED.family_decision_score,
        health_safety_sensitivity_score = EXCLUDED.health_safety_sensitivity_score,
        review_dependency_score = EXCLUDED.review_dependency_score,
        updated_at = CURRENT_TIMESTAMP
    """
    tuples = [build_feature_score_tuple(row, score_source, score_model_version) for row in rows]
    with conn.cursor() as cur:
        execute_batch(cur, sql, tuples, page_size=batch_size)


def summarize_status(rows: List[Dict[str, Any]]) -> Dict[str, Dict[str, int]]:
    summary = {col: {"STRONG": 0, "WEAK": 0, "UNKNOWN": 0} for col in SCORE_COLUMNS}
    for row in rows:
        status = get_result(row).get("label_status") or {}
        for col in SCORE_COLUMNS:
            value = status.get(col, "UNKNOWN")
            if value not in summary[col]:
                value = "UNKNOWN"
            summary[col][value] += 1
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--label-batch-id", required=True)
    parser.add_argument("--label-source", default=NEW_DEEPSEEK_LABEL_SOURCE)
    parser.add_argument("--score-source", default=NEW_DEEPSEEK_SCORE_SOURCE)
    parser.add_argument("--score-model-version", default=NEW_DEEPSEEK_SCORE_MODEL_VERSION)
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-feature-score", action="store_true")
    args = parser.parse_args()

    rows = read_jsonl(args.input)
    valid_rows = validate_rows(rows)

    print(f"입력: {len(rows):,}")
    print(f"유효: {len(valid_rows):,}")
    print("score별 label_status 분포:")
    print(json.dumps(summarize_status(valid_rows), ensure_ascii=False, indent=2))

    if args.dry_run:
        print("DRY RUN: DB 저장 안 함")
        if valid_rows:
            print(json.dumps(build_reason_json(valid_rows[0]), ensure_ascii=False, indent=2)[:3000])
        return

    if not valid_rows:
        raise RuntimeError("저장할 유효 row가 없습니다.")

    model_name = valid_rows[0].get("model_name", "DEEPSEEK")
    prompt_version = valid_rows[0].get("prompt_version", "UNKNOWN")

    conn = psycopg2.connect(PSYCOPG2_DATABASE_URL)
    try:
        conn.autocommit = False
        upsert_label_batch(
            conn,
            label_batch_id=args.label_batch_id,
            sample_size=len(valid_rows),
            label_source=args.label_source,
            model_name=model_name,
            prompt_version=prompt_version,
        )
        upsert_label_reviews(
            conn,
            rows=valid_rows,
            label_batch_id=args.label_batch_id,
            label_source=args.label_source,
            batch_size=args.batch_size,
        )
        if not args.skip_feature_score:
            upsert_feature_scores(
                conn,
                rows=valid_rows,
                score_source=args.score_source,
                score_model_version=args.score_model_version,
                batch_size=args.batch_size,
            )
        conn.commit()
        print("DB 저장 완료")
        print(f"label_batch_id={args.label_batch_id}")
        print(f"label_source={args.label_source}")
        if not args.skip_feature_score:
            print(f"score_source={args.score_source}")
            print(f"score_model_version={args.score_model_version}")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()
