#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
CustomerPreview / 미리고객 - Persona MVP Import v3

목표:
- PostgreSQL persona 테이블 생성 + 한글 COMMENT 생성
- 강제 종료 후 남은 persona 관련 lock 해제 옵션 제공
- Parquet 파일/디렉터리 읽기
- 멀티스레드 batch insert
- persona_source_record 저장
- persona_profile 생성
- persona_feature_score RULE_BASED 점수 생성
- 검증 리포트 출력

주의:
- worker마다 PostgreSQL 커넥션을 별도로 사용합니다.
- 너무 많은 worker는 오히려 PostgreSQL을 느리게 만들 수 있습니다.
- 로컬 Mac + PostgreSQL 기준 workers=4부터 시작하는 것을 추천합니다.
"""

import argparse
import hashlib
import json
import os
import queue
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from typing import Any, Dict, Iterable, List, Optional, Tuple

import psycopg2
import pyarrow.dataset as ds
from psycopg2.extras import Json, execute_values


SOURCE_COLUMNS = [
    "uuid",
    "professional_persona",
    "sports_persona",
    "arts_persona",
    "travel_persona",
    "culinary_persona",
    "family_persona",
    "persona",
    "cultural_background",
    "skills_and_expertise",
    "skills_and_expertise_list",
    "hobbies_and_interests",
    "hobbies_and_interests_list",
    "career_goals_and_ambitions",
    "sex",
    "age",
    "marital_status",
    "military_status",
    "family_type",
    "housing_type",
    "education_level",
    "bachelors_field",
    "occupation",
    "district",
    "province",
    "country",
]

PERSONA_TABLES = [
    "persona_feature_score",
    "persona_profile",
    "persona_source_record",
]


print_lock = threading.Lock()


def log(message: str) -> None:
    with print_lock:
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


def create_tables(conn):
    ddl = """
    CREATE TABLE IF NOT EXISTS persona_source_record (
        id BIGSERIAL PRIMARY KEY,
        source VARCHAR(100) NOT NULL,
        source_id VARCHAR(100) NOT NULL,

        professional_persona TEXT,
        sports_persona TEXT,
        arts_persona TEXT,
        travel_persona TEXT,
        culinary_persona TEXT,
        family_persona TEXT,
        persona TEXT,
        cultural_background TEXT,
        skills_and_expertise TEXT,
        skills_and_expertise_list JSONB,
        hobbies_and_interests TEXT,
        hobbies_and_interests_list JSONB,
        career_goals_and_ambitions TEXT,

        sex VARCHAR(50),
        age INTEGER,
        marital_status VARCHAR(100),
        military_status VARCHAR(100),
        family_type VARCHAR(100),
        housing_type VARCHAR(100),
        education_level VARCHAR(100),
        bachelors_field VARCHAR(255),
        occupation VARCHAR(255),
        district VARCHAR(100),
        province VARCHAR(100),
        country VARCHAR(100),

        raw_json JSONB NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        CONSTRAINT uk_persona_source_record_source_id UNIQUE (source, source_id)
    );

    CREATE INDEX IF NOT EXISTS idx_persona_source_record_source_id
        ON persona_source_record(source_id);

    CREATE INDEX IF NOT EXISTS idx_persona_source_record_age
        ON persona_source_record(age);

    CREATE INDEX IF NOT EXISTS idx_persona_source_record_province
        ON persona_source_record(province);

    CREATE INDEX IF NOT EXISTS idx_persona_source_record_occupation
        ON persona_source_record(occupation);

    CREATE INDEX IF NOT EXISTS idx_persona_source_record_family_type
        ON persona_source_record(family_type);


    CREATE TABLE IF NOT EXISTS persona_profile (
        id BIGSERIAL PRIMARY KEY,
        source_record_id BIGINT NOT NULL,
        source_id VARCHAR(100) NOT NULL,

        age INTEGER,
        age_group VARCHAR(20),
        gender VARCHAR(50),
        province VARCHAR(100),
        district VARCHAR(100),
        region VARCHAR(100),
        occupation VARCHAR(255),
        education_level VARCHAR(100),
        family_type VARCHAR(100),
        housing_type VARCHAR(100),

        persona_summary TEXT,
        search_text TEXT,

        active BOOLEAN NOT NULL DEFAULT TRUE,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        CONSTRAINT fk_persona_profile_source_record
            FOREIGN KEY (source_record_id)
            REFERENCES persona_source_record(id)
            ON DELETE CASCADE,

        CONSTRAINT uk_persona_profile_source_record_id UNIQUE (source_record_id),
        CONSTRAINT uk_persona_profile_source_id UNIQUE (source_id)
    );

    CREATE INDEX IF NOT EXISTS idx_persona_profile_age_group
        ON persona_profile(age_group);

    CREATE INDEX IF NOT EXISTS idx_persona_profile_gender
        ON persona_profile(gender);

    CREATE INDEX IF NOT EXISTS idx_persona_profile_region
        ON persona_profile(region);

    CREATE INDEX IF NOT EXISTS idx_persona_profile_province
        ON persona_profile(province);

    CREATE INDEX IF NOT EXISTS idx_persona_profile_occupation
        ON persona_profile(occupation);

    CREATE INDEX IF NOT EXISTS idx_persona_profile_family_type
        ON persona_profile(family_type);

    CREATE INDEX IF NOT EXISTS idx_persona_profile_housing_type
        ON persona_profile(housing_type);

    CREATE INDEX IF NOT EXISTS idx_persona_profile_active
        ON persona_profile(active);


    CREATE TABLE IF NOT EXISTS persona_feature_score (
        id BIGSERIAL PRIMARY KEY,
        persona_profile_id BIGINT NOT NULL,

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

        score_source VARCHAR(50) NOT NULL,
        score_model_version VARCHAR(100) NOT NULL,

        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        CONSTRAINT fk_persona_feature_score_profile
            FOREIGN KEY (persona_profile_id)
            REFERENCES persona_profile(id)
            ON DELETE CASCADE,

        CONSTRAINT uk_persona_feature_score_version
            UNIQUE (persona_profile_id, score_source, score_model_version),

        CONSTRAINT ck_persona_feature_score_digital
            CHECK (digital_affinity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_price
            CHECK (price_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_trust
            CHECK (trust_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_convenience
            CHECK (convenience_need_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_quality
            CHECK (quality_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_novelty
            CHECK (novelty_acceptance_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_local
            CHECK (local_affinity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_family
            CHECK (family_decision_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_health_safety
            CHECK (health_safety_sensitivity_score BETWEEN 0 AND 100),

        CONSTRAINT ck_persona_feature_score_review
            CHECK (review_dependency_score BETWEEN 0 AND 100)
    );

    CREATE INDEX IF NOT EXISTS idx_persona_feature_score_profile_id
        ON persona_feature_score(persona_profile_id);

    CREATE INDEX IF NOT EXISTS idx_persona_feature_score_source
        ON persona_feature_score(score_source);

    CREATE INDEX IF NOT EXISTS idx_persona_feature_score_model_version
        ON persona_feature_score(score_model_version);


    COMMENT ON TABLE persona_source_record IS '페르소나 parquet 원본 데이터를 보존하는 테이블';
    COMMENT ON COLUMN persona_source_record.id IS '원본 페르소나 내부 식별자';
    COMMENT ON COLUMN persona_source_record.source IS '원본 데이터 출처명';
    COMMENT ON COLUMN persona_source_record.source_id IS '원본 데이터의 고유 식별자. parquet uuid 값';
    COMMENT ON COLUMN persona_source_record.professional_persona IS '직업 및 업무 성향 관련 원본 페르소나 설명';
    COMMENT ON COLUMN persona_source_record.sports_persona IS '스포츠 관련 원본 페르소나 설명';
    COMMENT ON COLUMN persona_source_record.arts_persona IS '예술 및 문화 관련 원본 페르소나 설명';
    COMMENT ON COLUMN persona_source_record.travel_persona IS '여행 관련 원본 페르소나 설명';
    COMMENT ON COLUMN persona_source_record.culinary_persona IS '음식 및 요리 관련 원본 페르소나 설명';
    COMMENT ON COLUMN persona_source_record.family_persona IS '가족 및 생활 관계 관련 원본 페르소나 설명';
    COMMENT ON COLUMN persona_source_record.persona IS '대표 페르소나 원문 설명';
    COMMENT ON COLUMN persona_source_record.cultural_background IS '문화적 배경 원본 정보';
    COMMENT ON COLUMN persona_source_record.skills_and_expertise IS '기술 및 전문성 원본 설명';
    COMMENT ON COLUMN persona_source_record.skills_and_expertise_list IS '기술 및 전문성 목록 원본 데이터';
    COMMENT ON COLUMN persona_source_record.hobbies_and_interests IS '취미 및 관심사 원본 설명';
    COMMENT ON COLUMN persona_source_record.hobbies_and_interests_list IS '취미 및 관심사 목록 원본 데이터';
    COMMENT ON COLUMN persona_source_record.career_goals_and_ambitions IS '경력 목표 및 포부 원본 설명';
    COMMENT ON COLUMN persona_source_record.sex IS '원본 성별 값';
    COMMENT ON COLUMN persona_source_record.age IS '원본 나이 값';
    COMMENT ON COLUMN persona_source_record.marital_status IS '원본 혼인 상태';
    COMMENT ON COLUMN persona_source_record.military_status IS '원본 병역 상태';
    COMMENT ON COLUMN persona_source_record.family_type IS '원본 가족 형태';
    COMMENT ON COLUMN persona_source_record.housing_type IS '원본 주거 형태';
    COMMENT ON COLUMN persona_source_record.education_level IS '원본 학력 수준';
    COMMENT ON COLUMN persona_source_record.bachelors_field IS '원본 학사 전공 분야';
    COMMENT ON COLUMN persona_source_record.occupation IS '원본 직업 정보';
    COMMENT ON COLUMN persona_source_record.district IS '원본 시군구 정보';
    COMMENT ON COLUMN persona_source_record.province IS '원본 시도 정보';
    COMMENT ON COLUMN persona_source_record.country IS '원본 국가 정보';
    COMMENT ON COLUMN persona_source_record.raw_json IS 'parquet row 전체를 JSON으로 보존한 값';
    COMMENT ON COLUMN persona_source_record.created_at IS '원본 데이터 저장 시각';

    COMMENT ON TABLE persona_profile IS '검색, 샘플링, 리포트 생성을 위해 정규화한 페르소나 프로필 테이블';
    COMMENT ON COLUMN persona_profile.id IS '검색용 페르소나 프로필 내부 식별자';
    COMMENT ON COLUMN persona_profile.source_record_id IS '원본 페르소나 레코드 식별자';
    COMMENT ON COLUMN persona_profile.source_id IS '원본 데이터의 고유 식별자. parquet uuid 값';
    COMMENT ON COLUMN persona_profile.age IS '정규화된 나이';
    COMMENT ON COLUMN persona_profile.age_group IS '검색 및 샘플링용 연령대';
    COMMENT ON COLUMN persona_profile.gender IS '정규화된 성별';
    COMMENT ON COLUMN persona_profile.province IS '정규화된 시도 정보';
    COMMENT ON COLUMN persona_profile.district IS '정규화된 시군구 정보';
    COMMENT ON COLUMN persona_profile.region IS '권역 정보. 예: 수도권, 충청권, 호남권';
    COMMENT ON COLUMN persona_profile.occupation IS '검색용 직업 정보';
    COMMENT ON COLUMN persona_profile.education_level IS '검색용 학력 정보';
    COMMENT ON COLUMN persona_profile.family_type IS '검색용 가족 형태';
    COMMENT ON COLUMN persona_profile.housing_type IS '검색용 주거 형태';
    COMMENT ON COLUMN persona_profile.persona_summary IS '리포트와 검수 화면에서 사용할 짧은 페르소나 요약';
    COMMENT ON COLUMN persona_profile.search_text IS '검색, 임베딩, ML 입력에 사용할 통합 페르소나 문장';
    COMMENT ON COLUMN persona_profile.active IS '서비스에서 사용 가능한 페르소나 여부';
    COMMENT ON COLUMN persona_profile.created_at IS '프로필 생성 시각';
    COMMENT ON COLUMN persona_profile.updated_at IS '프로필 수정 시각';

    COMMENT ON TABLE persona_feature_score IS '페르소나 자체의 고정 성향 점수를 저장하는 테이블';
    COMMENT ON COLUMN persona_feature_score.id IS '페르소나 성향 점수 내부 식별자';
    COMMENT ON COLUMN persona_feature_score.persona_profile_id IS '성향 점수를 부여할 페르소나 프로필 식별자';
    COMMENT ON COLUMN persona_feature_score.digital_affinity_score IS '디지털 서비스나 앱 사용에 익숙한 정도';
    COMMENT ON COLUMN persona_feature_score.price_sensitivity_score IS '가격, 가성비, 생활비에 민감한 정도';
    COMMENT ON COLUMN persona_feature_score.trust_sensitivity_score IS '후기, 검증, 인증, 운영자 신뢰를 중요하게 보는 정도';
    COMMENT ON COLUMN persona_feature_score.convenience_need_score IS '시간 절약, 편의성, 자동화 니즈가 큰 정도';
    COMMENT ON COLUMN persona_feature_score.quality_sensitivity_score IS '품질, 꼼꼼함, 전문성, 위생, 완성도를 중시하는 정도';
    COMMENT ON COLUMN persona_feature_score.novelty_acceptance_score IS '새로운 서비스, 상품, 방식에 열려 있는 정도';
    COMMENT ON COLUMN persona_feature_score.local_affinity_score IS '동네, 지역, 단골, 오프라인 상권에 반응할 가능성';
    COMMENT ON COLUMN persona_feature_score.family_decision_score IS '가족, 배우자, 자녀, 부모 관련 의사결정 영향도';
    COMMENT ON COLUMN persona_feature_score.health_safety_sensitivity_score IS '건강, 안전, 위생, 재난, 의료, 식품 안정성에 민감한 정도';
    COMMENT ON COLUMN persona_feature_score.review_dependency_score IS '후기, 평판, 추천, 지인 평가에 의존하는 정도';
    COMMENT ON COLUMN persona_feature_score.score_source IS '점수 생성 출처. 예: RULE_BASED, DEEPSEEK_PSEUDO, ML_PREDICTED, HUMAN_VERIFIED';
    COMMENT ON COLUMN persona_feature_score.score_model_version IS '점수 생성 규칙 또는 모델 버전';
    COMMENT ON COLUMN persona_feature_score.created_at IS '성향 점수 생성 시각';
    COMMENT ON COLUMN persona_feature_score.updated_at IS '성향 점수 수정 시각';
    """

    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()


def list_persona_locks(conn):
    sql = """
    SELECT DISTINCT
        a.pid,
        a.state,
        a.wait_event_type,
        a.wait_event,
        c.relname,
        LEFT(a.query, 500) AS query
    FROM pg_locks l
    JOIN pg_class c ON c.oid = l.relation
    JOIN pg_stat_activity a ON a.pid = l.pid
    WHERE c.relname IN ('persona_source_record', 'persona_profile', 'persona_feature_score')
      AND a.pid <> pg_backend_pid()
    ORDER BY a.pid;
    """

    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def kill_persona_locks(conn):
    locks = list_persona_locks(conn)

    if not locks:
        log("[LOCK] persona 테이블 관련 lock 없음")
        return

    log(f"[LOCK] persona 테이블 관련 lock {len(locks)}개 발견")

    pids = sorted({row[0] for row in locks})

    with conn.cursor() as cur:
        for pid in pids:
            log(f"[LOCK] pg_terminate_backend({pid}) 실행")
            cur.execute("SELECT pg_terminate_backend(%s)", (pid,))
            result = cur.fetchone()[0]
            log(f"[LOCK] pid={pid}, terminated={result}")

    conn.commit()
    time.sleep(1)


def truncate_persona_tables(conn):
    sql = """
    TRUNCATE TABLE
        persona_feature_score,
        persona_profile,
        persona_source_record
    RESTART IDENTITY CASCADE;
    """
    with conn.cursor() as cur:
        cur.execute(sql)
    conn.commit()


def safe_text(value: Any) -> str:
    if value is None:
        return ""

    if isinstance(value, list):
        value = ", ".join(str(v) for v in value if v is not None)

    text = str(value)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def nullable_text(value: Any) -> Optional[str]:
    text = safe_text(value)
    return text if text else None


def safe_int(value: Any) -> Optional[int]:
    if value is None:
        return None

    try:
        return int(value)
    except Exception:
        try:
            return int(float(value))
        except Exception:
            return None


def jsonb_value(value: Any):
    if value is None:
        return None
    return Json(value, dumps=lambda obj: json.dumps(obj, ensure_ascii=False, default=str))


def raw_json_value(row: Dict[str, Any]):
    return Json(row, dumps=lambda obj: json.dumps(obj, ensure_ascii=False, default=str))


def normalize_gender(sex: Any) -> str:
    text = safe_text(sex).lower()

    if text in ["male", "man", "m", "남성", "남자"]:
        return "MALE"

    if text in ["female", "woman", "f", "여성", "여자"]:
        return "FEMALE"

    if "female" in text:
        return "FEMALE"

    if "male" in text:
        return "MALE"

    return "UNKNOWN"


def build_age_group(age: Optional[int]) -> str:
    if age is None:
        return "미상"

    if age < 20:
        return "10대 이하"
    if age < 30:
        return "20대"
    if age < 40:
        return "30대"
    if age < 50:
        return "40대"
    if age < 60:
        return "50대"
    if age < 70:
        return "60대"

    return "70대 이상"


def build_region(province: Any) -> str:
    p = safe_text(province)

    if p in ["서울", "서울특별시", "경기", "경기도", "인천", "인천광역시"]:
        return "수도권"

    if p in ["부산", "부산광역시", "울산", "울산광역시", "경남", "경상남도", "경상남"]:
        return "동남권"

    if p in ["대구", "대구광역시", "경북", "경상북도", "경상북"]:
        return "대경권"

    if p in ["대전", "대전광역시", "세종", "세종특별자치시", "충남", "충청남도", "충청남", "충북", "충청북도", "충청북"]:
        return "충청권"

    if p in ["광주", "광주광역시", "전남", "전라남도", "전라남", "전북", "전라북도", "전라북"]:
        return "호남권"

    if p in ["강원", "강원도", "강원특별자치도"]:
        return "강원권"

    if p in ["제주", "제주도", "제주특별자치도"]:
        return "제주권"

    if p:
        return "기타"

    return "미상"


def limit_text(text: str, max_length: int = 1200) -> str:
    text = safe_text(text)

    if len(text) <= max_length:
        return text

    sliced = text[:max_length].rstrip()

    sentence_end_positions = [
        sliced.rfind("다."),
        sliced.rfind("요."),
        sliced.rfind("."),
        sliced.rfind("!"),
        sliced.rfind("?"),
    ]

    last_sentence_end = max(sentence_end_positions)

    if last_sentence_end >= int(max_length * 0.6):
        end_token = sliced[last_sentence_end:last_sentence_end + 2]
        add_len = 2 if end_token in ["다.", "요."] else 1
        return sliced[:last_sentence_end + add_len].rstrip()

    last_space = sliced.rfind(" ")

    if last_space >= int(max_length * 0.8):
        return sliced[:last_space].rstrip() + "..."

    return sliced.rstrip() + "..."


def contains_any(text: str, keywords: List[str]) -> bool:
    lowered = safe_text(text).lower()
    return any(keyword.lower() in lowered for keyword in keywords)


def count_keywords(text: str, keywords: List[str]) -> int:
    lowered = safe_text(text).lower()
    return sum(1 for keyword in keywords if keyword.lower() in lowered)


def clamp_score(score: int) -> int:
    return max(0, min(100, int(score)))


def build_persona_summary(row: Dict[str, Any]) -> str:
    age = safe_int(row.get("age"))
    age_group = build_age_group(age)
    gender = normalize_gender(row.get("sex"))
    province = safe_text(row.get("province"))
    district = safe_text(row.get("district"))
    occupation = safe_text(row.get("occupation"))
    family_type = safe_text(row.get("family_type"))
    housing_type = safe_text(row.get("housing_type"))
    persona = safe_text(row.get("persona"))

    gender_text = {
        "MALE": "남성",
        "FEMALE": "여성",
        "UNKNOWN": "성별 미상",
    }.get(gender, "성별 미상")

    location = " ".join([v for v in [province, district] if v])

    basic_parts = [f"{age_group} {gender_text}"]

    if location:
        basic_parts.append(f"{location} 거주")

    if occupation:
        basic_parts.append(f"직업은 {occupation}")

    if family_type:
        basic_parts.append(f"가족 형태는 {family_type}")

    if housing_type:
        basic_parts.append(f"주거 형태는 {housing_type}")

    basic_summary = ", ".join(basic_parts)

    if persona:
        return f"{basic_summary}. {limit_text(persona, 260)}"

    return f"{basic_summary}."


def build_search_text(row: Dict[str, Any]) -> str:
    age = safe_int(row.get("age"))
    age_group = build_age_group(age)
    gender = normalize_gender(row.get("sex"))
    region = build_region(row.get("province"))

    gender_text = {
        "MALE": "남성",
        "FEMALE": "여성",
        "UNKNOWN": "성별 미상",
    }.get(gender, "성별 미상")

    sections = []

    basic = []
    basic.append(f"이 페르소나는 {age_group} {gender_text}입니다.")

    province = safe_text(row.get("province"))
    district = safe_text(row.get("district"))
    if province or district:
        basic.append(f"거주 지역은 {province} {district}이며 권역은 {region}입니다.")

    occupation = safe_text(row.get("occupation"))
    if occupation:
        basic.append(f"직업은 {occupation}입니다.")

    education_level = safe_text(row.get("education_level"))
    if education_level:
        basic.append(f"학력 수준은 {education_level}입니다.")

    family_type = safe_text(row.get("family_type"))
    if family_type:
        basic.append(f"가족 형태는 {family_type}입니다.")

    housing_type = safe_text(row.get("housing_type"))
    if housing_type:
        basic.append(f"주거 형태는 {housing_type}입니다.")

    sections.append(" ".join(basic))

    text_fields = [
        ("대표 성향", "persona"),
        ("직업 관련 성향", "professional_persona"),
        ("가족 관련 성향", "family_persona"),
        ("문화적 배경", "cultural_background"),
        ("기술 및 전문성", "skills_and_expertise"),
        ("취미와 관심사", "hobbies_and_interests"),
        ("경력 목표와 포부", "career_goals_and_ambitions"),
        ("스포츠 관련 성향", "sports_persona"),
        ("예술 관련 성향", "arts_persona"),
        ("여행 관련 성향", "travel_persona"),
        ("음식 관련 성향", "culinary_persona"),
    ]

    for label, field in text_fields:
        value = limit_text(safe_text(row.get(field)), 900)
        if value:
            sections.append(f"{label}: {value}")

    search_text = " ".join(sections)
    search_text = re.sub(r"\s+", " ", search_text).strip()

    return limit_text(search_text, 8000)


def calculate_rule_based_scores(row: Dict[str, Any], search_text: str) -> Dict[str, int]:
    text = safe_text(search_text).lower()

    age = safe_int(row.get("age"))
    occupation = safe_text(row.get("occupation")).lower()
    housing_type = safe_text(row.get("housing_type")).lower()
    family_type = safe_text(row.get("family_type")).lower()
    marital_status = safe_text(row.get("marital_status")).lower()
    education_level = safe_text(row.get("education_level")).lower()
    province = safe_text(row.get("province"))
    district = safe_text(row.get("district"))

    digital_keywords = [
        "앱", "어플", "온라인", "디지털", "플랫폼", "it", "소프트웨어", "개발",
        "데이터", "ai", "인공지능", "전자상거래", "e-commerce", "sns", "모바일",
        "컴퓨터", "노트북", "스마트폰", "인터넷", "웹", "웹툰", "게임", "e스포츠",
        "엑셀", "스프레드시트", "공학", "공학도", "전기", "전자", "ict",
        "가전제품", "매뉴얼", "설정값", "채용 공고", "유튜브", "리뷰 영상",
        "카메라", "사진", "전송", "단체 채팅방", "이모티콘", "배달 앱",
    ]

    price_keywords = [
        "가격", "가성비", "절약", "생활비", "할인", "저렴", "비용", "예산",
        "경제적", "합리적", "월세", "전세", "budget", "cost",
        "소박", "집밥", "집에서", "외식 횟수는 한 달에 한 번",
        "한 달에 한 번", "포장해", "나눠 먹", "간편하게 식사",
        "적절한 연봉", "연봉 수준", "구직", "구직중", "취업 준비",
        "채용 공고", "무직", "소박한 식사", "집에서 가족들이 차려준",
    ]

    trust_keywords = [
        "후기", "리뷰", "검증", "인증", "신뢰", "안전", "보증", "평판",
        "추천", "지인", "브랜드", "공식", "전문가", "전부 파악",
        "꼼꼼하게 살핍니다", "리뷰 영상",
    ]

    convenience_keywords = [
        "편리", "편의", "간편", "자동화", "시간 절약", "빠른", "효율",
        "대행", "예약", "배송", "구독", "원스톱", "비대면", "배달 앱",
        "포장", "채용 공고", "정리",
    ]

    quality_keywords = [
        "품질", "전문성", "꼼꼼", "위생", "완성도", "프리미엄", "고급",
        "정확", "관리", "신선", "안정성", "성능", "매뉴얼", "설정값",
        "수질", "전부 파악", "정갈", "체계",
    ]

    novelty_keywords = [
        "새로운", "신규", "트렌드", "혁신", "창업", "스타트업", "실험",
        "도전", "최신", "얼리어답터", "새 방식", "유튜브", "웹툰",
        "e스포츠", "요즘 젊은",
    ]

    local_keywords = [
        "동네", "지역", "단골", "오프라인", "상권", "근처", "로컬",
        "전통시장", "커뮤니티", "이웃", "지역사회", "경로당",
        "복지관", "봉사", "친목 모임", "단골집",
    ]

    family_keywords = [
        "가족", "자녀", "부모", "배우자", "아이", "육아", "손주", "가정",
        "부양", "맞벌이", "교육", "자식", "어머니",
    ]

    health_safety_keywords = [
        "건강", "안전", "위생", "의료", "병원", "식품", "재난", "질병",
        "운동", "영양", "케어", "보호", "청결", "고혈압", "당뇨",
        "질환", "검진", "스트레칭", "수질", "온천",
    ]

    scores = {
        "digital_affinity_score": 50,
        "price_sensitivity_score": 50,
        "trust_sensitivity_score": 50,
        "convenience_need_score": 50,
        "quality_sensitivity_score": 50,
        "novelty_acceptance_score": 50,
        "local_affinity_score": 50,
        "family_decision_score": 50,
        "health_safety_sensitivity_score": 50,
        "review_dependency_score": 50,
    }

    if age is not None:
        if age < 30:
            scores["digital_affinity_score"] += 20
            scores["novelty_acceptance_score"] += 12
            scores["price_sensitivity_score"] += 6
        elif age < 40:
            scores["digital_affinity_score"] += 12
            scores["convenience_need_score"] += 8
            scores["novelty_acceptance_score"] += 6
            scores["price_sensitivity_score"] += 3
        elif age < 50:
            scores["convenience_need_score"] += 6
            scores["quality_sensitivity_score"] += 5
        elif age < 60:
            scores["trust_sensitivity_score"] += 4
            scores["quality_sensitivity_score"] += 5
            scores["health_safety_sensitivity_score"] += 4
        elif age < 70:
            scores["digital_affinity_score"] -= 12
            scores["trust_sensitivity_score"] += 7
            scores["quality_sensitivity_score"] += 6
            scores["health_safety_sensitivity_score"] += 8
            scores["review_dependency_score"] += 5
            scores["novelty_acceptance_score"] -= 6
            scores["price_sensitivity_score"] += 6
        else:
            scores["digital_affinity_score"] -= 14
            scores["trust_sensitivity_score"] += 9
            scores["health_safety_sensitivity_score"] += 12
            scores["review_dependency_score"] += 7
            scores["novelty_acceptance_score"] -= 8
            scores["price_sensitivity_score"] += 10

    digital_jobs = ["개발", "엔지니어", "디자이너", "마케터", "기획", "데이터", "it", "학생", "연구"]
    if contains_any(occupation, digital_jobs):
        scores["digital_affinity_score"] += 12
        scores["novelty_acceptance_score"] += 6

    busy_jobs = ["직장", "회사", "관리자", "대표", "사업", "자영업", "전문직", "간호", "교사", "강사", "영업"]
    if contains_any(occupation, busy_jobs):
        scores["convenience_need_score"] += 8

    price_sensitive_occupation_keywords = [
        "무직", "구직", "구직중", "취업 준비", "방문강사", "서비스 종사원",
        "음식 서비스", "영업원", "운반원", "운전원", "배달", "일용",
        "계약직", "아르바이트", "프리랜서",
    ]

    stable_or_high_income_keywords = [
        "관리자", "전문직", "공무원", "교수", "의사", "변호사", "회계사",
        "대표", "임원",
    ]

    if contains_any(occupation, price_sensitive_occupation_keywords):
        scores["price_sensitivity_score"] += 10

    if "무직" in occupation:
        scores["price_sensitivity_score"] += 10

    if contains_any(text, ["구직", "구직중", "취업 준비", "채용 공고", "연봉 수준", "적절한 연봉"]):
        scores["price_sensitivity_score"] += 12

    if contains_any(occupation, stable_or_high_income_keywords):
        scores["price_sensitivity_score"] -= 5

    if contains_any(education_level, ["무학", "초등학교", "중학교"]):
        scores["price_sensitivity_score"] += 7
    elif contains_any(education_level, ["고등학교"]):
        scores["price_sensitivity_score"] += 3

    if contains_any(housing_type, ["월세", "전세", "임대", "다세대", "연립", "원룸", "고시원", "주택 이외"]):
        scores["price_sensitivity_score"] += 8

    if contains_any(family_type, ["혼자 거주"]):
        scores["price_sensitivity_score"] += 5

    if age is not None and age < 35 and contains_any(family_type, ["부모와 동거"]):
        scores["price_sensitivity_score"] += 5

    if contains_any(family_type, ["자녀", "배우자·자녀", "부양", "손주"]):
        scores["price_sensitivity_score"] += 6

    if contains_any(family_type, ["가족", "자녀", "부부", "부모", "아이", "손주"]):
        scores["family_decision_score"] += 15
        scores["health_safety_sensitivity_score"] += 5

    if contains_any(marital_status, ["married", "기혼", "결혼"]):
        scores["family_decision_score"] += 10
        scores["trust_sensitivity_score"] += 4

    if contains_any(education_level, ["대학", "학사", "석사", "박사", "bachelor", "master", "phd"]):
        scores["quality_sensitivity_score"] += 5

    if province or district:
        scores["local_affinity_score"] += 2

    premium_or_leisure_keywords = [
        "백화점", "쇼윈도", "온천 여행", "풀빌라", "이자카야", "하이볼",
        "사케", "초밥", "수제 버거", "프리미엄", "고급", "유명한 식당",
    ]

    if contains_any(text, premium_or_leisure_keywords):
        scores["price_sensitivity_score"] -= 5

    scores["digital_affinity_score"] += min(count_keywords(text, digital_keywords) * 6, 30)
    scores["price_sensitivity_score"] += min(count_keywords(text, price_keywords) * 5, 25)
    scores["trust_sensitivity_score"] += min(count_keywords(text, trust_keywords) * 5, 25)
    scores["convenience_need_score"] += min(count_keywords(text, convenience_keywords) * 6, 24)
    scores["quality_sensitivity_score"] += min(count_keywords(text, quality_keywords) * 5, 25)
    scores["novelty_acceptance_score"] += min(count_keywords(text, novelty_keywords) * 6, 24)
    scores["local_affinity_score"] += min(count_keywords(text, local_keywords) * 6, 28)
    scores["family_decision_score"] += min(count_keywords(text, family_keywords) * 6, 30)
    scores["health_safety_sensitivity_score"] += min(count_keywords(text, health_safety_keywords) * 7, 35)

    engineering_keywords = ["공학", "공학도", "전기", "전자", "ict", "설비", "엑셀", "매뉴얼", "설정값"]
    if contains_any(text, engineering_keywords):
        scores["digital_affinity_score"] += 8
        scores["quality_sensitivity_score"] += 5

    digital_content_keywords = ["웹툰", "e스포츠", "유튜브", "리뷰 영상", "단체 채팅방", "이모티콘", "사진", "전송", "배달 앱"]
    if contains_any(text, digital_content_keywords):
        scores["digital_affinity_score"] += 8
        scores["novelty_acceptance_score"] += 4

    health_direct_keywords = ["고혈압", "당뇨", "질환", "검진", "건강 관리", "병원"]
    if contains_any(text, health_direct_keywords):
        scores["health_safety_sensitivity_score"] += 15

    review_keywords = ["후기", "리뷰", "평판", "추천", "지인", "별점", "검증", "리뷰 영상"]
    scores["review_dependency_score"] += min(count_keywords(text, review_keywords) * 7, 28)

    if scores["trust_sensitivity_score"] >= 70:
        scores["review_dependency_score"] += 8

    return {key: clamp_score(value) for key, value in scores.items()}


def normalized_source_id(row: Dict[str, Any]) -> str:
    source_id = safe_text(row.get("uuid"))

    if source_id:
        return source_id

    raw_for_hash = json.dumps(row, ensure_ascii=False, default=str, sort_keys=True)
    return hashlib.sha256(raw_for_hash.encode("utf-8")).hexdigest()


def build_source_values(row: Dict[str, Any], source: str) -> Tuple:
    source_id = normalized_source_id(row)

    return (
        source,
        source_id,
        nullable_text(row.get("professional_persona")),
        nullable_text(row.get("sports_persona")),
        nullable_text(row.get("arts_persona")),
        nullable_text(row.get("travel_persona")),
        nullable_text(row.get("culinary_persona")),
        nullable_text(row.get("family_persona")),
        nullable_text(row.get("persona")),
        nullable_text(row.get("cultural_background")),
        nullable_text(row.get("skills_and_expertise")),
        jsonb_value(row.get("skills_and_expertise_list")),
        nullable_text(row.get("hobbies_and_interests")),
        jsonb_value(row.get("hobbies_and_interests_list")),
        nullable_text(row.get("career_goals_and_ambitions")),
        nullable_text(row.get("sex")),
        safe_int(row.get("age")),
        nullable_text(row.get("marital_status")),
        nullable_text(row.get("military_status")),
        nullable_text(row.get("family_type")),
        nullable_text(row.get("housing_type")),
        nullable_text(row.get("education_level")),
        nullable_text(row.get("bachelors_field")),
        nullable_text(row.get("occupation")),
        nullable_text(row.get("district")),
        nullable_text(row.get("province")),
        nullable_text(row.get("country")),
        raw_json_value(row),
    )


def insert_source_records(conn, rows: List[Dict[str, Any]], source: str) -> Dict[str, int]:
    values = [build_source_values(row, source) for row in rows]

    if not values:
        return {}

    insert_sql = """
    INSERT INTO persona_source_record (
        source,
        source_id,
        professional_persona,
        sports_persona,
        arts_persona,
        travel_persona,
        culinary_persona,
        family_persona,
        persona,
        cultural_background,
        skills_and_expertise,
        skills_and_expertise_list,
        hobbies_and_interests,
        hobbies_and_interests_list,
        career_goals_and_ambitions,
        sex,
        age,
        marital_status,
        military_status,
        family_type,
        housing_type,
        education_level,
        bachelors_field,
        occupation,
        district,
        province,
        country,
        raw_json
    )
    VALUES %s
    ON CONFLICT (source, source_id) DO NOTHING;
    """

    source_ids = [value[1] for value in values]

    with conn.cursor() as cur:
        execute_values(cur, insert_sql, values, page_size=len(values))

        cur.execute(
            """
            SELECT id, source_id
            FROM persona_source_record
            WHERE source = %s
              AND source_id = ANY(%s)
            """,
            (source, source_ids),
        )
        result = cur.fetchall()

    return {source_id: record_id for record_id, source_id in result}


def build_profile_values(row: Dict[str, Any], source_id: str, source_record_id: int) -> Tuple:
    age = safe_int(row.get("age"))
    age_group = build_age_group(age)
    gender = normalize_gender(row.get("sex"))
    province = nullable_text(row.get("province"))
    district = nullable_text(row.get("district"))
    region = build_region(row.get("province"))
    occupation = nullable_text(row.get("occupation"))
    education_level = nullable_text(row.get("education_level"))
    family_type = nullable_text(row.get("family_type"))
    housing_type = nullable_text(row.get("housing_type"))
    persona_summary = build_persona_summary(row)
    search_text = build_search_text(row)

    return (
        source_record_id,
        source_id,
        age,
        age_group,
        gender,
        province,
        district,
        region,
        occupation,
        education_level,
        family_type,
        housing_type,
        persona_summary,
        search_text,
    )


def insert_profiles(conn, rows: List[Dict[str, Any]], source_id_to_record_id: Dict[str, int]) -> Dict[str, int]:
    values = []

    for row in rows:
        source_id = normalized_source_id(row)
        source_record_id = source_id_to_record_id.get(source_id)

        if source_record_id is None:
            continue

        values.append(build_profile_values(row, source_id, source_record_id))

    if not values:
        return {}

    insert_sql = """
    INSERT INTO persona_profile (
        source_record_id,
        source_id,
        age,
        age_group,
        gender,
        province,
        district,
        region,
        occupation,
        education_level,
        family_type,
        housing_type,
        persona_summary,
        search_text
    )
    VALUES %s
    ON CONFLICT (source_id) DO UPDATE SET
        source_record_id = EXCLUDED.source_record_id,
        age = EXCLUDED.age,
        age_group = EXCLUDED.age_group,
        gender = EXCLUDED.gender,
        province = EXCLUDED.province,
        district = EXCLUDED.district,
        region = EXCLUDED.region,
        occupation = EXCLUDED.occupation,
        education_level = EXCLUDED.education_level,
        family_type = EXCLUDED.family_type,
        housing_type = EXCLUDED.housing_type,
        persona_summary = EXCLUDED.persona_summary,
        search_text = EXCLUDED.search_text,
        active = TRUE,
        updated_at = CURRENT_TIMESTAMP;
    """

    source_ids = [value[1] for value in values]

    with conn.cursor() as cur:
        execute_values(cur, insert_sql, values, page_size=len(values))

        cur.execute(
            """
            SELECT id, source_id
            FROM persona_profile
            WHERE source_id = ANY(%s)
            """,
            (source_ids,),
        )
        result = cur.fetchall()

    return {source_id: profile_id for profile_id, source_id in result}


def insert_rule_based_scores(
    conn,
    rows: List[Dict[str, Any]],
    source_id_to_profile_id: Dict[str, int],
    score_model_version: str,
):
    values = []

    for row in rows:
        source_id = normalized_source_id(row)
        persona_profile_id = source_id_to_profile_id.get(source_id)

        if persona_profile_id is None:
            continue

        search_text = build_search_text(row)
        scores = calculate_rule_based_scores(row, search_text)

        values.append(
            (
                persona_profile_id,
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
                "RULE_BASED",
                score_model_version,
            )
        )

    if not values:
        return

    insert_sql = """
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
        execute_values(cur, insert_sql, values, page_size=len(values))


def process_batch(batch_no: int, rows: List[Dict[str, Any]], args) -> Tuple[int, int]:
    conn = connect_db(args)

    try:
        source_id_to_record_id = insert_source_records(conn, rows, args.source)
        source_id_to_profile_id = insert_profiles(conn, rows, source_id_to_record_id)
        insert_rule_based_scores(conn, rows, source_id_to_profile_id, args.score_model_version)
        conn.commit()
        return batch_no, len(rows)

    except Exception:
        conn.rollback()
        raise

    finally:
        conn.close()


def read_parquet_batches(parquet_path: str, batch_size: int, limit: Optional[int]) -> Iterable[Tuple[int, List[Dict[str, Any]]]]:
    log(f"[PARQUET] dataset 로딩 시작: {parquet_path}")

    if not os.path.exists(parquet_path):
        raise FileNotFoundError(parquet_path)

    dataset = ds.dataset(parquet_path, format="parquet")
    log("[PARQUET] dataset 로딩 완료")

    scanner = dataset.scanner(batch_size=batch_size)
    log("[PARQUET] scanner 생성 완료")

    total_yielded = 0
    batch_no = 0

    for record_batch in scanner.to_batches():
        if limit is not None and total_yielded >= limit:
            break

        rows = record_batch.to_pylist()

        if limit is not None:
            remaining = limit - total_yielded
            rows = rows[:remaining]

        cleaned_rows = []
        for row in rows:
            cleaned = {column: row.get(column) for column in SOURCE_COLUMNS}
            cleaned_rows.append(cleaned)

        if not cleaned_rows:
            break

        batch_no += 1
        total_yielded += len(cleaned_rows)

        yield batch_no, cleaned_rows


def import_personas_multithread(conn, args):
    started_at = datetime.now()
    total_processed = 0

    log(f"[IMPORT] 멀티스레드 import 시작 workers={args.workers}, batch_size={args.batch_size}, limit={args.limit}")

    # DDL/RESET용 connection은 여기서부터 쓰지 않고 worker별 connection을 사용한다.
    conn.commit()

    futures = []

    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        for batch_no, rows in read_parquet_batches(args.parquet_path, args.batch_size, args.limit):
            future = executor.submit(process_batch, batch_no, rows, args)
            futures.append(future)

            # 너무 많은 batch가 메모리에 쌓이지 않도록 일부 완료를 기다림
            if len(futures) >= args.workers * args.max_pending_batches:
                still_pending = []
                for f in futures:
                    if f.done():
                        done_batch_no, row_count = f.result()
                        total_processed += row_count
                        log(f"[IMPORT] batch={done_batch_no} 완료 rows={row_count}, total_processed={total_processed}")
                    else:
                        still_pending.append(f)
                futures = still_pending

        for f in as_completed(futures):
            done_batch_no, row_count = f.result()
            total_processed += row_count
            log(f"[IMPORT] batch={done_batch_no} 완료 rows={row_count}, total_processed={total_processed}")

    finished_at = datetime.now()
    log(f"[IMPORT DONE] total_processed={total_processed}, elapsed={finished_at - started_at}")


def fetch_scalar(cur, sql: str, params: Tuple = ()):
    cur.execute(sql, params)
    row = cur.fetchone()
    return row[0] if row else None


def print_distribution(cur, title: str, sql: str, params: Tuple = ()):
    print()
    print(f"## {title}")
    cur.execute(sql, params)
    rows = cur.fetchall()

    if not rows:
        print("- 결과 없음")
        return

    for row in rows:
        print(f"- {row[0]}: {row[1]}")


def run_validation_report(conn, source: str, score_model_version: str):
    print()
    print("=" * 80)
    print("MVP 검증 리포트")
    print("=" * 80)

    with conn.cursor() as cur:
        source_count = fetch_scalar(
            cur,
            """
            SELECT COUNT(*)
            FROM persona_source_record
            WHERE source = %s
            """,
            (source,),
        )

        profile_count = fetch_scalar(
            cur,
            """
            SELECT COUNT(*)
            FROM persona_profile p
            JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE s.source = %s
            """,
            (source,),
        )

        rule_score_count = fetch_scalar(
            cur,
            """
            SELECT COUNT(*)
            FROM persona_feature_score fs
            JOIN persona_profile p ON p.id = fs.persona_profile_id
            JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE s.source = %s
              AND fs.score_source = 'RULE_BASED'
              AND fs.score_model_version = %s
            """,
            (source, score_model_version),
        )

        duplicate_uuid_count = fetch_scalar(
            cur,
            """
            SELECT COUNT(*)
            FROM (
                SELECT source_id
                FROM persona_source_record
                WHERE source = %s
                GROUP BY source_id
                HAVING COUNT(*) > 1
            ) t
            """,
            (source,),
        )

        orphan_source_count = fetch_scalar(
            cur,
            """
            SELECT COUNT(*)
            FROM persona_source_record s
            LEFT JOIN persona_profile p ON p.source_record_id = s.id
            WHERE s.source = %s
              AND p.id IS NULL
            """,
            (source,),
        )

        profile_without_score_count = fetch_scalar(
            cur,
            """
            SELECT COUNT(*)
            FROM persona_profile p
            JOIN persona_source_record s ON s.id = p.source_record_id
            LEFT JOIN persona_feature_score fs
                ON fs.persona_profile_id = p.id
               AND fs.score_source = 'RULE_BASED'
               AND fs.score_model_version = %s
            WHERE s.source = %s
              AND fs.id IS NULL
            """,
            (score_model_version, source),
        )

        empty_search_text_count = fetch_scalar(
            cur,
            """
            SELECT COUNT(*)
            FROM persona_profile p
            JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE s.source = %s
              AND (p.search_text IS NULL OR LENGTH(TRIM(p.search_text)) = 0)
            """,
            (source,),
        )

        print()
        print("## Row Count")
        print(f"- persona_source_record: {source_count}")
        print(f"- persona_profile: {profile_count}")
        print(f"- persona_feature_score RULE_BASED {score_model_version}: {rule_score_count}")

        print()
        print("## Integrity")
        print(f"- duplicate_uuid_count: {duplicate_uuid_count}")
        print(f"- source_record_without_profile_count: {orphan_source_count}")
        print(f"- profile_without_rule_score_count: {profile_without_score_count}")
        print(f"- empty_search_text_count: {empty_search_text_count}")

        print_distribution(
            cur,
            "age_group 분포",
            """
            SELECT COALESCE(p.age_group, 'NULL') AS age_group, COUNT(*)
            FROM persona_profile p
            JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE s.source = %s
            GROUP BY p.age_group
            ORDER BY COUNT(*) DESC
            """,
            (source,),
        )

        print_distribution(
            cur,
            "gender 분포",
            """
            SELECT COALESCE(p.gender, 'NULL') AS gender, COUNT(*)
            FROM persona_profile p
            JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE s.source = %s
            GROUP BY p.gender
            ORDER BY COUNT(*) DESC
            """,
            (source,),
        )

        print_distribution(
            cur,
            "region 분포",
            """
            SELECT COALESCE(p.region, 'NULL') AS region, COUNT(*)
            FROM persona_profile p
            JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE s.source = %s
            GROUP BY p.region
            ORDER BY COUNT(*) DESC
            """,
            (source,),
        )

        print_distribution(
            cur,
            "price_sensitivity_score 분포",
            """
            SELECT
                CASE
                    WHEN fs.price_sensitivity_score < 40 THEN '0~39'
                    WHEN fs.price_sensitivity_score < 50 THEN '40~49'
                    WHEN fs.price_sensitivity_score < 60 THEN '50~59'
                    WHEN fs.price_sensitivity_score < 70 THEN '60~69'
                    WHEN fs.price_sensitivity_score < 80 THEN '70~79'
                    ELSE '80~100'
                END AS score_range,
                COUNT(*)
            FROM persona_feature_score fs
            JOIN persona_profile p ON p.id = fs.persona_profile_id
            JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE s.source = %s
              AND fs.score_source = 'RULE_BASED'
              AND fs.score_model_version = %s
            GROUP BY score_range
            ORDER BY score_range
            """,
            (source, score_model_version),
        )

    print()
    print("=" * 80)
    print("검증 리포트 종료")
    print("=" * 80)


def parse_args():
    parser = argparse.ArgumentParser(description="CustomerPreview persona parquet import script v3 - multithread")

    parser.add_argument("--parquet-path", required=True, help="parquet 파일 또는 parquet 디렉터리 경로")
    parser.add_argument("--reset-persona", action="store_true", help="기존 persona 관련 테이블 TRUNCATE 후 import")
    parser.add_argument("--kill-persona-locks", action="store_true", help="persona 관련 테이블 lock을 잡고 있는 다른 DB 세션 종료")
    parser.add_argument("--batch-size", type=int, default=5000, help="batch 처리 row 수")
    parser.add_argument("--limit", type=int, default=None, help="최대 import row 수")
    parser.add_argument("--workers", type=int, default=4, help="동시 insert worker 수")
    parser.add_argument("--max-pending-batches", type=int, default=4, help="worker 수 대비 최대 대기 batch 배수")

    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="postgres")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")

    parser.add_argument("--source", default="NEMOTRON_PERSONAS_KOREA")
    parser.add_argument("--score-model-version", default="rule_v1_2")

    return parser.parse_args()


def main():
    args = parse_args()

    log("[START] 스크립트 시작")
    log(f"[CONFIG] parquet_path={args.parquet_path}")
    log(f"[CONFIG] db={args.host}:{args.port}/{args.dbname}, user={args.user}")
    log(f"[CONFIG] reset_persona={args.reset_persona}, kill_persona_locks={args.kill_persona_locks}")
    log(f"[CONFIG] workers={args.workers}, batch_size={args.batch_size}, limit={args.limit}")
    log(f"[CONFIG] score_model_version={args.score_model_version}")

    conn = connect_db(args)

    try:
        if args.kill_persona_locks:
            log("[LOCK] persona 관련 lock 해제 시작")
            kill_persona_locks(conn)
            log("[LOCK] persona 관련 lock 해제 완료")

        log("[DDL] 테이블 생성 시작")
        create_tables(conn)
        log("[DDL] 테이블 생성 완료")

        if args.reset_persona:
            log("[RESET] persona 관련 테이블 초기화 시작")
            truncate_persona_tables(conn)
            log("[RESET] persona 관련 테이블 초기화 완료")

        import_personas_multithread(conn, args)

        run_validation_report(conn, args.source, args.score_model_version)

    except KeyboardInterrupt:
        conn.rollback()
        log("[INTERRUPT] 사용자에 의해 중단됨")
        raise

    except Exception as e:
        conn.rollback()
        log("[ERROR] import 실패")
        log(str(e))
        raise

    finally:
        conn.close()
        log("[END] DB 연결 종료")


if __name__ == "__main__":
    main()
