#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
CustomerPreview / 미리고객 - 100만 페르소나 성향 점수 예측

역할:
- 학습된 TF-IDF + Ridge 모델을 로드한다.
- persona_profile 전체 또는 일부를 batch로 읽어온다.
- 10개 성향 점수를 예측한다.
- persona_score_prediction 테이블에 model_version별로 저장한다.
- 선택적으로 persona_feature_score에도 ML_PREDICTED로 저장한다.

설치:
pip install psycopg2-binary pandas numpy scikit-learn joblib

1만 명 테스트:
python3 ./scripts/predict_persona_scores.py \
  --dbname precustomer \
  --model-path ./models/persona_score_ridge_v1.joblib \
  --model-version persona_score_ridge_v1 \
  --limit 10000 \
  --batch-size 2000

전체 예측:
python3 ./scripts/predict_persona_scores.py \
  --dbname precustomer \
  --model-path ./models/persona_score_ridge_v1.joblib \
  --model-version persona_score_ridge_v1 \
  --batch-size 5000
"""

import argparse
import json
import time
from datetime import datetime
from typing import Any, Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
import psycopg2
from psycopg2.extras import execute_values


SCORE_COLUMNS = [
    "digital_affinity_score",
    "price_sensitivity_score",
    "trust_sensitivity_score",
    "convenience_need_score",
    "quality_sensitivity_score",
    "novelty_acceptance_score",
    "local_affinity_score",
    "family_decision_score",
    "health_safety_sensitivity_score",
    "review_dependency_score",
]


def log(message):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {message}", flush=True)


def connect_db(args):
    return psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.dbname,
        user=args.user,
        password=args.password,
        connect_timeout=10,
    )


def ensure_tables(conn):
    ddl = """
    CREATE TABLE IF NOT EXISTS persona_score_prediction (
        id BIGSERIAL PRIMARY KEY,
        persona_profile_id BIGINT NOT NULL,
        model_version VARCHAR(100) NOT NULL,

        digital_affinity_score INTEGER NOT NULL,
        price_sensitivity_score INTEGER NOT NULL,
        trust_sensitivity_score INTEGER NOT NULL,
        convenience_need_score INTEGER NOT NULL,
        quality_sensitivity_score INTEGER NOT NULL,
        novelty_acceptance_score INTEGER NOT NULL,
        local_affinity_score INTEGER NOT NULL,
        family_decision_score INTEGER NOT NULL,
        health_safety_sensitivity_score INTEGER NOT NULL,
        review_dependency_score INTEGER NOT NULL,

        prediction_confidence NUMERIC(5, 4),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        CONSTRAINT fk_persona_score_prediction_profile
            FOREIGN KEY (persona_profile_id)
            REFERENCES persona_profile(id)
            ON DELETE CASCADE,

        CONSTRAINT uk_persona_score_prediction_model
            UNIQUE (persona_profile_id, model_version),

        CONSTRAINT ck_persona_score_prediction_digital CHECK (digital_affinity_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_price CHECK (price_sensitivity_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_trust CHECK (trust_sensitivity_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_convenience CHECK (convenience_need_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_quality CHECK (quality_sensitivity_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_novelty CHECK (novelty_acceptance_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_local CHECK (local_affinity_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_family CHECK (family_decision_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_health CHECK (health_safety_sensitivity_score BETWEEN 0 AND 100),
        CONSTRAINT ck_persona_score_prediction_review CHECK (review_dependency_score BETWEEN 0 AND 100)
    );

    CREATE INDEX IF NOT EXISTS idx_persona_score_prediction_profile_id
        ON persona_score_prediction(persona_profile_id);

    CREATE INDEX IF NOT EXISTS idx_persona_score_prediction_model_version
        ON persona_score_prediction(model_version);

    COMMENT ON TABLE persona_score_prediction IS 'ML 모델이 예측한 페르소나 성향 점수를 모델 버전별로 저장하는 테이블';
    COMMENT ON COLUMN persona_score_prediction.id IS 'ML 예측 점수 내부 식별자';
    COMMENT ON COLUMN persona_score_prediction.persona_profile_id IS '예측 대상 페르소나 프로필 식별자';
    COMMENT ON COLUMN persona_score_prediction.model_version IS '예측에 사용한 ML 모델 버전';
    COMMENT ON COLUMN persona_score_prediction.digital_affinity_score IS '디지털 서비스나 앱 사용에 익숙한 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.price_sensitivity_score IS '가격, 가성비, 생활비에 민감한 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.trust_sensitivity_score IS '후기, 검증, 인증, 운영자 신뢰를 중요하게 보는 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.convenience_need_score IS '시간 절약, 편의성, 자동화 니즈가 큰 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.quality_sensitivity_score IS '품질, 꼼꼼함, 전문성, 위생, 완성도를 중시하는 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.novelty_acceptance_score IS '새로운 서비스, 상품, 방식에 열려 있는 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.local_affinity_score IS '동네, 지역, 단골, 오프라인 상권에 반응할 가능성 예측값';
    COMMENT ON COLUMN persona_score_prediction.family_decision_score IS '가족, 배우자, 자녀, 부모, 손주 관련 의사결정 영향도 예측값';
    COMMENT ON COLUMN persona_score_prediction.health_safety_sensitivity_score IS '건강, 안전, 위생, 재난, 의료, 식품 안정성에 민감한 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.review_dependency_score IS '후기, 평판, 추천, 지인 평가에 의존하는 정도 예측값';
    COMMENT ON COLUMN persona_score_prediction.prediction_confidence IS '예측 신뢰도. 초기 모델에서는 NULL 또는 단순 기준값 사용';
    COMMENT ON COLUMN persona_score_prediction.created_at IS '예측 결과 생성 시각';
    """
    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()


def safe_text(value):
    if value is None:
        return ""
    if pd.isna(value):
        return ""
    return str(value).strip()


def build_model_text(row):
    parts = []
    parts.append(f"연령대: {safe_text(row.get('age_group'))}")
    parts.append(f"성별: {safe_text(row.get('gender'))}")
    parts.append(f"권역: {safe_text(row.get('region'))}")
    parts.append(f"시도: {safe_text(row.get('province'))}")
    parts.append(f"시군구: {safe_text(row.get('district'))}")
    parts.append(f"직업: {safe_text(row.get('occupation'))}")
    parts.append(f"학력: {safe_text(row.get('education_level'))}")
    parts.append(f"가족형태: {safe_text(row.get('family_type'))}")
    parts.append(f"주거형태: {safe_text(row.get('housing_type'))}")
    parts.append(f"요약: {safe_text(row.get('persona_summary'))}")

    search_text = safe_text(row.get("search_text"))
    if len(search_text) > 4000:
        search_text = search_text[:4000]

    parts.append(f"상세설명: {search_text}")
    return "\n".join(part for part in parts if part.strip())


def count_targets(conn, args):
    where = "WHERE p.active = TRUE" if args.active_only else ""
    limit_sql = "LIMIT %s" if args.limit is not None else ""

    # limit이 있으면 실제 대상 count는 limit 값과 전체 count 중 작은 값
    with conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM persona_profile p {where}")
        total = cur.fetchone()[0]

    if args.limit is not None:
        return min(total, args.limit)

    return total


def fetch_batch(conn, args, last_id):
    where = ["p.id > %s"]
    params = [last_id]

    if args.active_only:
        where.append("p.active = TRUE")

    if args.skip_existing:
        where.append(
            """
            NOT EXISTS (
                SELECT 1
                FROM persona_score_prediction sp
                WHERE sp.persona_profile_id = p.id
                  AND sp.model_version = %s
            )
            """
        )
        params.append(args.model_version)

    limit_value = args.batch_size

    sql = f"""
    SELECT
        p.id AS persona_profile_id,
        p.source_id,
        p.age,
        p.age_group,
        p.gender,
        p.region,
        p.province,
        p.district,
        p.occupation,
        p.education_level,
        p.family_type,
        p.housing_type,
        p.persona_summary,
        p.search_text
    FROM persona_profile p
    WHERE {" AND ".join(where)}
    ORDER BY p.id
    LIMIT %s
    """

    params.append(limit_value)

    df = pd.read_sql_query(sql, conn, params=params)
    return df


def predict_scores(model_bundle, df):
    pipeline = model_bundle["pipeline"]
    texts = df.apply(build_model_text, axis=1).values
    pred = pipeline.predict(texts)
    pred = np.clip(np.rint(pred), 0, 100).astype(int)
    return pred


def insert_predictions(conn, args, df, pred):
    values = []

    for idx, row in df.iterrows():
        scores = pred[len(values)]
        values.append(
            (
                int(row["persona_profile_id"]),
                args.model_version,
                int(scores[0]),
                int(scores[1]),
                int(scores[2]),
                int(scores[3]),
                int(scores[4]),
                int(scores[5]),
                int(scores[6]),
                int(scores[7]),
                int(scores[8]),
                int(scores[9]),
                None,
            )
        )

    sql = """
    INSERT INTO persona_score_prediction (
        persona_profile_id,
        model_version,

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

        prediction_confidence
    )
    VALUES %s
    ON CONFLICT (persona_profile_id, model_version) DO UPDATE SET
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
        prediction_confidence = EXCLUDED.prediction_confidence;
    """

    with conn.cursor() as cur:
        execute_values(cur, sql, values, page_size=len(values))


def upsert_feature_scores(conn, args, df, pred):
    values = []

    for idx, row in df.iterrows():
        scores = pred[len(values)]
        values.append(
            (
                int(row["persona_profile_id"]),
                int(scores[0]),
                int(scores[1]),
                int(scores[2]),
                int(scores[3]),
                int(scores[4]),
                int(scores[5]),
                int(scores[6]),
                int(scores[7]),
                int(scores[8]),
                int(scores[9]),
                "ML_PREDICTED",
                args.model_version,
            )
        )

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
    VALUES %s
    ON CONFLICT (persona_profile_id, score_source, score_model_version) DO UPDATE SET
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
        updated_at = CURRENT_TIMESTAMP;
    """
    with conn.cursor() as cur:
        execute_values(cur, sql, values, page_size=len(values))


def run_prediction(conn, args, model_bundle):
    ensure_tables(conn)

    total_target = count_targets(conn, args)
    log(f"[TARGET] target_count={total_target}")

    processed = 0
    last_id = 0
    started_at = time.time()

    while True:
        if args.limit is not None and processed >= args.limit:
            break

        current_batch_size = args.batch_size
        if args.limit is not None:
            current_batch_size = min(args.batch_size, args.limit - processed)

        original_batch_size = args.batch_size
        args.batch_size = current_batch_size
        df = fetch_batch(conn, args, last_id)
        args.batch_size = original_batch_size

        if df.empty:
            break

        last_id = int(df["persona_profile_id"].max())

        pred = predict_scores(model_bundle, df)
        insert_predictions(conn, args, df, pred)

        if args.insert_feature_score:
            upsert_feature_scores(conn, args, df, pred)

        conn.commit()

        processed += len(df)
        elapsed = time.time() - started_at
        rows_per_sec = processed / elapsed if elapsed > 0 else 0

        if args.limit is not None:
            progress = processed / args.limit * 100
            log(
                f"[PROGRESS] processed={processed}/{args.limit} "
                f"({progress:.2f}%), last_id={last_id}, rows/sec={rows_per_sec:.2f}"
            )
        else:
            log(
                f"[PROGRESS] processed={processed}, last_id={last_id}, rows/sec={rows_per_sec:.2f}"
            )

    elapsed = time.time() - started_at
    log(f"[DONE] processed={processed}, elapsed={elapsed:.2f}s")


def parse_args():
    parser = argparse.ArgumentParser(description="CustomerPreview persona score prediction")

    parser.add_argument("--model-path", required=True)
    parser.add_argument("--model-version", required=True)

    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--batch-size", type=int, default=5000)

    parser.add_argument("--active-only", action="store_true", default=True)
    parser.add_argument("--include-inactive", action="store_false", dest="active_only")
    parser.add_argument("--skip-existing", action="store_true", default=False)

    parser.add_argument("--insert-feature-score", action="store_true", default=True)
    parser.add_argument("--no-insert-feature-score", action="store_false", dest="insert_feature_score")

    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="precustomer")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")

    return parser.parse_args()


def main():
    args = parse_args()
    log("[START] persona score prediction")
    log(f"[CONFIG] model_path={args.model_path}")
    log(f"[CONFIG] model_version={args.model_version}")
    log(f"[CONFIG] limit={args.limit}, batch_size={args.batch_size}")
    log(f"[CONFIG] insert_feature_score={args.insert_feature_score}")

    model_bundle = joblib.load(args.model_path)

    conn = connect_db(args)
    try:
        run_prediction(conn, args, model_bundle)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
        log("[END] DB connection closed")


if __name__ == "__main__":
    main()
