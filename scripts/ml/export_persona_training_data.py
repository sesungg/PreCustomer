#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
CustomerPreview / 미리고객 - ML 학습용 데이터 export

역할:
- persona_label_review에 저장된 DeepSeek pseudo-label을 학습용 CSV로 export한다.
- persona_profile의 검색용 프로필/텍스트와 10개 성향 점수를 결합한다.
- 이 CSV는 train_persona_score_model.py의 입력으로 사용한다.

설치:
pip install psycopg2-binary pandas

실행:
python3 ./scripts/export_persona_training_data.py \
  --dbname precustomer \
  --label-batch-id deepseek_3000_v1 \
  --output ./ml_data/train_persona_scores_deepseek_3000.csv
"""

import argparse
from pathlib import Path

import pandas as pd
import psycopg2


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


def connect_db(args):
    return psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.dbname,
        user=args.user,
        password=args.password,
        connect_timeout=10,
    )


def export_training_data(conn, args):
    sql = """
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
        p.search_text,

        lr.digital_affinity_score,
        lr.price_sensitivity_score,
        lr.trust_sensitivity_score,
        lr.convenience_need_score,
        lr.quality_sensitivity_score,
        lr.novelty_acceptance_score,
        lr.local_affinity_score,
        lr.family_decision_score,
        lr.health_safety_sensitivity_score,
        lr.review_dependency_score
    FROM persona_label_review lr
    JOIN persona_profile p ON p.id = lr.persona_profile_id
    WHERE lr.label_batch_id = %s
      AND lr.label_source = %s
    ORDER BY p.id
    """

    df = pd.read_sql_query(
        sql,
        conn,
        params=(args.label_batch_id, args.label_source),
    )

    if df.empty:
        raise RuntimeError(
            f"학습 데이터가 없습니다. label_batch_id={args.label_batch_id}, "
            f"label_source={args.label_source}"
        )

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(output_path, index=False, encoding="utf-8-sig")

    print("=" * 80)
    print("ML 학습용 데이터 export 완료")
    print("=" * 80)
    print(f"output: {output_path}")
    print(f"rows: {len(df)}")
    print(f"columns: {len(df.columns)}")

    print()
    print("점수 평균")
    for col in SCORE_COLUMNS:
        print(f"- {col}: avg={round(df[col].mean(), 2)}, min={df[col].min()}, max={df[col].max()}")

    print("=" * 80)


def parse_args():
    parser = argparse.ArgumentParser(description="CustomerPreview ML 학습용 DeepSeek 라벨 export")

    parser.add_argument("--label-batch-id", required=True, help="예: deepseek_3000_v1")
    parser.add_argument("--label-source", default="DEEPSEEK_PSEUDO")
    parser.add_argument("--output", required=True)

    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="precustomer")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")

    return parser.parse_args()


def main():
    args = parse_args()
    conn = connect_db(args)

    try:
        export_training_data(conn, args)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
