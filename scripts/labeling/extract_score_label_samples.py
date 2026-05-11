#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
DeepSeek score-label worker 입력용 persona 샘플 JSONL 생성 스크립트.

목적:
- deepseek_score_label_worker.py 에 바로 넣을 수 있는 JSONL 생성
- 기존 Flash 3,000건, Pro 408건, GPT/Human 검수건과 중복되지 않도록 제외
- 직업군/연령/성별 쏠림을 줄이기 위한 간단한 균형 샘플링
- price/trust/health/review처럼 약한 score 개선용 신호가 있는 페르소나를 일부 우선 포함 가능

출력 예:
  persona_pipeline_output/samples/score_label_flash_more_3000_20260510_150000.jsonl

실행 예:
  python3 extract_score_label_samples.py \
    --limit 3000 \
    --sample-mode weak_signal_mix \
    --output persona_pipeline_output/samples/score_label_flash_more_3000.jsonl

생성 후 DeepSeek Flash 실행:
  DEEPSEEK_MODEL=deepseek-v4-flash \
  LABEL_PROMPT_VERSION=PERSONA_SCORE_LABEL_V2_FLASH_MORE \
  python3 deepseek_score_label_worker.py \
    --input persona_pipeline_output/samples/score_label_flash_more_3000.jsonl \
    --output-dir persona_pipeline_output/deepseek_outputs/flash_more_3000_v3 \
    --limit 5
