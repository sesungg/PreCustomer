#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
CustomerPreview / 미리고객 - DeepSeek pseudo-label 생성 스크립트 v1

역할:
- 샘플 CSV를 읽는다.
- 각 persona에 대해 DeepSeek API로 10개 성향 점수와 근거를 생성한다.
- 결과를 JSONL로 백업 저장한다.
- persona_label_batch / persona_label_review 테이블에 저장한다.
- 선택적으로 persona_feature_score에도 DEEPSEEK_PSEUDO 점수를 저장한다.
- 실패 row는 error JSONL에 남기고 다음 row를 계속 처리한다.

필요 패키지:
pip install psycopg2-binary

환경변수:
export DEEPSEEK_API_KEY="sk-..."

30명 테스트 실행:
python3 ./scripts/deepseek_label_personas.py \
  --dbname precustomer \
  --input ./samples/persona_label_sample_30_test.csv \
  --output-jsonl ./outputs/deepseek_label_30_test.jsonl \
  --error-jsonl ./outputs/deepseek_label_30_test_errors.jsonl \
  --label-batch-id deepseek_test_30_v1 \
  --limit 30

300명 실행:
python3 ./scripts/deepseek_label_personas.py \
  --dbname precustomer \
  --input ./samples/persona_label_sample_3000_v3.csv \
  --output-jsonl ./outputs/deepseek_label_300.jsonl \
  --error-jsonl ./outputs/deepseek_label_300_errors.jsonl \
  --label-batch-id deepseek_300_v1 \
  --limit 300

3,000명 실행:
python3 ./scripts/deepseek_label_personas.py \
  --dbname precustomer \
  --input ./samples/persona_label_sample_3000_v3.csv \
  --output-jsonl ./outputs/deepseek_label_3000.jsonl \
  --error-jsonl ./outputs/deepseek_label_3000_errors.jsonl \
  --label-batch-id deepseek_3000_v1