"""

import argparse
import json
import math
import os
import random
import re
from datetime import datetime, date
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import pandas as pd
from sqlalchemy import create_engine, text


try:
    from config import SQLALCHEMY_DATABASE_URL
except Exception:
    SQLALCHEMY_DATABASE_URL = os.getenv(
        "SQLALCHEMY_DATABASE_URL",
        os.getenv("DATABASE_URL", "postgresql+psycopg2://postgres:postgres@localhost:5432/precustomer"),
    )


DEFAULT_EXCLUDE_FEATURE_SCORES = [
    ("DEEPSEEK_PSEUDO", "deepseek_v4_flash_prompt_v1"),
    ("DEEPSEEK_PSEUDO", "deepseek_v4_flash_prompt_v2"),
    ("DEEPSEEK_PRO_PSEUDO", "deepseek_v4_pro_prompt_v2"),
    ("HUMAN_VERIFIED", "human_v1"),
]

DEFAULT_EXCLUDE_LABEL_REVIEWS = [
    ("DEEPSEEK_PSEUDO", "deepseek_3000_v1"),
    ("DEEPSEEK_PSEUDO", "deepseek_test_30_v1"),
    ("DEEPSEEK_PSEUDO", "deepseek_test_30_v2"),
    ("DEEPSEEK_PRO_PSEUDO", "DeepSeek_Pro_pseudo-label_batch"),
    ("GPT_PSEUDO", "GPT_review_v1"),
]

WEAK_SIGNAL_KEYWORDS = {
    "price": [
        "가격", "할인", "쿠폰", "가성비", "저렴", "비싸", "예산", "생활비", "절약", "중고",
        "특가", "배송비", "아끼", "부담", "소득", "월세", "대출", "아르바이트",
    ],
    "trust": [
        "신뢰", "검증", "인증", "공식", "자격증", "브랜드", "안전한", "믿을", "평판",
        "단골", "추천받", "전문가", "보증", "정품", "후기 확인",
    ],
    "health_safety": [
        "건강", "안전", "위생", "성분", "영양", "운동", "병원", "의료", "약", "다이어트",
        "식단", "알레르기", "질병", "피부", "소독", "청결", "아이", "노인", "돌봄",
    ],
    "review": [
        "리뷰", "후기", "평점", "별점", "블로그", "유튜브 리뷰", "커뮤니티", "카페",
        "인플루언서", "추천", "비교", "검색", "댓글", "사용기", "체험단",
    ],
}


PROFILE_COLUMNS = [
    "persona_profile_id", "source_record_id", "source_id",
    "age", "age_group", "gender", "province", "district", "region",
    "occupation", "education_level", "family_type", "housing_type",
    "persona_summary", "search_text", "interests", "pain_points",
    "occupation_group", "employment_status", "occupation_domain",
]

SOURCE_COLUMNS = [
    "source_persona", "professional_persona", "sports_persona", "arts_persona",
    "travel_persona", "culinary_persona", "family_persona",
    "cultural_background", "skills_and_expertise", "skills_and_expertise_list",
    "hobbies_and_interests", "hobbies_and_interests_list",
    "career_goals_and_ambitions", "sex", "source_age", "marital_status",
    "military_status", "source_family_type", "source_housing_type",
    "source_education_level", "bachelors_field", "source_occupation",
    "source_district", "source_province", "country",
]


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)


def parse_pair(value: str) -> Tuple[str, str]:
    if ":" not in value:
        raise argparse.ArgumentTypeError("형식은 SOURCE:VERSION 또는 SOURCE:BATCH_ID 여야 합니다.")
    left, right = value.split(":", 1)
    left = left.strip()
    right = right.strip()
    if not left or not right:
        raise argparse.ArgumentTypeError("SOURCE와 VERSION/BATCH_ID는 비어 있을 수 없습니다.")
    return left, right


def json_default(value: Any) -> Any:
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    try:
        import numpy as np
        if isinstance(value, (np.integer,)):
            return int(value)
        if isinstance(value, (np.floating,)):
            return float(value)
    except Exception:
        pass
    return str(value)


def safe_text(value: Any) -> str:
    if value is None:
        return ""
    if pd.isna(value):
        return ""
    return str(value).strip()


def compact_text(value: Any, max_len: int = 1400) -> str:
    text_value = safe_text(value)
    text_value = re.sub(r"\s+", " ", text_value).strip()
    if len(text_value) > max_len:
        return text_value[:max_len].rstrip() + "..."
    return text_value


def parse_jsonish(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, (list, dict)):
        return value
    if pd.isna(value):
        return None
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return None
        if stripped.startswith("[") or stripped.startswith("{"):
            try:
                return json.loads(stripped)
            except Exception:
                return stripped
    return value


def build_exclusion_sql(
    exclude_feature_scores: Sequence[Tuple[str, str]],
    exclude_label_reviews: Sequence[Tuple[str, str]],
) -> Tuple[str, Dict[str, Any]]:
    clauses: List[str] = []
    params: Dict[str, Any] = {}

    for i, (score_source, score_model_version) in enumerate(exclude_feature_scores):
        clauses.append(
            f"""
            NOT EXISTS (
                SELECT 1
                FROM persona_feature_score fs_ex_{i}
                WHERE fs_ex_{i}.persona_profile_id = p.id
                  AND fs_ex_{i}.score_source = :exclude_score_source_{i}
                  AND fs_ex_{i}.score_model_version = :exclude_score_model_version_{i}
            )
            """
        )
        params[f"exclude_score_source_{i}"] = score_source
        params[f"exclude_score_model_version_{i}"] = score_model_version

    for i, (label_source, label_batch_id) in enumerate(exclude_label_reviews):
        clauses.append(
            f"""
            NOT EXISTS (
                SELECT 1
                FROM persona_label_review lr_ex_{i}
                WHERE lr_ex_{i}.persona_profile_id = p.id
                  AND lr_ex_{i}.label_source = :exclude_label_source_{i}
                  AND lr_ex_{i}.label_batch_id = :exclude_label_batch_id_{i}
            )
            """
        )
        params[f"exclude_label_source_{i}"] = label_source
        params[f"exclude_label_batch_id_{i}"] = label_batch_id

    if not clauses:
        return "TRUE", params

    return "\n      AND ".join(clauses), params


def fetch_candidate_pool(
    db_url: str,
    pool_size: int,
    seed: int,
    min_id: int,
    exclude_feature_scores: Sequence[Tuple[str, str]],
    exclude_label_reviews: Sequence[Tuple[str, str]],
) -> pd.DataFrame:
    engine = create_engine(db_url, pool_pre_ping=True)
    exclusion_sql, params = build_exclusion_sql(exclude_feature_scores, exclude_label_reviews)

    # md5 기반 정렬은 재현 가능한 pseudo-random sampling을 위해 사용한다.
    # 100만 row 수준에서는 한 번 실행용으로 감당 가능한 편이지만,
    # 너무 느리면 --pool-size를 낮추거나 --min-id로 범위를 나눠 실행한다.
    sql = f"""
    SELECT
        p.id AS persona_profile_id,
        p.source_record_id,
        COALESCE(p.source_id, s.source_id) AS source_id,
        p.age,
        p.age_group,
        p.gender,
        p.province,
        p.district,
        p.region,
        p.occupation,
        p.education_level,
        p.family_type,
        p.housing_type,
        p.persona_summary,
        p.search_text,
        p.interests,
        p.pain_points,

        COALESCE(onorm.occupation_group, p.occupation, 'UNKNOWN') AS occupation_group,
        COALESCE(onorm.employment_status, 'UNKNOWN') AS employment_status,
        COALESCE(onorm.occupation_domain, 'UNKNOWN') AS occupation_domain,

        s.persona AS source_persona,
        s.professional_persona,
        s.sports_persona,
        s.arts_persona,
        s.travel_persona,
        s.culinary_persona,
        s.family_persona,
        s.cultural_background,
        s.skills_and_expertise,
        s.skills_and_expertise_list,
        s.hobbies_and_interests,
        s.hobbies_and_interests_list,
        s.career_goals_and_ambitions,
        s.sex,
        s.age AS source_age,
        s.marital_status,
        s.military_status,
        s.family_type AS source_family_type,
        s.housing_type AS source_housing_type,
        s.education_level AS source_education_level,
        s.bachelors_field,
        s.occupation AS source_occupation,
        s.district AS source_district,
        s.province AS source_province,
        s.country
    FROM persona_profile p
    JOIN persona_source_record s
      ON s.id = p.source_record_id
    LEFT JOIN persona_occupation_normalized onorm
      ON onorm.persona_profile_id = p.id
    WHERE p.active = TRUE
      AND p.id > :min_id
      AND {exclusion_sql}
    ORDER BY md5(p.id::text || :seed_text)
    LIMIT :pool_size
    """

    params.update({
        "min_id": min_id,
        "pool_size": pool_size,
        "seed_text": str(seed),
    })

    return pd.read_sql(text(sql), engine, params=params)


def combined_text_for_signals(row: pd.Series) -> str:
    parts = []
    for col in [
        "occupation", "persona_summary", "search_text", "interests", "pain_points",
        "source_persona", "professional_persona", "sports_persona", "arts_persona",
        "travel_persona", "culinary_persona", "family_persona", "cultural_background",
        "skills_and_expertise", "hobbies_and_interests", "career_goals_and_ambitions",
    ]:
        parts.append(safe_text(row.get(col)))
    return " ".join(parts)


def detect_focus_signals(row: pd.Series) -> List[str]:
    text_value = combined_text_for_signals(row)
    signals = []
    for signal, keywords in WEAK_SIGNAL_KEYWORDS.items():
        if any(keyword in text_value for keyword in keywords):
            signals.append(signal)
    return signals


def add_sampling_columns(df: pd.DataFrame, seed: int) -> pd.DataFrame:
    out = df.copy()
    for col in ["occupation_group", "age_group", "gender", "province"]:
        out[col] = out[col].fillna("UNKNOWN").astype(str)
        out.loc[out[col].str.strip() == "", col] = "UNKNOWN"

    out["focus_signals"] = out.apply(detect_focus_signals, axis=1)
    out["focus_signal_count"] = out["focus_signals"].apply(len)

    rng = random.Random(seed)
    out["_rand"] = [rng.random() for _ in range(len(out))]
    return out


def greedy_diverse_select(
    df: pd.DataFrame,
    limit: int,
    seed: int,
    max_occupation_share: float,
    max_age_group_share: float,
    prefer_focus: bool,
    already_selected_ids: Optional[set] = None,
) -> pd.DataFrame:
    if df.empty or limit <= 0:
        return df.iloc[0:0].copy()

    already_selected_ids = already_selected_ids or set()

    work = df[~df["persona_profile_id"].astype(str).isin(already_selected_ids)].copy()
    if work.empty:
        return work

    max_occ = max(1, math.ceil(limit * max_occupation_share))
    max_age = max(1, math.ceil(limit * max_age_group_share))

    if prefer_focus:
        work = work.sort_values(
            by=["focus_signal_count", "_rand"],
            ascending=[False, True],
        )
    else:
        work = work.sort_values(by=["_rand"], ascending=True)

    selected_rows = []
    occ_counts: Dict[str, int] = {}
    age_counts: Dict[str, int] = {}

    for _, row in work.iterrows():
        if len(selected_rows) >= limit:
            break

        occ = safe_text(row.get("occupation_group")) or "UNKNOWN"
        age = safe_text(row.get("age_group")) or "UNKNOWN"

        if occ_counts.get(occ, 0) >= max_occ:
            continue
        if age_counts.get(age, 0) >= max_age:
            continue

        selected_rows.append(row)
        occ_counts[occ] = occ_counts.get(occ, 0) + 1
        age_counts[age] = age_counts.get(age, 0) + 1

    # quota 때문에 목표 수를 못 채우면 남은 row를 cap 무시하고 채운다.
    if len(selected_rows) < limit:
        selected_ids = {str(row["persona_profile_id"]) for row in selected_rows}
        remaining = work[~work["persona_profile_id"].astype(str).isin(selected_ids)]
        for _, row in remaining.iterrows():
            if len(selected_rows) >= limit:
                break
            selected_rows.append(row)

    if not selected_rows:
        return work.iloc[0:0].copy()

    return pd.DataFrame(selected_rows).reset_index(drop=True)


def sample_candidates(
    df: pd.DataFrame,
    limit: int,
    seed: int,
    sample_mode: str,
    max_occupation_share: float,
    max_age_group_share: float,
    focus_ratio: float,
) -> pd.DataFrame:
    df = add_sampling_columns(df, seed=seed)

    if sample_mode == "balanced":
        selected = greedy_diverse_select(
            df=df,
            limit=limit,
            seed=seed,
            max_occupation_share=max_occupation_share,
            max_age_group_share=max_age_group_share,
            prefer_focus=False,
        )
        return selected

    if sample_mode == "weak_signal_mix":
        focus_target = int(round(limit * focus_ratio))
        general_target = limit - focus_target

        focus_df = df[df["focus_signal_count"] > 0].copy()
        focus_selected = greedy_diverse_select(
            df=focus_df,
            limit=focus_target,
            seed=seed,
            max_occupation_share=max_occupation_share,
            max_age_group_share=max_age_group_share,
            prefer_focus=True,
        )

        selected_ids = set(focus_selected["persona_profile_id"].astype(str))
        general_selected = greedy_diverse_select(
            df=df,
            limit=general_target,
            seed=seed + 7,
            max_occupation_share=max_occupation_share,
            max_age_group_share=max_age_group_share,
            prefer_focus=False,
            already_selected_ids=selected_ids,
        )

        selected = pd.concat([focus_selected, general_selected], ignore_index=True)
        if len(selected) < limit:
            selected_ids = set(selected["persona_profile_id"].astype(str))
            fill = greedy_diverse_select(
                df=df,
                limit=limit - len(selected),
                seed=seed + 13,
                max_occupation_share=1.0,
                max_age_group_share=1.0,
                prefer_focus=False,
                already_selected_ids=selected_ids,
            )
            selected = pd.concat([selected, fill], ignore_index=True)

        return selected.head(limit).reset_index(drop=True)

    raise ValueError(f"지원하지 않는 sample_mode: {sample_mode}")


def make_worker_payload(row: pd.Series, prompt_version: str, sample_version: str, sample_mode: str) -> Dict[str, Any]:
    profile = {}
    for col in PROFILE_COLUMNS:
        if col in row.index:
            profile[col] = parse_jsonish(row.get(col))

    source = {}
    for col in SOURCE_COLUMNS:
        if col in row.index:
            value = parse_jsonish(row.get(col))
            if isinstance(value, str):
                value = compact_text(value, 1400)
            source[col] = value

    pid = int(row["persona_profile_id"])

    return {
        "persona_profile_id": pid,
        "source_record_id": int(row["source_record_id"]) if pd.notna(row.get("source_record_id")) else None,
        "source_id": safe_text(row.get("source_id")),
        "prompt_version": prompt_version,
        "sample_meta": {
            "sample_version": sample_version,
            "sample_mode": sample_mode,
            "focus_signals": row.get("focus_signals", []),
            "focus_signal_count": int(row.get("focus_signal_count", 0)),
            "occupation_group": safe_text(row.get("occupation_group")) or "UNKNOWN",
            "age_group": safe_text(row.get("age_group")) or "UNKNOWN",
            "gender": safe_text(row.get("gender")) or "UNKNOWN",
            "province": safe_text(row.get("province")) or "UNKNOWN",
        },
        "input": {
            "profile": profile,
            "source": source,
            "labeling_instruction": {
                "purpose": "10개 소비 성향 score pseudo-label 생성",
                "do_not_copy_existing_scores": True,
                "use_original_evidence_only": True,
                "unknown_should_have_low_confidence": True,
            },
        },
    }


def write_jsonl(rows: Iterable[Dict[str, Any]], output_path: str) -> int:
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)

    count = 0
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False, default=json_default) + "\n")
            count += 1
    return count


def write_summary(selected: pd.DataFrame, output_path: str, pool_count: int, args: argparse.Namespace) -> str:
    summary_path = str(Path(output_path).with_suffix(".summary.json"))

    def top_counts(col: str, n: int = 30) -> Dict[str, int]:
        if col not in selected.columns:
            return {}
        return {
            str(k): int(v)
            for k, v in selected[col].fillna("UNKNOWN").astype(str).value_counts().head(n).items()
        }

    focus_counter: Dict[str, int] = {}
    for signals in selected.get("focus_signals", []):
        for signal in signals:
            focus_counter[signal] = focus_counter.get(signal, 0) + 1

    summary = {
        "created_at": datetime.now().isoformat(),
        "output_path": output_path,
        "pool_count": int(pool_count),
        "selected_count": int(len(selected)),
        "args": {
            "limit": args.limit,
            "pool_size": args.pool_size,
            "sample_mode": args.sample_mode,
            "focus_ratio": args.focus_ratio,
            "seed": args.seed,
            "max_occupation_share": args.max_occupation_share,
            "max_age_group_share": args.max_age_group_share,
        },
        "focus_signal_counts": focus_counter,
        "occupation_group_counts": top_counts("occupation_group"),
        "age_group_counts": top_counts("age_group"),
        "gender_counts": top_counts("gender"),
        "province_counts": top_counts("province"),
    }

    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2, default=json_default)

    return summary_path


def main() -> None:
    parser = argparse.ArgumentParser(description="DeepSeek score labeling용 persona 샘플 JSONL 추출")
    parser.add_argument("--db-url", default=SQLALCHEMY_DATABASE_URL)
    parser.add_argument("--limit", type=int, default=3000)
    parser.add_argument("--pool-size", type=int, default=None, help="후보 pool 크기. 기본값 limit * 12")
    parser.add_argument("--min-id", type=int, default=0)
    parser.add_argument("--seed", type=int, default=20260510)
    parser.add_argument("--sample-mode", choices=["balanced", "weak_signal_mix"], default="weak_signal_mix")
    parser.add_argument("--focus-ratio", type=float, default=0.45, help="weak_signal_mix에서 focus 후보 비율")
    parser.add_argument("--max-occupation-share", type=float, default=0.12)
    parser.add_argument("--max-age-group-share", type=float, default=0.25)
    parser.add_argument("--prompt-version", default="PERSONA_SCORE_LABEL_V2_FLASH_MORE")
    parser.add_argument("--sample-version", default=None)
    parser.add_argument("--output", required=True)

    parser.add_argument(
        "--exclude-feature",
        action="append",
        type=parse_pair,
        default=None,
        help="제외할 persona_feature_score. 형식 SOURCE:VERSION. 여러 번 지정 가능.",
    )
    parser.add_argument(
        "--exclude-label",
        action="append",
        type=parse_pair,
        default=None,
        help="제외할 persona_label_review. 형식 SOURCE:BATCH_ID. 여러 번 지정 가능.",
    )
    parser.add_argument(
        "--no-default-excludes",
        action="store_true",
        help="기본 제외 조건을 사용하지 않는다.",
    )

    args = parser.parse_args()

    if args.limit <= 0:
        raise ValueError("--limit은 1 이상이어야 합니다.")

    if args.pool_size is None:
        args.pool_size = max(args.limit * 12, args.limit)

    if args.pool_size < args.limit:
        raise ValueError("--pool-size는 --limit 이상이어야 합니다.")

    sample_version = args.sample_version or f"score_label_sample_{datetime.now().strftime('%Y%m%d_%H%M%S')}"

    exclude_feature_scores: List[Tuple[str, str]] = []
    exclude_label_reviews: List[Tuple[str, str]] = []

    if not args.no_default_excludes:
        exclude_feature_scores.extend(DEFAULT_EXCLUDE_FEATURE_SCORES)
        exclude_label_reviews.extend(DEFAULT_EXCLUDE_LABEL_REVIEWS)

    if args.exclude_feature:
        exclude_feature_scores.extend(args.exclude_feature)
    if args.exclude_label:
        exclude_label_reviews.extend(args.exclude_label)

    log(f"DB URL: {args.db_url}")
    log(f"후보 pool 조회 시작: pool_size={args.pool_size:,}, limit={args.limit:,}")
    log(f"제외 feature score: {exclude_feature_scores}")
    log(f"제외 label review: {exclude_label_reviews}")

    pool = fetch_candidate_pool(
        db_url=args.db_url,
        pool_size=args.pool_size,
        seed=args.seed,
        min_id=args.min_id,
        exclude_feature_scores=exclude_feature_scores,
        exclude_label_reviews=exclude_label_reviews,
    )

    log(f"후보 pool 조회 완료: {len(pool):,}")

    if pool.empty:
        raise RuntimeError("후보가 없습니다. 제외 조건이나 min-id를 확인하세요.")

    selected = sample_candidates(
        df=pool,
        limit=args.limit,
        seed=args.seed,
        sample_mode=args.sample_mode,
        max_occupation_share=args.max_occupation_share,
        max_age_group_share=args.max_age_group_share,
        focus_ratio=args.focus_ratio,
    )

    if len(selected) < args.limit:
        log(f"[WARN] 요청한 {args.limit:,}건보다 적은 {len(selected):,}건만 선택되었습니다.")

    payloads = [
        make_worker_payload(
            row=row,
            prompt_version=args.prompt_version,
            sample_version=sample_version,
            sample_mode=args.sample_mode,
        )
        for _, row in selected.iterrows()
    ]

    written = write_jsonl(payloads, args.output)
    summary_path = write_summary(selected, args.output, pool_count=len(pool), args=args)

    log(f"JSONL 저장 완료: {args.output} rows={written:,}")
    log(f"요약 저장 완료: {summary_path}")

    print()
    print("다음 실행 예:")
    print(f"DEEPSEEK_MODEL=deepseek-v4-flash \\")
    print(f"LABEL_PROMPT_VERSION={args.prompt_version} \\")
    print(f"python3 deepseek_score_label_worker.py \\")
    print(f"  --input {args.output} \\")
    print(f"  --output-dir persona_pipeline_output/deepseek_outputs/flash_more_3000_v3 \\")
    print(f"  --limit 5")


if __name__ == "__main__":
    main()