"""

import argparse
import csv
import json
import os
import re
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import psycopg2
from psycopg2.extras import Json


SCORE_KEYS = [
    "digitalAffinityScore",
    "priceSensitivityScore",
    "trustSensitivityScore",
    "convenienceNeedScore",
    "qualitySensitivityScore",
    "noveltyAcceptanceScore",
    "localAffinityScore",
    "familyDecisionScore",
    "healthSafetySensitivityScore",
    "reviewDependencyScore",
]

DB_SCORE_COLUMNS = {
    "digitalAffinityScore": "digital_affinity_score",
    "priceSensitivityScore": "price_sensitivity_score",
    "trustSensitivityScore": "trust_sensitivity_score",
    "convenienceNeedScore": "convenience_need_score",
    "qualitySensitivityScore": "quality_sensitivity_score",
    "noveltyAcceptanceScore": "novelty_acceptance_score",
    "localAffinityScore": "local_affinity_score",
    "familyDecisionScore": "family_decision_score",
    "healthSafetySensitivityScore": "health_safety_sensitivity_score",
    "reviewDependencyScore": "review_dependency_score",
}


def log(message: str) -> None:
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


def ensure_tables(conn) -> None:
    """
    label 테이블이 없으면 생성한다.
    기존에 이미 생성되어 있으면 건드리지 않는다.
    """

    ddl = """
    CREATE TABLE IF NOT EXISTS persona_label_batch (
        id BIGSERIAL PRIMARY KEY,
        label_batch_id VARCHAR(100) NOT NULL UNIQUE,
        batch_name VARCHAR(200),
        sample_size INTEGER NOT NULL,
        label_source VARCHAR(50) NOT NULL,
        model_name VARCHAR(100),
        prompt_version VARCHAR(100),
        input_csv_path TEXT,
        output_jsonl_path TEXT,
        status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    COMMENT ON TABLE persona_label_batch IS '페르소나 라벨링 작업 배치 관리 테이블';
    COMMENT ON COLUMN persona_label_batch.id IS '라벨링 배치 내부 식별자';
    COMMENT ON COLUMN persona_label_batch.label_batch_id IS '라벨링 배치 고유 식별자';
    COMMENT ON COLUMN persona_label_batch.batch_name IS '라벨링 배치 이름';
    COMMENT ON COLUMN persona_label_batch.sample_size IS '라벨링 대상 샘플 수';
    COMMENT ON COLUMN persona_label_batch.label_source IS '라벨 생성 출처. 예: DEEPSEEK_PSEUDO, HUMAN, HUMAN_CORRECTED';
    COMMENT ON COLUMN persona_label_batch.model_name IS '라벨 생성에 사용한 모델명';
    COMMENT ON COLUMN persona_label_batch.prompt_version IS '라벨 생성 프롬프트 버전';
    COMMENT ON COLUMN persona_label_batch.input_csv_path IS '입력 CSV 파일 경로';
    COMMENT ON COLUMN persona_label_batch.output_jsonl_path IS '출력 JSONL 파일 경로';
    COMMENT ON COLUMN persona_label_batch.status IS '라벨링 배치 상태. CREATED, RUNNING, COMPLETED, FAILED';
    COMMENT ON COLUMN persona_label_batch.created_at IS '배치 생성 시각';
    COMMENT ON COLUMN persona_label_batch.updated_at IS '배치 수정 시각';

    CREATE TABLE IF NOT EXISTS persona_label_review (
        id BIGSERIAL PRIMARY KEY,
        persona_profile_id BIGINT NOT NULL,
        label_batch_id VARCHAR(100) NOT NULL,
        label_source VARCHAR(50) NOT NULL,

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

        reason_json JSONB,
        reviewer VARCHAR(100),
        reviewed BOOLEAN NOT NULL DEFAULT FALSE,

        raw_response_json JSONB,
        error_message TEXT,

        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        CONSTRAINT fk_persona_label_review_profile
            FOREIGN KEY (persona_profile_id)
            REFERENCES persona_profile(id)
            ON DELETE CASCADE,

        CONSTRAINT uk_persona_label_review_batch_profile_source
            UNIQUE (label_batch_id, persona_profile_id, label_source),

        CONSTRAINT ck_persona_label_review_digital
            CHECK (digital_affinity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_price
            CHECK (price_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_trust
            CHECK (trust_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_convenience
            CHECK (convenience_need_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_quality
            CHECK (quality_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_novelty
            CHECK (novelty_acceptance_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_local
            CHECK (local_affinity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_family
            CHECK (family_decision_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_health
            CHECK (health_safety_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_label_review_review_dependency
            CHECK (review_dependency_score BETWEEN 0 AND 100)
    );

    CREATE INDEX IF NOT EXISTS idx_persona_label_review_profile_id
        ON persona_label_review(persona_profile_id);

    CREATE INDEX IF NOT EXISTS idx_persona_label_review_batch_id
        ON persona_label_review(label_batch_id);

    CREATE INDEX IF NOT EXISTS idx_persona_label_review_source
        ON persona_label_review(label_source);

    CREATE INDEX IF NOT EXISTS idx_persona_label_review_reviewed
        ON persona_label_review(reviewed);

    COMMENT ON TABLE persona_label_review IS 'DeepSeek pseudo-label과 사람 검수 라벨을 저장하는 테이블';
    COMMENT ON COLUMN persona_label_review.id IS '라벨 리뷰 내부 식별자';
    COMMENT ON COLUMN persona_label_review.persona_profile_id IS '라벨 대상 페르소나 프로필 식별자';
    COMMENT ON COLUMN persona_label_review.label_batch_id IS '라벨링 배치 식별자';
    COMMENT ON COLUMN persona_label_review.label_source IS '라벨 출처. DEEPSEEK_PSEUDO, HUMAN, HUMAN_CORRECTED';
    COMMENT ON COLUMN persona_label_review.digital_affinity_score IS '디지털 서비스나 앱 사용에 익숙한 정도';
    COMMENT ON COLUMN persona_label_review.price_sensitivity_score IS '가격, 가성비, 생활비에 민감한 정도';
    COMMENT ON COLUMN persona_label_review.trust_sensitivity_score IS '후기, 검증, 인증, 운영자 신뢰를 중요하게 보는 정도';
    COMMENT ON COLUMN persona_label_review.convenience_need_score IS '시간 절약, 편의성, 자동화 니즈가 큰 정도';
    COMMENT ON COLUMN persona_label_review.quality_sensitivity_score IS '품질, 꼼꼼함, 전문성, 위생, 완성도를 중시하는 정도';
    COMMENT ON COLUMN persona_label_review.novelty_acceptance_score IS '새로운 서비스, 상품, 방식에 열려 있는 정도';
    COMMENT ON COLUMN persona_label_review.local_affinity_score IS '동네, 지역, 단골, 오프라인 상권에 반응할 가능성';
    COMMENT ON COLUMN persona_label_review.family_decision_score IS '가족, 배우자, 자녀, 부모 관련 의사결정 영향도';
    COMMENT ON COLUMN persona_label_review.health_safety_sensitivity_score IS '건강, 안전, 위생, 재난, 의료, 식품 안정성에 민감한 정도';
    COMMENT ON COLUMN persona_label_review.review_dependency_score IS '후기, 평판, 추천, 지인 평가에 의존하는 정도';
    COMMENT ON COLUMN persona_label_review.reason_json IS '점수별 판단 근거 JSON';
    COMMENT ON COLUMN persona_label_review.reviewer IS '사람 검수자';
    COMMENT ON COLUMN persona_label_review.reviewed IS '사람 검수 완료 여부';
    COMMENT ON COLUMN persona_label_review.raw_response_json IS 'DeepSeek 원본 응답 JSON';
    COMMENT ON COLUMN persona_label_review.error_message IS '라벨링 실패 또는 파싱 실패 메시지';
    COMMENT ON COLUMN persona_label_review.created_at IS '라벨 생성 시각';
    COMMENT ON COLUMN persona_label_review.updated_at IS '라벨 수정 시각';
    """

    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()


def upsert_label_batch(conn, args, sample_size: int, status: str) -> None:
    sql = """
    INSERT INTO persona_label_batch (
        label_batch_id,
        batch_name,
        sample_size,
        label_source,
        model_name,
        prompt_version,
        input_csv_path,
        output_jsonl_path,
        status
    )
    VALUES (%s, %s, %s, 'DEEPSEEK_PSEUDO', %s, %s, %s, %s, %s)
    ON CONFLICT (label_batch_id) DO UPDATE SET
        batch_name = EXCLUDED.batch_name,
        sample_size = EXCLUDED.sample_size,
        label_source = EXCLUDED.label_source,
        model_name = EXCLUDED.model_name,
        prompt_version = EXCLUDED.prompt_version,
        input_csv_path = EXCLUDED.input_csv_path,
        output_jsonl_path = EXCLUDED.output_jsonl_path,
        status = EXCLUDED.status,
        updated_at = CURRENT_TIMESTAMP;
    """

    with conn.cursor() as cur:
        cur.execute(
            sql,
            (
                args.label_batch_id,
                args.batch_name,
                sample_size,
                args.model,
                args.prompt_version,
                args.input,
                args.output_jsonl,
                status,
            ),
        )
    conn.commit()


def update_batch_status(conn, label_batch_id: str, status: str) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE persona_label_batch
            SET status = %s,
                updated_at = CURRENT_TIMESTAMP
            WHERE label_batch_id = %s
            """,
            (status, label_batch_id),
        )
    conn.commit()


def read_csv_rows(input_path: str, limit: Optional[int]) -> List[Dict[str, Any]]:
    with open(input_path, newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    if limit is not None:
        rows = rows[:limit]

    return rows


def load_completed_ids_from_jsonl(output_jsonl: str) -> set:
    completed = set()
    path = Path(output_jsonl)

    if not path.exists():
        return completed

    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            try:
                obj = json.loads(line)
                if obj.get("ok") and obj.get("persona_profile_id") is not None:
                    completed.add(str(obj["persona_profile_id"]))
            except Exception:
                continue

    return completed


def is_already_labeled(conn, label_batch_id: str, persona_profile_id: int) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1
                FROM persona_label_review
                WHERE label_batch_id = %s
                  AND persona_profile_id = %s
                  AND label_source = 'DEEPSEEK_PSEUDO'
            )
            """,
            (label_batch_id, persona_profile_id),
        )
        return bool(cur.fetchone()[0])

def build_system_prompt() -> str:
    return """
너는 한국 시장 조사 서비스를 위한 페르소나 성향 라벨링 전문가다.
너의 임무는 주어진 한국형 페르소나 설명을 읽고 10개 성향 점수를 0~100 사이 정수로 평가하는 것이다.

중요 원칙:
- 주어진 페르소나 정보에서 추론 가능한 내용만 반영한다.
- 확실하지 않으면 50에 가깝게 둔다.
- 나이, 직업, 가족형태, 주거형태, 관심사, 생활 방식, 소비 단서를 함께 본다.
- 특정 직업이나 연령에 대한 편견만으로 극단 점수를 주지 않는다.
- 모든 점수는 0~100 정수다.
- 근거는 짧고 구체적으로 작성한다.
- 반드시 JSON만 출력한다. 설명 문장, 마크다운, 코드블록은 출력하지 않는다.

localAffinityScore 특별 기준:
- localAffinityScore는 단순히 특정 지역에 거주한다는 뜻이 아니다.
- 거주 지역, 출신 지역, 여행지, 도시명, 구/군 이름만으로 점수를 높이지 마라.
- 지역명이 많이 등장해도 동네 상권, 이웃, 단골, 오프라인 모임, 경로당, 복지관, 지역 봉사, 전통시장, 지역 커뮤니티 단서가 없으면 40~55 범위에 둔다.
- 동네 단골집, 이웃 관계, 지역사회 활동, 복지관 봉사, 경로당 친목, 전통시장 이용, 동네 상권 애착이 명확하면 65~85를 줄 수 있다.
- 지역 기반 사업자, 자영업자, 동네 고객과 직접 만나는 직업이고 지역 상권 맥락이 강하면 70 이상을 줄 수 있다.
- 단순 산책, 여행, 거주지 설명, 자연 풍경 선호는 localAffinityScore의 강한 근거가 아니다.
""".strip()

def build_user_prompt(row: Dict[str, Any]) -> str:
    # search_text가 너무 길면 비용이 늘어난다.
    # 라벨링에는 2500~3500자 정도면 충분하다.
    search_text = str(row.get("search_text") or "").strip()
    if len(search_text) > 3200:
        search_text = search_text[:3200].rstrip()

    persona_summary = str(row.get("persona_summary") or "").strip()
    if len(persona_summary) > 700:
        persona_summary = persona_summary[:700].rstrip()

    return f"""
다음 페르소나의 성향 점수를 평가해라.

[기본 정보]
personaProfileId: {row.get("persona_profile_id")}
age: {row.get("age")}
ageGroup: {row.get("age_group")}
gender: {row.get("gender")}
region: {row.get("region")}
province: {row.get("province")}
district: {row.get("district")}
occupation: {row.get("occupation")}
occupationGroup: {row.get("occupation_group")}
isJobless: {row.get("is_jobless")}
educationLevel: {row.get("education_level")}
familyType: {row.get("family_type")}
housingType: {row.get("housing_type")}

[요약]
{persona_summary}

[상세 설명]
{search_text}

[점수 정의]
digitalAffinityScore: 디지털 서비스나 앱 사용에 익숙한 정도
priceSensitivityScore: 가격, 가성비, 생활비에 민감한 정도
trustSensitivityScore: 후기, 검증, 인증, 운영자 신뢰를 중요하게 보는 정도
convenienceNeedScore: 시간 절약, 편의성, 자동화 니즈가 큰 정도
qualitySensitivityScore: 품질, 꼼꼼함, 전문성, 위생, 완성도를 중시하는 정도
noveltyAcceptanceScore: 새로운 서비스, 상품, 방식에 열려 있는 정도
localAffinityScore: 단순 거주지가 아니라 동네 상권, 단골, 이웃 관계, 지역 커뮤니티, 오프라인 모임, 경로당, 복지관, 전통시장, 지역 봉사 등에 반응할 가능성
familyDecisionScore: 가족, 배우자, 자녀, 부모, 손주 관련 의사결정 영향도
healthSafetySensitivityScore: 건강, 안전, 위생, 재난, 의료, 식품 안정성에 민감한 정도
reviewDependencyScore: 후기, 평판, 추천, 지인 평가에 의존하는 정도

[localAffinityScore 판단 주의]
- 거주 지역, 출신 지역, 여행지, 도시명만으로 localAffinityScore를 높이지 마라.
- 지역명이 등장해도 동네 상권, 단골, 이웃, 지역 커뮤니티, 복지관, 경로당, 전통시장, 지역 봉사 단서가 없으면 보통 40~55점이다.
- 명확한 지역사회 활동이나 단골/이웃/오프라인 상권 맥락이 있으면 65점 이상 가능하다.

[출력 JSON 형식]
{{
  "digitalAffinityScore": 50,
  "priceSensitivityScore": 50,
  "trustSensitivityScore": 50,
  "convenienceNeedScore": 50,
  "qualitySensitivityScore": 50,
  "noveltyAcceptanceScore": 50,
  "localAffinityScore": 50,
  "familyDecisionScore": 50,
  "healthSafetySensitivityScore": 50,
  "reviewDependencyScore": 50,
  "reasons": {{
    "digitalAffinityScore": "짧은 근거",
    "priceSensitivityScore": "짧은 근거",
    "trustSensitivityScore": "짧은 근거",
    "convenienceNeedScore": "짧은 근거",
    "qualitySensitivityScore": "짧은 근거",
    "noveltyAcceptanceScore": "짧은 근거",
    "localAffinityScore": "짧은 근거",
    "familyDecisionScore": "짧은 근거",
    "healthSafetySensitivityScore": "짧은 근거",
    "reviewDependencyScore": "짧은 근거"
  }}
}}

반드시 위 JSON 형식만 출력해라.
""".strip()


def call_deepseek(args, row: Dict[str, Any]) -> Dict[str, Any]:
    api_key = os.environ.get(args.api_key_env)

    if not api_key:
        raise RuntimeError(f"환경변수 {args.api_key_env}가 설정되어 있지 않습니다.")

    url = args.api_base.rstrip("/") + "/chat/completions"

    body = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": build_system_prompt()},
            {"role": "user", "content": build_user_prompt(row)},
        ],
        "temperature": args.temperature,
        "max_tokens": args.max_tokens,
        "stream": False,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
    }

    data = json.dumps(body, ensure_ascii=False).encode("utf-8")

    req = urllib.request.Request(
        url=url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=args.timeout_seconds) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw)


def extract_content(api_response: Dict[str, Any]) -> str:
    return api_response["choices"][0]["message"]["content"]


def parse_model_json(content: str) -> Dict[str, Any]:
    content = content.strip()

    # 혹시 코드블록이 섞인 경우 방어
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?", "", content).strip()
        content = re.sub(r"```$", "", content).strip()

    try:
        return json.loads(content)
    except Exception:
        # JSON 앞뒤에 문장이 섞인 경우 방어
        start = content.find("{")
        end = content.rfind("}")
        if start >= 0 and end > start:
            return json.loads(content[start : end + 1])
        raise


def clamp_score(value: Any) -> int:
    try:
        score = int(round(float(value)))
    except Exception:
        raise ValueError(f"점수 값을 정수로 변환할 수 없습니다: {value}")

    return max(0, min(100, score))


def validate_label(label: Dict[str, Any]) -> Dict[str, Any]:
    normalized = {}

    for key in SCORE_KEYS:
        if key not in label:
            raise ValueError(f"필수 점수 누락: {key}")
        normalized[key] = clamp_score(label[key])

    reasons = label.get("reasons")
    if not isinstance(reasons, dict):
        reasons = {}

    # reasons가 비어 있어도 DB 저장은 가능하게 하되, key는 맞춰준다.
    normalized_reasons = {}
    for key in SCORE_KEYS:
        reason = reasons.get(key, "")
        normalized_reasons[key] = str(reason).strip()[:300]

    normalized["reasons"] = normalized_reasons
    return normalized


def insert_label_review(
    conn,
    row: Dict[str, Any],
    label_batch_id: str,
    label: Dict[str, Any],
    raw_response: Dict[str, Any],
) -> None:
    persona_profile_id = int(row["persona_profile_id"])

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
        %s, %s, 'DEEPSEEK_PSEUDO',

        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,

        %s,
        NULL,
        FALSE,
        %s,
        NULL
    )
    ON CONFLICT (label_batch_id, persona_profile_id, label_source) DO UPDATE SET
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
        error_message = NULL,
        updated_at = CURRENT_TIMESTAMP;
    """

    with conn.cursor() as cur:
        cur.execute(
            sql,
            (
                persona_profile_id,
                label_batch_id,
                label["digitalAffinityScore"],
                label["priceSensitivityScore"],
                label["trustSensitivityScore"],
                label["convenienceNeedScore"],
                label["qualitySensitivityScore"],
                label["noveltyAcceptanceScore"],
                label["localAffinityScore"],
                label["familyDecisionScore"],
                label["healthSafetySensitivityScore"],
                label["reviewDependencyScore"],
                Json(label["reasons"], dumps=lambda obj: json.dumps(obj, ensure_ascii=False)),
                Json(raw_response, dumps=lambda obj: json.dumps(obj, ensure_ascii=False, default=str)),
            ),
        )


def upsert_feature_score(
    conn,
    row: Dict[str, Any],
    label: Dict[str, Any],
    score_model_version: str,
) -> None:
    persona_profile_id = int(row["persona_profile_id"])

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
    VALUES (
        %s,

        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,

        'DEEPSEEK_PSEUDO',
        %s
    )
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
        cur.execute(
            sql,
            (
                persona_profile_id,
                label["digitalAffinityScore"],
                label["priceSensitivityScore"],
                label["trustSensitivityScore"],
                label["convenienceNeedScore"],
                label["qualitySensitivityScore"],
                label["noveltyAcceptanceScore"],
                label["localAffinityScore"],
                label["familyDecisionScore"],
                label["healthSafetySensitivityScore"],
                label["reviewDependencyScore"],
                score_model_version,
            ),
        )


def append_jsonl(path: str, obj: Dict[str, Any]) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)

    with p.open("a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False, default=str) + "\n")


def process_one(conn, args, row: Dict[str, Any]) -> Tuple[bool, Optional[str]]:
    persona_profile_id = str(row.get("persona_profile_id"))

    if args.skip_db_existing and is_already_labeled(conn, args.label_batch_id, int(persona_profile_id)):
        return True, "db_existing"

    last_error = None

    for attempt in range(1, args.max_retries + 2):
        try:
            api_response = call_deepseek(args, row)
            content = extract_content(api_response)
            parsed = parse_model_json(content)
            label = validate_label(parsed)

            insert_label_review(conn, row, args.label_batch_id, label, api_response)

            if args.insert_feature_score:
                upsert_feature_score(conn, row, label, args.score_model_version)

            conn.commit()

            append_jsonl(
                args.output_jsonl,
                {
                    "ok": True,
                    "persona_profile_id": int(persona_profile_id),
                    "label_batch_id": args.label_batch_id,
                    "label": label,
                    "usage": api_response.get("usage"),
                    "model": api_response.get("model"),
                    "created_at": datetime.now().isoformat(),
                },
            )

            return True, None

        except urllib.error.HTTPError as e:
            body = ""
            try:
                body = e.read().decode("utf-8")
            except Exception:
                pass

            last_error = f"HTTPError {e.code}: {body or e.reason}"

        except urllib.error.URLError as e:
            last_error = f"URLError: {e.reason}"

        except Exception as e:
            last_error = str(e)

        conn.rollback()

        if attempt <= args.max_retries + 1:
            sleep_seconds = args.retry_sleep_seconds * attempt
            log(f"[RETRY] persona_profile_id={persona_profile_id}, attempt={attempt}, error={last_error}, sleep={sleep_seconds}s")
            time.sleep(sleep_seconds)

    append_jsonl(
        args.error_jsonl,
        {
            "ok": False,
            "persona_profile_id": persona_profile_id,
            "label_batch_id": args.label_batch_id,
            "error": last_error,
            "created_at": datetime.now().isoformat(),
            "row": row,
        },
    )

    return False, last_error


def print_final_report(conn, label_batch_id: str) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT COUNT(*)
            FROM persona_label_review
            WHERE label_batch_id = %s
              AND label_source = 'DEEPSEEK_PSEUDO'
            """,
            (label_batch_id,),
        )
        count = cur.fetchone()[0]

        cur.execute(
            """
            SELECT
                MIN(digital_affinity_score), ROUND(AVG(digital_affinity_score), 2), MAX(digital_affinity_score),
                MIN(price_sensitivity_score), ROUND(AVG(price_sensitivity_score), 2), MAX(price_sensitivity_score),
                MIN(trust_sensitivity_score), ROUND(AVG(trust_sensitivity_score), 2), MAX(trust_sensitivity_score),
                MIN(convenience_need_score), ROUND(AVG(convenience_need_score), 2), MAX(convenience_need_score),
                MIN(quality_sensitivity_score), ROUND(AVG(quality_sensitivity_score), 2), MAX(quality_sensitivity_score),
                MIN(novelty_acceptance_score), ROUND(AVG(novelty_acceptance_score), 2), MAX(novelty_acceptance_score),
                MIN(local_affinity_score), ROUND(AVG(local_affinity_score), 2), MAX(local_affinity_score),
                MIN(family_decision_score), ROUND(AVG(family_decision_score), 2), MAX(family_decision_score),
                MIN(health_safety_sensitivity_score), ROUND(AVG(health_safety_sensitivity_score), 2), MAX(health_safety_sensitivity_score),
                MIN(review_dependency_score), ROUND(AVG(review_dependency_score), 2), MAX(review_dependency_score)
            FROM persona_label_review
            WHERE label_batch_id = %s
              AND label_source = 'DEEPSEEK_PSEUDO'
            """,
            (label_batch_id,),
        )
        stats = cur.fetchone()

    print()
    print("=" * 80)
    print("DeepSeek 라벨링 결과 요약")
    print("=" * 80)
    print(f"label_batch_id: {label_batch_id}")
    print(f"saved_count: {count}")

    if stats and stats[0] is not None:
        labels = [
            "digital_affinity",
            "price_sensitivity",
            "trust_sensitivity",
            "convenience_need",
            "quality_sensitivity",
            "novelty_acceptance",
            "local_affinity",
            "family_decision",
            "health_safety_sensitivity",
            "review_dependency",
        ]

        idx = 0
        for label in labels:
            min_v, avg_v, max_v = stats[idx], stats[idx + 1], stats[idx + 2]
            print(f"- {label}: min={min_v}, avg={avg_v}, max={max_v}")
            idx += 3

    print("=" * 80)


def parse_args():
    parser = argparse.ArgumentParser(description="CustomerPreview DeepSeek pseudo-label 생성 스크립트")

    parser.add_argument("--input", required=True, help="샘플 CSV 경로")
    parser.add_argument("--output-jsonl", required=True, help="성공 결과 JSONL 경로")
    parser.add_argument("--error-jsonl", required=True, help="실패 결과 JSONL 경로")
    parser.add_argument("--label-batch-id", required=True, help="라벨링 배치 ID")
    parser.add_argument("--batch-name", default="DeepSeek pseudo-label batch", help="배치 이름")
    parser.add_argument("--prompt-version", default="persona_score_prompt_v1", help="프롬프트 버전")
    parser.add_argument("--score-model-version", default="deepseek_v4_flash_prompt_v1", help="persona_feature_score 저장용 버전명")

    parser.add_argument("--limit", type=int, default=None, help="CSV에서 처리할 최대 row 수")
    parser.add_argument("--start-index", type=int, default=0, help="CSV row 시작 index. 0부터 시작")
    parser.add_argument("--sleep-seconds", type=float, default=0.2, help="요청 사이 대기 시간")
    parser.add_argument("--max-retries", type=int, default=2, help="실패 시 재시도 횟수")
    parser.add_argument("--retry-sleep-seconds", type=float, default=2.0, help="재시도 기본 대기 시간")
    parser.add_argument("--timeout-seconds", type=int, default=120, help="API timeout 초")

    parser.add_argument("--api-key-env", default="DEEPSEEK_API_KEY", help="DeepSeek API key 환경변수명")
    parser.add_argument("--api-base", default="https://api.deepseek.com", help="DeepSeek API base URL")
    parser.add_argument("--model", default="deepseek-v4-flash", help="DeepSeek 모델명")
    parser.add_argument("--temperature", type=float, default=0.0, help="라벨링 일관성을 위해 기본 0.0")
    parser.add_argument("--max-tokens", type=int, default=1200, help="응답 최대 토큰")

    parser.add_argument("--insert-feature-score", action="store_true", default=True, help="persona_feature_score에도 DEEPSEEK_PSEUDO 저장")
    parser.add_argument("--no-insert-feature-score", action="store_false", dest="insert_feature_score", help="persona_feature_score 저장 안 함")
    parser.add_argument("--skip-jsonl-existing", action="store_true", help="output_jsonl에 성공 기록이 있으면 skip")
    parser.add_argument("--skip-db-existing", action="store_true", default=True, help="DB에 동일 batch/persona 라벨이 있으면 skip")
    parser.add_argument("--no-skip-db-existing", action="store_false", dest="skip_db_existing", help="DB 기존 라벨이 있어도 재처리")

    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="postgres")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")

    return parser.parse_args()


def main():
    args = parse_args()

    started_at = datetime.now()

    log("[START] DeepSeek persona pseudo-label 생성 시작")
    log(f"[CONFIG] input={args.input}")
    log(f"[CONFIG] output_jsonl={args.output_jsonl}")
    log(f"[CONFIG] error_jsonl={args.error_jsonl}")
    log(f"[CONFIG] db={args.host}:{args.port}/{args.dbname}, user={args.user}")
    log(f"[CONFIG] label_batch_id={args.label_batch_id}")
    log(f"[CONFIG] model={args.model}, api_base={args.api_base}")
    log(f"[CONFIG] limit={args.limit}, start_index={args.start_index}")

    rows = read_csv_rows(args.input, args.limit)
    if args.start_index > 0:
        rows = rows[args.start_index :]

    log(f"[CSV] 처리 대상 rows={len(rows)}")

    completed_from_jsonl = set()
    if args.skip_jsonl_existing:
        completed_from_jsonl = load_completed_ids_from_jsonl(args.output_jsonl)
        log(f"[RESUME] output_jsonl 기존 성공 수={len(completed_from_jsonl)}")

    conn = connect_db(args)

    success_count = 0
    fail_count = 0
    skip_count = 0

    try:
        ensure_tables(conn)
        upsert_label_batch(conn, args, len(rows), "RUNNING")

        for idx, row in enumerate(rows, start=1):
            persona_profile_id = str(row.get("persona_profile_id"))

            if args.skip_jsonl_existing and persona_profile_id in completed_from_jsonl:
                skip_count += 1
                if idx % 10 == 0:
                    log(f"[SKIP] idx={idx}, persona_profile_id={persona_profile_id}, reason=jsonl_existing")
                continue

            ok, error = process_one(conn, args, row)

            if ok:
                success_count += 1
            else:
                fail_count += 1

            if idx % 5 == 0 or idx == len(rows):
                elapsed = datetime.now() - started_at
                log(
                    f"[PROGRESS] {idx}/{len(rows)} "
                    f"success={success_count}, fail={fail_count}, skip={skip_count}, elapsed={elapsed}"
                )

            if args.sleep_seconds > 0:
                time.sleep(args.sleep_seconds)

        final_status = "COMPLETED" if fail_count == 0 else "COMPLETED_WITH_ERRORS"
        update_batch_status(conn, args.label_batch_id, final_status)
        print_final_report(conn, args.label_batch_id)

    except KeyboardInterrupt:
        conn.rollback()
        update_batch_status(conn, args.label_batch_id, "INTERRUPTED")
        log("[INTERRUPT] 사용자에 의해 중단됨")
        raise

    except Exception as e:
        conn.rollback()
        try:
            update_batch_status(conn, args.label_batch_id, "FAILED")
        except Exception:
            pass
        log("[ERROR] DeepSeek 라벨링 실패")
        log(str(e))
        raise

    finally:
        conn.close()
        elapsed = datetime.now() - started_at
        log(f"[END] DB 연결 종료, elapsed={elapsed}")


if __name__ == "__main__":
    main()
