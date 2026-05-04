#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
CustomerPreview / 미리고객
DeepSeek 기반 페르소나 이해 프로필 생성 스크립트

파일명:
  deepseek_normalize_persona_understanding.py

역할:
  1. persona_profile + persona_source_record를 조회한다.
  2. 원본 컬럼을 DeepSeek에 넣어 "사람 이해 프로필" JSON을 생성한다.
  3. persona_llm_understanding_profile 테이블을 생성/저장한다.
  4. 이미 처리된 페르소나는 skip할 수 있다.
  5. 실패 응답은 JSONL로 저장한다.
  6. dry-run으로 DB 저장 없이 응답 품질을 먼저 확인할 수 있다.

실행 전:
  pip install psycopg2-binary requests

환경변수:
  export DEEPSEEK_API_KEY="sk-..."

샘플 20명 dry-run:
  python3 ./scripts/deepseek_normalize_persona_understanding.py \
    --dbname precustomer \
    --limit 20 \
    --llm-batch-size 5 \
    --dry-run

100명 저장:
  python3 ./scripts/deepseek_normalize_persona_understanding.py \
    --dbname precustomer \
    --limit 100 \
    --llm-batch-size 5

이미 저장된 건 skip하며 3,000명, 4개 병렬:
  python3 ./scripts/deepseek_normalize_persona_understanding.py \
    --dbname precustomer \
    --limit 3000 \
    --llm-batch-size 10 \
    --max-workers 4 \
    --skip-existing

특정 id 이후부터:
  python3 ./scripts/deepseek_normalize_persona_understanding.py \
    --dbname precustomer \
    --start-id 100000 \
    --limit 1000 \
    --llm-batch-size 10 \
    --skip-existing

주의:
  - DeepSeek 결과는 정답이 아니라 LLM_NORMALIZED pseudo profile이다.
  - 소득/자산/건강상태는 원본 단서 기반 추정값이다.
  - 정치/종교/질병 진단/신용등급/실제 자산금액 같은 민감하거나 근거 없는 정보는 생성하지 않는다.
  - 원본 데이터는 수정하지 않는다.
"""

import argparse
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

import psycopg2
import requests
from psycopg2.extras import RealDictCursor, Json, execute_values


PROMPT_VERSION = "persona_understanding_v1"
NORMALIZATION_VERSION = "understanding_schema_v1"

DEFAULT_MODEL_NAME = "deepseek"
DEFAULT_MODEL_VERSION = "deepseek-v4-flash"
DEFAULT_BASE_URL = "https://api.deepseek.com"


# -----------------------------
# 공통 유틸
# -----------------------------

def log(message: str) -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {message}", flush=True)


def safe_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def compact_text(value: Any, max_len: int = 1200) -> str:
    text = safe_text(value)
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) > max_len:
        return text[:max_len].rstrip() + "..."
    return text


def clamp_number(value: Any, min_v: float, max_v: float, default: float) -> float:
    try:
        n = float(value)
        if n < min_v:
            return min_v
        if n > max_v:
            return max_v
        return n
    except Exception:
        return default


def as_list(value: Any) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def as_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    return {}


def get_nested(data: Dict[str, Any], path: str, default: Any = None) -> Any:
    cur: Any = data
    for key in path.split("."):
        if not isinstance(cur, dict):
            return default
        if key not in cur:
            return default
        cur = cur[key]
    return cur


def json_dumps(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def now_string() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def ensure_dir(path: str) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)


# -----------------------------
# enum 정의
# -----------------------------

EMPLOYMENT_STATUS = {
    "EMPLOYED", "UNEMPLOYED", "JOB_SEEKING", "RETIRED_OR_INACTIVE",
    "STUDENT", "HOMEMAKER", "UNKNOWN"
}

OCCUPATION_GROUP = {
    "OFFICE_ADMIN", "SALES_MARKETING", "ECOMMERCE_SELLER", "IT_DIGITAL",
    "EDUCATION", "HEALTHCARE_CARE", "FOOD_SERVICE", "LOCAL_SERVICE",
    "TRANSPORT_LOGISTICS", "MANUFACTURING_TECH", "CONSTRUCTION_FIELD",
    "SMALL_BUSINESS_OWNER", "PROFESSIONAL_SERVICE", "ART_CULTURE",
    "AGRI_FISHERY", "STUDENT", "RETIRED_INACTIVE", "UNEMPLOYED",
    "HOMEMAKER", "OTHER", "UNKNOWN"
}

OCCUPATION_DOMAIN = {
    "OFFICE_WORK", "SALES_MARKETING", "ECOMMERCE", "DIGITAL_TECH",
    "EDUCATION", "HEALTHCARE_CARE", "FOOD_LOCAL_SERVICE", "LOCAL_SERVICE",
    "TRANSPORT_LOGISTICS", "FIELD_TECH_MANUFACTURING", "CONSTRUCTION_FIELD",
    "SMALL_BUSINESS", "PROFESSIONAL_SERVICE", "ART_CULTURE",
    "AGRI_FISHERY", "STUDENT", "SENIOR_INACTIVE", "UNEMPLOYED",
    "HOMEMAKER", "OTHER", "UNKNOWN"
}

LEVEL_ENUM = {
    "VERY_LOW", "LOW", "LOWER_MIDDLE", "MEDIUM", "MIDDLE",
    "UPPER_MIDDLE", "HIGH", "VERY_HIGH", "UNKNOWN"
}

SIMPLE_LEVEL = {"LOW", "MEDIUM", "HIGH", "UNKNOWN"}

PET_OWNERSHIP_STATUS = {"HAS_PET", "NO_PET", "PET_INTEREST", "UNKNOWN"}
PET_TYPES = {"DOG", "CAT", "SMALL_ANIMAL", "BIRD", "FISH", "OTHER", "UNKNOWN"}

HEALTH_STATUS = {
    "HEALTHY_ACTIVE", "AVERAGE", "LOW_STAMINA_OR_FATIGUE",
    "MOBILITY_CONCERN", "CHRONIC_CONCERN_MENTIONED", "UNKNOWN"
}

SPORTS_PARTICIPATION_LEVEL = {
    "NONE_OR_LOW", "WATCHING_ONLY", "LIGHT_ACTIVITY",
    "REGULAR_EXERCISE", "ACTIVE_SPORTS", "UNKNOWN"
}

CREATIVE_ACTIVITY_LEVEL = {
    "APPRECIATION_ONLY", "LIGHT_CREATION", "ACTIVE_CREATION", "UNKNOWN"
}

PURCHASE_DECISION_STYLE = {
    "PRICE_FIRST", "TRUST_FIRST", "QUALITY_FIRST", "CONVENIENCE_FIRST",
    "FAMILY_FIRST", "HEALTH_SAFETY_FIRST", "REVIEW_DEPENDENT",
    "NOVELTY_SEEKING", "CONSERVATIVE", "IMPULSE", "UNKNOWN"
}

PRICE_ATTITUDE = {
    "LOW_PRICE_FIRST", "VALUE_FOR_MONEY", "WILL_PAY_IF_TRUSTED",
    "PREMIUM_ACCEPTING", "UNKNOWN"
}

CUSTOMER_ROLES = {
    "SENIOR_USER", "CAREGIVER_FAMILY", "PARENT", "WORKING_PARENT",
    "OFFICE_WORKER", "LOCAL_RESIDENT", "SMALL_BUSINESS_OWNER",
    "STUDENT", "ONLINE_SELLER", "FOOD_DECISION_MAKER",
    "HEALTH_SAFETY_CONCERNED_USER", "PRICE_SENSITIVE_USER",
    "QUALITY_SENSITIVE_USER", "CONVENIENCE_SEEKER", "EARLY_ADOPTER",
    "SKEPTICAL_REVIEW_READER", "PET_OWNER", "CULTURE_LOVER",
    "SPORTS_ACTIVE_USER", "UNKNOWN"
}

CHANNELS = {
    "APP_MOBILE", "WEB_ONLINE", "ECOMMERCE", "OFFLINE_STORE",
    "LOCAL_COMMUNITY", "PUBLIC_CENTER", "FAMILY_RECOMMENDATION",
    "FRIEND_RECOMMENDATION", "PHONE_CALL", "IN_PERSON_EXPLANATION",
    "UNKNOWN"
}

DOMAINS = {
    "SENIOR_HEALTH", "LOCAL_LIFE_SERVICE", "CHILD_SAFETY",
    "FOOD_LOCAL_HYGIENE", "ECOMMERCE", "APP_SAAS", "EDUCATION",
    "HOUSEHOLD_MANAGEMENT", "TRAVEL_LEISURE", "FINANCE_SAVING",
    "HEALTHCARE_MEDICAL", "BEAUTY_FASHION", "PET_CARE",
    "ART_CULTURE_HOBBY", "SPORTS_FITNESS", "B2B_BUSINESS", "UNKNOWN"
}

LIFE_CONTEXTS = {
    "SENIOR_LIFE", "PARENTING", "DUAL_INCOME_FAMILY", "BUSY_WORKER",
    "LOCAL_LIVING", "OFFLINE_STORE_USER", "FOOD_HYGIENE_CONCERN",
    "HEALTH_SAFETY_CONCERN", "DIGITAL_COMFORT", "ECOMMERCE_EXPERIENCE",
    "HOUSEHOLD_CARE", "PRICE_CONSCIOUS_LIVING", "STUDENT_LIFE",
    "COMMUNITY_ACTIVITY", "SELF_IMPROVEMENT", "LEISURE_OUTDOOR",
    "LEISURE_HOME", "PET_CARE_LIFE", "ART_CULTURE_LIFE",
    "SPORTS_FITNESS_LIFE", "UNKNOWN"
}

ATTITUDE_TAGS = {
    "PRACTICAL", "CAUTIOUS", "TRUST_SEEKING", "PRICE_SENSITIVE",
    "QUALITY_ORIENTED", "FAMILY_ORIENTED", "LOCAL_ORIENTED",
    "HEALTH_SAFETY_ORIENTED", "DIGITAL_FRIENDLY", "DIGITAL_HESITANT",
    "NOVELTY_OPEN", "CONSERVATIVE", "TIME_SAVING_ORIENTED",
    "REVIEW_DEPENDENT", "AESTHETIC_ORIENTED", "FOOD_ORIENTED",
    "PET_ORIENTED", "SPORTS_ORIENTED", "UNKNOWN"
}

PERSONALITY_TAGS = {
    "PRACTICAL", "CAUTIOUS", "IMPULSIVE", "ANALYTICAL", "EMOTIONAL",
    "CONSERVATIVE", "EXPERIMENTAL", "SOCIAL", "INDEPENDENT",
    "DETAIL_ORIENTED", "LOW_PATIENCE", "HIGH_PATIENCE", "UNKNOWN"
}

NEGATIVE_CONTEXTS = {
    "NOT_TARGET_FOR_ECOMMERCE", "NOT_TARGET_FOR_SENIOR_SERVICE",
    "NOT_TARGET_FOR_PARENTING", "NOT_TARGET_FOR_OFFLINE_LOCAL_SERVICE",
    "TOO_DIGITAL_ONLY", "TOO_OFFLINE_ONLY", "LOW_PURCHASE_RELEVANCE",
    "UNCLEAR_CUSTOMER_ROLE", "UNKNOWN"
}

ALL_ENUMS = {
    "employmentStatus": EMPLOYMENT_STATUS,
    "occupationGroup": OCCUPATION_GROUP,
    "occupationDomain": OCCUPATION_DOMAIN,
    "petOwnershipStatus": PET_OWNERSHIP_STATUS,
    "healthStatusInferred": HEALTH_STATUS,
    "sportsParticipationLevel": SPORTS_PARTICIPATION_LEVEL,
    "creativeActivityLevel": CREATIVE_ACTIVITY_LEVEL,
    "purchaseDecisionStyle": PURCHASE_DECISION_STYLE,
    "priceAttitude": PRICE_ATTITUDE,
}


# -----------------------------
# DB
# -----------------------------

def connect_db(args):
    return psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.dbname,
        user=args.user,
        password=args.password,
        connect_timeout=10,
    )


def ensure_table(conn) -> None:
    ddl = """
    CREATE TABLE IF NOT EXISTS persona_llm_understanding_profile (
        id BIGSERIAL PRIMARY KEY,

        persona_profile_id BIGINT NOT NULL,
        source_record_id BIGINT,
        source_id VARCHAR(100),

        model_name VARCHAR(100) NOT NULL,
        model_version VARCHAR(100) NOT NULL,
        prompt_version VARCHAR(100) NOT NULL,
        normalization_version VARCHAR(100) NOT NULL,

        profile_summary_ko TEXT,
        one_line_persona_ko TEXT,
        representativeness_segment VARCHAR(255),
        core_identity_tags JSONB NOT NULL DEFAULT '[]'::jsonb,

        employment_status VARCHAR(50),
        occupation_group VARCHAR(100),
        occupation_domain VARCHAR(100),
        occupation_seniority VARCHAR(50),
        work_style VARCHAR(50),
        previous_occupation_group VARCHAR(100),

        household_stage VARCHAR(100),
        family_decision_role VARCHAR(100),
        household_responsibility_level VARCHAR(50),
        has_parenting_context BOOLEAN,
        has_senior_context BOOLEAN,
        has_caregiving_context BOOLEAN,

        socioeconomic_tier VARCHAR(50),
        estimated_income_level VARCHAR(50),
        estimated_asset_level VARCHAR(50),
        disposable_income_level VARCHAR(50),
        financial_pressure_level VARCHAR(50),
        housing_stability_level VARCHAR(50),
        income_source_type VARCHAR(100),
        spending_power_level VARCHAR(50),
        subscription_affordability VARCHAR(50),
        one_time_purchase_affordability VARCHAR(50),
        premium_acceptance_level VARCHAR(50),
        price_elasticity_level VARCHAR(50),
        financial_decision_role VARCHAR(100),

        pet_ownership_status VARCHAR(50),
        pet_types JSONB NOT NULL DEFAULT '[]'::jsonb,
        pet_care_involvement_level VARCHAR(50),
        pet_spending_tendency VARCHAR(50),
        pet_related_needs JSONB NOT NULL DEFAULT '[]'::jsonb,

        health_status_inferred VARCHAR(100),
        physical_activity_level VARCHAR(50),
        mobility_level VARCHAR(50),
        health_concern_level VARCHAR(50),
        safety_concern_level VARCHAR(50),
        hygiene_sensitivity_level VARCHAR(50),
        medical_care_relevance VARCHAR(50),
        diet_health_orientation VARCHAR(50),
        health_limitations JSONB NOT NULL DEFAULT '[]'::jsonb,

        sports_interest_level VARCHAR(50),
        sports_participation_level VARCHAR(100),
        sports_types JSONB NOT NULL DEFAULT '[]'::jsonb,
        exercise_motivation JSONB NOT NULL DEFAULT '[]'::jsonb,
        outdoor_activity_level VARCHAR(50),
        sports_sociality_level VARCHAR(50),

        arts_interest_level VARCHAR(50),
        culture_consumption_level VARCHAR(50),
        preferred_arts_types JSONB NOT NULL DEFAULT '[]'::jsonb,
        creative_activity_level VARCHAR(50),
        aesthetic_sensitivity_level VARCHAR(50),
        culture_spending_tendency VARCHAR(50),

        food_preference_profile TEXT,
        preferred_cuisines JSONB NOT NULL DEFAULT '[]'::jsonb,
        meal_style JSONB NOT NULL DEFAULT '[]'::jsonb,
        delivery_usage_level VARCHAR(50),
        dining_out_level VARCHAR(50),
        home_cooking_level VARCHAR(50),
        food_adventurousness_level VARCHAR(50),
        healthy_eating_level VARCHAR(50),
        food_price_sensitivity_level VARCHAR(50),
        food_quality_sensitivity_level VARCHAR(50),
        food_hygiene_sensitivity_level VARCHAR(50),
        food_social_context JSONB NOT NULL DEFAULT '[]'::jsonb,

        travel_interest_level VARCHAR(50),
        travel_style JSONB NOT NULL DEFAULT '[]'::jsonb,
        travel_frequency_level VARCHAR(50),
        planning_style VARCHAR(50),
        mobility_preference VARCHAR(100),
        local_exploration_level VARCHAR(50),
        travel_spending_tendency VARCHAR(50),

        hobby_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
        hobby_activity_level VARCHAR(50),
        social_hobby_level VARCHAR(50),
        homebody_level VARCHAR(50),
        outdoor_orientation_level VARCHAR(50),
        hobby_spending_tendency VARCHAR(50),

        life_contexts JSONB NOT NULL DEFAULT '[]'::jsonb,
        primary_customer_role VARCHAR(100),
        customer_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
        channel_affinities JSONB NOT NULL DEFAULT '[]'::jsonb,
        primary_channel VARCHAR(100),
        domain_affinities JSONB NOT NULL DEFAULT '[]'::jsonb,
        weak_domains JSONB NOT NULL DEFAULT '[]'::jsonb,
        negative_contexts JSONB NOT NULL DEFAULT '[]'::jsonb,

        attitude_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
        personality_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
        decision_temperament VARCHAR(100),
        risk_tolerance_level VARCHAR(50),
        change_resistance_level VARCHAR(50),
        emotional_reactivity_level VARCHAR(50),

        purchase_decision_style VARCHAR(100),
        main_purchase_drivers JSONB NOT NULL DEFAULT '[]'::jsonb,
        main_purchase_barriers JSONB NOT NULL DEFAULT '[]'::jsonb,
        price_attitude VARCHAR(100),
        trust_requirement_level VARCHAR(50),
        quality_requirement_level VARCHAR(50),
        convenience_need_level VARCHAR(50),

        brand_sensitivity_level VARCHAR(50),
        local_reputation_sensitivity_level VARCHAR(50),
        expert_authority_sensitivity_level VARCHAR(50),
        certification_sensitivity_level VARCHAR(50),
        social_proof_sensitivity_level VARCHAR(50),

        privacy_sensitivity_level VARCHAR(50),
        data_sharing_resistance_level VARCHAR(50),
        location_sharing_resistance_level VARCHAR(50),
        identity_verification_acceptance_level VARCHAR(50),

        support_channel_preference VARCHAR(100),
        human_support_need_level VARCHAR(50),
        self_service_readiness VARCHAR(50),
        complaint_likelihood_level VARCHAR(50),
        refund_sensitivity_level VARCHAR(50),

        ad_receptiveness_level VARCHAR(50),
        preferred_message_style VARCHAR(100),
        persuasion_triggers JSONB NOT NULL DEFAULT '[]'::jsonb,
        message_resistance_points JSONB NOT NULL DEFAULT '[]'::jsonb,
        tone_preference VARCHAR(100),

        daily_routine_pattern VARCHAR(100),
        available_time_slots JSONB NOT NULL DEFAULT '[]'::jsonb,
        weekend_activity_level VARCHAR(50),
        weekday_pressure_level VARCHAR(50),
        schedule_flexibility_level VARCHAR(50),

        accessibility_need_level VARCHAR(50),
        mobility_constraint_level VARCHAR(50),
        transport_dependency VARCHAR(100),
        in_person_service_difficulty VARCHAR(50),

        social_influence_level VARCHAR(50),
        word_of_mouth_influence_level VARCHAR(50),
        family_influence_level VARCHAR(50),
        peer_influence_level VARCHAR(50),
        community_influence_level VARCHAR(50),

        social_value_sensitivity_level VARCHAR(50),
        eco_friendly_sensitivity_level VARCHAR(50),
        local_business_support_level VARCHAR(50),
        public_good_orientation_level VARCHAR(50),

        service_comprehension_level VARCHAR(50),
        onboarding_difficulty_level VARCHAR(50),
        instruction_preference VARCHAR(100),
        learning_willingness_level VARCHAR(50),

        likely_positive_points JSONB NOT NULL DEFAULT '[]'::jsonb,
        likely_concerns JSONB NOT NULL DEFAULT '[]'::jsonb,
        message_hooks JSONB NOT NULL DEFAULT '[]'::jsonb,
        avoid_messages JSONB NOT NULL DEFAULT '[]'::jsonb,

        trait_scores_json JSONB NOT NULL DEFAULT '{}'::jsonb,
        trait_score_reason_json JSONB NOT NULL DEFAULT '{}'::jsonb,
        trait_score_confidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,

        data_richness_score NUMERIC(5, 2),
        persona_consistency_score NUMERIC(5, 2),
        selection_usefulness_score NUMERIC(5, 2),
        persona_risk_level VARCHAR(50),

        overall_confidence NUMERIC(5, 4),
        evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
        source_columns_json JSONB NOT NULL DEFAULT '{}'::jsonb,
        low_confidence_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
        unknown_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
        raw_response JSONB NOT NULL DEFAULT '{}'::jsonb,

        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        CONSTRAINT fk_persona_llm_understanding_profile_persona_profile
            FOREIGN KEY (persona_profile_id)
            REFERENCES persona_profile(id)
            ON DELETE CASCADE,

        CONSTRAINT uk_persona_llm_understanding_profile_version
            UNIQUE (persona_profile_id, model_version, prompt_version, normalization_version)
    );

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_persona_profile_id
        ON persona_llm_understanding_profile(persona_profile_id);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_source_id
        ON persona_llm_understanding_profile(source_id);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_model_prompt
        ON persona_llm_understanding_profile(model_version, prompt_version, normalization_version);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_employment
        ON persona_llm_understanding_profile(employment_status);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_occupation_group
        ON persona_llm_understanding_profile(occupation_group);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_occupation_domain
        ON persona_llm_understanding_profile(occupation_domain);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_primary_customer_role
        ON persona_llm_understanding_profile(primary_customer_role);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_primary_channel
        ON persona_llm_understanding_profile(primary_channel);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_spending_power
        ON persona_llm_understanding_profile(spending_power_level);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_selection_score
        ON persona_llm_understanding_profile(selection_usefulness_score);

    CREATE INDEX IF NOT EXISTS idx_persona_llm_understanding_profile_confidence
        ON persona_llm_understanding_profile(overall_confidence);

    CREATE INDEX IF NOT EXISTS gin_persona_llm_life_contexts
        ON persona_llm_understanding_profile
        USING GIN (life_contexts);

    CREATE INDEX IF NOT EXISTS gin_persona_llm_customer_roles
        ON persona_llm_understanding_profile
        USING GIN (customer_roles);

    CREATE INDEX IF NOT EXISTS gin_persona_llm_domain_affinities
        ON persona_llm_understanding_profile
        USING GIN (domain_affinities);

    CREATE INDEX IF NOT EXISTS gin_persona_llm_channel_affinities
        ON persona_llm_understanding_profile
        USING GIN (channel_affinities);

    CREATE INDEX IF NOT EXISTS gin_persona_llm_attitude_tags
        ON persona_llm_understanding_profile
        USING GIN (attitude_tags);

    COMMENT ON TABLE persona_llm_understanding_profile IS 'LLM이 원본 페르소나를 분석해 생성한 사람 이해 프로필';
    COMMENT ON COLUMN persona_llm_understanding_profile.persona_profile_id IS '페르소나 프로필 식별자';
    COMMENT ON COLUMN persona_llm_understanding_profile.source_record_id IS '원본 parquet 저장 레코드 식별자';
    COMMENT ON COLUMN persona_llm_understanding_profile.source_id IS '원본 데이터 UUID';
    COMMENT ON COLUMN persona_llm_understanding_profile.model_name IS '정규화에 사용한 모델 제공자명';
    COMMENT ON COLUMN persona_llm_understanding_profile.model_version IS '정규화에 사용한 모델 버전';
    COMMENT ON COLUMN persona_llm_understanding_profile.prompt_version IS '정규화 프롬프트 버전';
    COMMENT ON COLUMN persona_llm_understanding_profile.normalization_version IS '정규화 결과 스키마 버전';
    COMMENT ON COLUMN persona_llm_understanding_profile.profile_summary_ko IS '원본 근거 기반 사람 요약';
    COMMENT ON COLUMN persona_llm_understanding_profile.one_line_persona_ko IS '한 줄 페르소나 표현';
    COMMENT ON COLUMN persona_llm_understanding_profile.representativeness_segment IS '대표 세그먼트';
    COMMENT ON COLUMN persona_llm_understanding_profile.employment_status IS '고용 상태 추정값';
    COMMENT ON COLUMN persona_llm_understanding_profile.occupation_group IS '직업군 정규화 값';
    COMMENT ON COLUMN persona_llm_understanding_profile.occupation_domain IS '직업 도메인 정규화 값';
    COMMENT ON COLUMN persona_llm_understanding_profile.socioeconomic_tier IS '원본 단서 기반 사회경제 수준 추정';
    COMMENT ON COLUMN persona_llm_understanding_profile.estimated_income_level IS '원본 단서 기반 소득 수준 추정';
    COMMENT ON COLUMN persona_llm_understanding_profile.estimated_asset_level IS '원본 단서 기반 자산 수준 추정';
    COMMENT ON COLUMN persona_llm_understanding_profile.spending_power_level IS '실질 구매력 추정';
    COMMENT ON COLUMN persona_llm_understanding_profile.pet_ownership_status IS '반려동물 보유 여부 추정';
    COMMENT ON COLUMN persona_llm_understanding_profile.health_status_inferred IS '원본 단서 기반 건강상태 추정. 의학적 진단 아님';
    COMMENT ON COLUMN persona_llm_understanding_profile.life_contexts IS '생활 맥락 목록 JSON';
    COMMENT ON COLUMN persona_llm_understanding_profile.customer_roles IS '고객 역할 목록 JSON';
    COMMENT ON COLUMN persona_llm_understanding_profile.domain_affinities IS '서비스 도메인 적합도 목록 JSON';
    COMMENT ON COLUMN persona_llm_understanding_profile.trait_scores_json IS '10개 성향 점수 재분석 결과 JSON';
    COMMENT ON COLUMN persona_llm_understanding_profile.evidence_json IS '각 판단의 근거 JSON';
    COMMENT ON COLUMN persona_llm_understanding_profile.raw_response IS 'LLM 원본 응답 JSON';
    COMMENT ON COLUMN persona_llm_understanding_profile.overall_confidence IS '정규화 결과 전체 신뢰도';
    """

    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()


def reset_table(conn, model_version: str, prompt_version: str, normalization_version: str) -> None:
    log("[RESET] 기존 LLM 이해 프로필 삭제 시작")
    with conn.cursor() as cur:
        cur.execute(
            """
            DELETE FROM persona_llm_understanding_profile
            WHERE model_version = %s
              AND prompt_version = %s
              AND normalization_version = %s
            """,
            (model_version, prompt_version, normalization_version),
        )
    conn.commit()
    log("[RESET] 기존 LLM 이해 프로필 삭제 완료")


def fetch_personas(
    conn,
    args,
    last_id: int,
    limit_remaining: Optional[int],
) -> List[Dict[str, Any]]:
    batch_size = args.fetch_batch_size
    if limit_remaining is not None:
        batch_size = min(batch_size, limit_remaining)
    if batch_size <= 0:
        return []

    skip_sql = ""
    params: List[Any] = []

    params.extend([last_id])

    if args.skip_existing:
        skip_sql = """
          AND NOT EXISTS (
              SELECT 1
              FROM persona_llm_understanding_profile lup
              WHERE lup.persona_profile_id = p.id
                AND lup.model_version = %s
                AND lup.prompt_version = %s
                AND lup.normalization_version = %s
          )
        """
        params.extend([args.model_version, args.prompt_version, args.normalization_version])

    params.append(batch_size)

    sql = f"""
    SELECT
        p.id AS persona_profile_id,
        p.source_record_id,
        p.source_id AS profile_source_id,
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

        s.source_id AS source_id,
        s.professional_persona,
        s.sports_persona,
        s.arts_persona,
        s.travel_persona,
        s.culinary_persona,
        s.family_persona,
        s.persona,
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
    WHERE p.id > %s
      AND p.active = TRUE
      {skip_sql}
    ORDER BY p.id
    LIMIT %s
    """

    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, params)
        return [dict(row) for row in cur.fetchall()]


# -----------------------------
# Prompt 생성
# -----------------------------

def enum_lines(name: str, values: Iterable[str]) -> str:
    return f"- {name}: {', '.join(sorted(values))}"


def build_system_prompt() -> str:
    return f"""
너는 한국형 페르소나 데이터를 정규화하는 분류기다.

목표:
- 주어진 원본 페르소나 데이터를 읽고, 지정된 JSON 스키마에 맞춰 "사람 이해 프로필"을 생성한다.
- 원본에서 멀어지지 않는다.
- 근거가 부족하면 UNKNOWN 또는 빈 배열로 둔다.
- 절대 enum 목록에 없는 값을 만들지 않는다.
- 출력은 반드시 JSON 객체 하나만 반환한다. 마크다운 코드블록 금지.

중요 원칙:
1. 소득, 자산, 건강상태는 실제값이 아니라 원본 단서 기반 추정값이다.
2. 의학적 진단, 질병 단정, 신용등급, 실제 자산 금액, 정치/종교/민감 정보는 만들지 않는다.
3. 건강상태는 생활 단서 기반 추정일 뿐이며, 원본에 명확한 단서가 없으면 UNKNOWN이다.
4. 반려동물은 원본에 명확한 단서가 있을 때만 HAS_PET 또는 PET_INTEREST로 판단한다.
5. 산책/공원/운동만으로 반려견 보유로 판단하지 않는다.
6. 가족이라는 단어만으로 PARENTING을 붙이지 않는다.
7. 식사/음식이라는 단어만으로 HOUSEHOLD_CARE를 붙이지 않는다.
8. 외식/식당 방문만으로 OFFLINE_STORE_USER를 붙이지 않는다.
9. 온라인이라는 단어만으로 ECOMMERCE_EXPERIENCE를 붙이지 않는다.
10. 학생을 가르치는 사람은 STUDENT_LIFE가 아니라 EDUCATION 계열이다.
11. 온라인 쇼핑 판매원은 SALES_MARKETING이 아니라 ECOMMERCE_SELLER로 분류한다.
12. lifeContexts, customerRoles, domainAffinities는 너무 많이 붙이지 않는다. 보통 1~4개, 명확할 때만 5개 이상 허용한다.
13. 각 판단의 근거는 reasoning.evidenceJson 또는 reasoning.sourceColumnsJson에 간단히 남긴다.
14. traitScores의 모든 점수는 0~100 정수다.
15. confidence는 0.0~1.0 숫자다.

고정 enum:
{enum_lines("employmentStatus", EMPLOYMENT_STATUS)}
{enum_lines("occupationGroup", OCCUPATION_GROUP)}
{enum_lines("occupationDomain", OCCUPATION_DOMAIN)}
{enum_lines("petOwnershipStatus", PET_OWNERSHIP_STATUS)}
{enum_lines("petTypes", PET_TYPES)}
{enum_lines("healthStatusInferred", HEALTH_STATUS)}
{enum_lines("sportsParticipationLevel", SPORTS_PARTICIPATION_LEVEL)}
{enum_lines("creativeActivityLevel", CREATIVE_ACTIVITY_LEVEL)}
{enum_lines("purchaseDecisionStyle", PURCHASE_DECISION_STYLE)}
{enum_lines("priceAttitude", PRICE_ATTITUDE)}
{enum_lines("customerRoles", CUSTOMER_ROLES)}
{enum_lines("channels", CHANNELS)}
{enum_lines("domains", DOMAINS)}
{enum_lines("lifeContexts", LIFE_CONTEXTS)}
{enum_lines("attitudeTags", ATTITUDE_TAGS)}
{enum_lines("personalityTags", PERSONALITY_TAGS)}
{enum_lines("negativeContexts", NEGATIVE_CONTEXTS)}

레벨 enum:
- 일반 수준값: VERY_LOW, LOW, MEDIUM, HIGH, VERY_HIGH, UNKNOWN
- 경제/계층 수준값: VERY_LOW, LOW, LOWER_MIDDLE, MIDDLE, UPPER_MIDDLE, HIGH, VERY_HIGH, UNKNOWN
- 판단 리스크: LOW, MEDIUM, HIGH, UNKNOWN

반드시 다음 최상위 구조로 출력한다:
{{
  "results": [
    {{
      "personaProfileId": 123,
      "profileSummaryKo": "",
      "oneLinePersonaKo": "",
      "representativenessSegment": "",
      "coreIdentityTags": [],

      "work": {{}},
      "household": {{}},
      "economy": {{}},
      "pet": {{}},
      "health": {{}},
      "sports": {{}},
      "artsCulture": {{}},
      "food": {{}},
      "travelHobby": {{}},
      "customerUnderstanding": {{}},
      "purchaseAndMessage": {{}},
      "serviceUsage": {{}},
      "traitScores": {{}},
      "quality": {{}},
      "reasoning": {{}}
    }}
  ]
}}
""".strip()


def build_user_prompt(personas: List[Dict[str, Any]]) -> str:
    payload = {
        "task": "아래 personas 배열의 각 페르소나에 대해 사람 이해 프로필을 생성해라.",
        "inputNotes": {
            "language": "Korean",
            "source": "Nemotron-Personas-Korea",
            "useOriginalColumnsOnly": True,
            "noHallucination": True,
            "outputCountMustEqualInputCount": True,
        },
        "personas": [persona_to_llm_input(row) for row in personas],
    }

    return json.dumps(payload, ensure_ascii=False, indent=2)


def persona_to_llm_input(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "personaProfileId": int(row["persona_profile_id"]),
        "sourceRecordId": int(row["source_record_id"]) if row.get("source_record_id") is not None else None,
        "sourceId": safe_text(row.get("source_id") or row.get("profile_source_id")),
        "basic": {
            "sex": safe_text(row.get("sex") or row.get("gender")),
            "age": row.get("source_age") if row.get("source_age") is not None else row.get("age"),
            "ageGroup": safe_text(row.get("age_group")),
            "maritalStatus": safe_text(row.get("marital_status")),
            "militaryStatus": safe_text(row.get("military_status")),
            "familyType": safe_text(row.get("source_family_type") or row.get("family_type")),
            "housingType": safe_text(row.get("source_housing_type") or row.get("housing_type")),
            "educationLevel": safe_text(row.get("source_education_level") or row.get("education_level")),
            "bachelorsField": safe_text(row.get("bachelors_field")),
            "occupation": safe_text(row.get("source_occupation") or row.get("occupation")),
            "district": safe_text(row.get("source_district") or row.get("district")),
            "province": safe_text(row.get("source_province") or row.get("province")),
            "country": safe_text(row.get("country")),
        },
        "originalSections": {
            "persona": compact_text(row.get("persona"), 900),
            "personaSummary": compact_text(row.get("persona_summary"), 600),
            "professionalPersona": compact_text(row.get("professional_persona"), 900),
            "familyPersona": compact_text(row.get("family_persona"), 900),
            "culturalBackground": compact_text(row.get("cultural_background"), 700),
            "skillsAndExpertise": compact_text(row.get("skills_and_expertise"), 700),
            "skillsAndExpertiseList": row.get("skills_and_expertise_list"),
            "hobbiesAndInterests": compact_text(row.get("hobbies_and_interests"), 700),
            "hobbiesAndInterestsList": row.get("hobbies_and_interests_list"),
            "careerGoalsAndAmbitions": compact_text(row.get("career_goals_and_ambitions"), 700),
            "sportsPersona": compact_text(row.get("sports_persona"), 700),
            "artsPersona": compact_text(row.get("arts_persona"), 700),
            "travelPersona": compact_text(row.get("travel_persona"), 700),
            "culinaryPersona": compact_text(row.get("culinary_persona"), 900),
        },
    }


# -----------------------------
# DeepSeek 호출
# -----------------------------

def call_deepseek(args, personas: List[Dict[str, Any]]) -> Dict[str, Any]:
    api_key = args.api_key or os.environ.get("DEEPSEEK_API_KEY")
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY 환경변수 또는 --api-key가 필요합니다.")

    base_url = args.base_url.rstrip("/")
    url = f"{base_url}/chat/completions"

    messages = [
        {"role": "system", "content": build_system_prompt()},
        {"role": "user", "content": build_user_prompt(personas)},
    ]

    body = {
        "model": args.model_version,
        "messages": messages,
        "temperature": args.temperature,
        "max_tokens": args.max_tokens,
        "response_format": {"type": "json_object"},
        "thinking": {"type": args.thinking_mode},
    }

    if args.thinking_mode == "enabled":
        body["reasoning_effort"] = args.reasoning_effort

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    last_error = None

    for attempt in range(1, args.max_retries + 1):
        try:
            response = requests.post(url, headers=headers, json=body, timeout=args.timeout)
            if response.status_code >= 400:
                last_error = RuntimeError(f"HTTP {response.status_code}: {response.text[:1000]}")
                if response.status_code in {429, 500, 502, 503, 504}:
                    time.sleep(args.retry_sleep * attempt)
                    continue
                raise last_error

            data = response.json()
            content = data["choices"][0]["message"]["content"]
            parsed = parse_json_content(content)
            parsed["_api_usage"] = data.get("usage", {})
            return parsed
        except Exception as e:
            last_error = e
            if attempt < args.max_retries:
                time.sleep(args.retry_sleep * attempt)
            else:
                break

    raise RuntimeError(f"DeepSeek 호출 실패: {last_error}")


def parse_json_content(content: str) -> Dict[str, Any]:
    content = safe_text(content)

    # markdown fence 방어
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?", "", content).strip()
        content = re.sub(r"```$", "", content).strip()

    try:
        return json.loads(content)
    except json.JSONDecodeError:
        # JSON object만 추출 시도
        start = content.find("{")
        end = content.rfind("}")
        if start >= 0 and end > start:
            return json.loads(content[start:end + 1])
        raise


# -----------------------------
# 검증/정규화
# -----------------------------

def normalize_enum(value: Any, allowed: set, default: str = "UNKNOWN") -> str:
    value = safe_text(value)
    if value in allowed:
        return value
    return default


def normalize_level(value: Any, default: str = "UNKNOWN") -> str:
    value = safe_text(value)
    if value in LEVEL_ENUM:
        return value
    if value == "MIDDLE":
        return "MIDDLE"
    return default


def normalize_simple_level(value: Any, default: str = "UNKNOWN") -> str:
    value = safe_text(value)
    if value in SIMPLE_LEVEL:
        return value
    if value in {"VERY_LOW", "LOWER_MIDDLE"}:
        return "LOW"
    if value in {"MIDDLE"}:
        return "MEDIUM"
    if value in {"UPPER_MIDDLE", "VERY_HIGH"}:
        return "HIGH"
    return default


def normalize_json_array(value: Any, allowed: Optional[set] = None) -> List[Any]:
    arr = as_list(value)
    result = []
    seen = set()

    for item in arr:
        if isinstance(item, dict):
            code = safe_text(item.get("code"))
            if allowed is not None and code and code not in allowed:
                continue
            key = json_dumps(item)
            if key not in seen:
                seen.add(key)
                result.append(item)
        else:
            text = safe_text(item)
            if not text:
                continue
            if allowed is not None and text not in allowed:
                continue
            if text not in seen:
                seen.add(text)
                result.append(text)

    return result


def normalize_trait_scores(value: Any) -> Dict[str, int]:
    src = as_dict(value)
    keys = [
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

    result = {}
    for key in keys:
        result[key] = int(round(clamp_number(src.get(key), 0, 100, 50)))

    return result


def validate_and_normalize_result(result: Dict[str, Any], expected_ids: set) -> Dict[str, Any]:
    if not isinstance(result, dict):
        raise ValueError("result가 dict가 아닙니다.")

    pid = result.get("personaProfileId")
    try:
        pid = int(pid)
    except Exception:
        raise ValueError(f"personaProfileId가 잘못되었습니다: {pid}")

    if pid not in expected_ids:
        raise ValueError(f"예상하지 않은 personaProfileId: {pid}")

    # 최소 구조 보정
    work = as_dict(result.get("work"))
    household = as_dict(result.get("household"))
    economy = as_dict(result.get("economy"))
    pet = as_dict(result.get("pet"))
    health = as_dict(result.get("health"))
    sports = as_dict(result.get("sports"))
    arts = as_dict(result.get("artsCulture"))
    food = as_dict(result.get("food"))
    travel_hobby = as_dict(result.get("travelHobby"))
    customer = as_dict(result.get("customerUnderstanding"))
    purchase = as_dict(result.get("purchaseAndMessage"))
    usage = as_dict(result.get("serviceUsage"))
    quality = as_dict(result.get("quality"))
    reasoning = as_dict(result.get("reasoning"))

    result["personaProfileId"] = pid
    result["work"] = work
    result["household"] = household
    result["economy"] = economy
    result["pet"] = pet
    result["health"] = health
    result["sports"] = sports
    result["artsCulture"] = arts
    result["food"] = food
    result["travelHobby"] = travel_hobby
    result["customerUnderstanding"] = customer
    result["purchaseAndMessage"] = purchase
    result["serviceUsage"] = usage
    result["quality"] = quality
    result["reasoning"] = reasoning

    # enum 보정
    work["employmentStatus"] = normalize_enum(work.get("employmentStatus"), EMPLOYMENT_STATUS)
    work["occupationGroup"] = normalize_enum(work.get("occupationGroup"), OCCUPATION_GROUP)
    work["occupationDomain"] = normalize_enum(work.get("occupationDomain"), OCCUPATION_DOMAIN)

    pet["petOwnershipStatus"] = normalize_enum(pet.get("petOwnershipStatus"), PET_OWNERSHIP_STATUS)
    pet["petTypes"] = normalize_json_array(pet.get("petTypes"), PET_TYPES)

    health["healthStatusInferred"] = normalize_enum(health.get("healthStatusInferred"), HEALTH_STATUS)

    sports["sportsParticipationLevel"] = normalize_enum(
        sports.get("sportsParticipationLevel"),
        SPORTS_PARTICIPATION_LEVEL,
    )

    arts["creativeActivityLevel"] = normalize_enum(
        arts.get("creativeActivityLevel"),
        CREATIVE_ACTIVITY_LEVEL,
    )

    purchase["purchaseDecisionStyle"] = normalize_enum(
        purchase.get("purchaseDecisionStyle"),
        PURCHASE_DECISION_STYLE,
    )
    purchase["priceAttitude"] = normalize_enum(
        purchase.get("priceAttitude"),
        PRICE_ATTITUDE,
    )

    customer["customerRoles"] = normalize_json_array(customer.get("customerRoles"), CUSTOMER_ROLES)
    customer["channelAffinities"] = normalize_json_array(customer.get("channelAffinities"), CHANNELS)
    customer["domainAffinities"] = normalize_json_array(customer.get("domainAffinities"), DOMAINS)
    customer["weakDomains"] = normalize_json_array(customer.get("weakDomains"), DOMAINS)
    customer["negativeContexts"] = normalize_json_array(customer.get("negativeContexts"), NEGATIVE_CONTEXTS)
    customer["lifeContexts"] = normalize_json_array(customer.get("lifeContexts"), LIFE_CONTEXTS)

    purchase["attitudeTags"] = normalize_json_array(purchase.get("attitudeTags"), ATTITUDE_TAGS)
    purchase["personalityTags"] = normalize_json_array(purchase.get("personalityTags"), PERSONALITY_TAGS)

    result["traitScores"] = normalize_trait_scores(result.get("traitScores"))

    quality["dataRichnessScore"] = clamp_number(quality.get("dataRichnessScore"), 0, 100, 50)
    quality["personaConsistencyScore"] = clamp_number(quality.get("personaConsistencyScore"), 0, 100, 50)
    quality["selectionUsefulnessScore"] = clamp_number(quality.get("selectionUsefulnessScore"), 0, 100, 50)
    quality["overallConfidence"] = clamp_number(quality.get("overallConfidence"), 0, 1, 0.5)

    return result


def extract_results(response_json: Dict[str, Any], expected_ids: List[int]) -> List[Dict[str, Any]]:
    results = response_json.get("results")
    if not isinstance(results, list):
        raise ValueError("응답 JSON에 results 배열이 없습니다.")

    expected_set = set(expected_ids)
    normalized = []
    seen = set()

    for item in results:
        row = validate_and_normalize_result(item, expected_set)
        pid = row["personaProfileId"]
        if pid in seen:
            raise ValueError(f"중복 personaProfileId 응답: {pid}")
        seen.add(pid)
        normalized.append(row)

    missing = expected_set - seen
    if missing:
        raise ValueError(f"응답 누락 personaProfileId: {sorted(list(missing))[:20]}")

    return normalized


# -----------------------------
# 저장
# -----------------------------

def build_db_row(
    source_by_id: Dict[int, Dict[str, Any]],
    result: Dict[str, Any],
    args,
    raw_response: Dict[str, Any],
) -> Tuple[Any, ...]:
    pid = int(result["personaProfileId"])
    source = source_by_id[pid]

    work = result["work"]
    household = result["household"]
    economy = result["economy"]
    pet = result["pet"]
    health = result["health"]
    sports = result["sports"]
    arts = result["artsCulture"]
    food = result["food"]
    travel = result["travelHobby"]
    customer = result["customerUnderstanding"]
    purchase = result["purchaseAndMessage"]
    usage = result["serviceUsage"]
    quality = result["quality"]
    reasoning = result["reasoning"]

    # source columns json: 원본 입력값의 요약 버전 저장
    source_columns_json = {
        "basic": {
            "age": source.get("source_age") or source.get("age"),
            "sex": source.get("sex") or source.get("gender"),
            "occupation": source.get("source_occupation") or source.get("occupation"),
            "familyType": source.get("source_family_type") or source.get("family_type"),
            "housingType": source.get("source_housing_type") or source.get("housing_type"),
            "educationLevel": source.get("source_education_level") or source.get("education_level"),
            "province": source.get("source_province") or source.get("province"),
            "district": source.get("source_district") or source.get("district"),
        },
        "reasoningSourceColumns": reasoning.get("sourceColumnsJson", {}),
    }

    return (
        pid,
        source.get("source_record_id"),
        safe_text(source.get("source_id") or source.get("profile_source_id")),
        args.model_name,
        args.model_version,
        args.prompt_version,
        args.normalization_version,

        safe_text(result.get("profileSummaryKo")),
        safe_text(result.get("oneLinePersonaKo")),
        safe_text(result.get("representativenessSegment")),
        Json(as_list(result.get("coreIdentityTags"))),

        safe_text(work.get("employmentStatus")),
        safe_text(work.get("occupationGroup")),
        safe_text(work.get("occupationDomain")),
        safe_text(work.get("occupationSeniority")),
        safe_text(work.get("workStyle")),
        safe_text(work.get("previousOccupationGroup")),

        safe_text(household.get("householdStage")),
        safe_text(household.get("familyDecisionRole")),
        safe_text(household.get("householdResponsibilityLevel")),
        household.get("hasParentingContext") if isinstance(household.get("hasParentingContext"), bool) else None,
        household.get("hasSeniorContext") if isinstance(household.get("hasSeniorContext"), bool) else None,
        household.get("hasCaregivingContext") if isinstance(household.get("hasCaregivingContext"), bool) else None,

        safe_text(economy.get("socioeconomicTier")),
        safe_text(economy.get("estimatedIncomeLevel")),
        safe_text(economy.get("estimatedAssetLevel")),
        safe_text(economy.get("disposableIncomeLevel")),
        safe_text(economy.get("financialPressureLevel")),
        safe_text(economy.get("housingStabilityLevel")),
        safe_text(economy.get("incomeSourceType")),
        safe_text(economy.get("spendingPowerLevel")),
        safe_text(economy.get("subscriptionAffordability")),
        safe_text(economy.get("oneTimePurchaseAffordability")),
        safe_text(economy.get("premiumAcceptanceLevel")),
        safe_text(economy.get("priceElasticityLevel")),
        safe_text(economy.get("financialDecisionRole")),

        safe_text(pet.get("petOwnershipStatus")),
        Json(as_list(pet.get("petTypes"))),
        safe_text(pet.get("petCareInvolvementLevel")),
        safe_text(pet.get("petSpendingTendency")),
        Json(as_list(pet.get("petRelatedNeeds"))),

        safe_text(health.get("healthStatusInferred")),
        safe_text(health.get("physicalActivityLevel")),
        safe_text(health.get("mobilityLevel")),
        safe_text(health.get("healthConcernLevel")),
        safe_text(health.get("safetyConcernLevel")),
        safe_text(health.get("hygieneSensitivityLevel")),
        safe_text(health.get("medicalCareRelevance")),
        safe_text(health.get("dietHealthOrientation")),
        Json(as_list(health.get("healthLimitations"))),

        safe_text(sports.get("sportsInterestLevel")),
        safe_text(sports.get("sportsParticipationLevel")),
        Json(as_list(sports.get("sportsTypes"))),
        Json(as_list(sports.get("exerciseMotivation"))),
        safe_text(sports.get("outdoorActivityLevel")),
        safe_text(sports.get("sportsSocialityLevel")),

        safe_text(arts.get("artsInterestLevel")),
        safe_text(arts.get("cultureConsumptionLevel")),
        Json(as_list(arts.get("preferredArtsTypes"))),
        safe_text(arts.get("creativeActivityLevel")),
        safe_text(arts.get("aestheticSensitivityLevel")),
        safe_text(arts.get("cultureSpendingTendency")),

        safe_text(food.get("foodPreferenceProfile")),
        Json(as_list(food.get("preferredCuisines"))),
        Json(as_list(food.get("mealStyle"))),
        safe_text(food.get("deliveryUsageLevel")),
        safe_text(food.get("diningOutLevel")),
        safe_text(food.get("homeCookingLevel")),
        safe_text(food.get("foodAdventurousnessLevel")),
        safe_text(food.get("healthyEatingLevel")),
        safe_text(food.get("foodPriceSensitivityLevel")),
        safe_text(food.get("foodQualitySensitivityLevel")),
        safe_text(food.get("foodHygieneSensitivityLevel")),
        Json(as_list(food.get("foodSocialContext"))),

        safe_text(travel.get("travelInterestLevel")),
        Json(as_list(travel.get("travelStyle"))),
        safe_text(travel.get("travelFrequencyLevel")),
        safe_text(travel.get("planningStyle")),
        safe_text(travel.get("mobilityPreference")),
        safe_text(travel.get("localExplorationLevel")),
        safe_text(travel.get("travelSpendingTendency")),

        Json(as_list(travel.get("hobbyCategories"))),
        safe_text(travel.get("hobbyActivityLevel")),
        safe_text(travel.get("socialHobbyLevel")),
        safe_text(travel.get("homebodyLevel")),
        safe_text(travel.get("outdoorOrientationLevel")),
        safe_text(travel.get("hobbySpendingTendency")),

        Json(as_list(customer.get("lifeContexts"))),
        safe_text(customer.get("primaryCustomerRole")),
        Json(as_list(customer.get("customerRoles"))),
        Json(as_list(customer.get("channelAffinities"))),
        safe_text(customer.get("primaryChannel")),
        Json(as_list(customer.get("domainAffinities"))),
        Json(as_list(customer.get("weakDomains"))),
        Json(as_list(customer.get("negativeContexts"))),

        Json(as_list(purchase.get("attitudeTags"))),
        Json(as_list(purchase.get("personalityTags"))),
        safe_text(purchase.get("decisionTemperament")),
        safe_text(purchase.get("riskToleranceLevel")),
        safe_text(purchase.get("changeResistanceLevel")),
        safe_text(purchase.get("emotionalReactivityLevel")),

        safe_text(purchase.get("purchaseDecisionStyle")),
        Json(as_list(purchase.get("mainPurchaseDrivers"))),
        Json(as_list(purchase.get("mainPurchaseBarriers"))),
        safe_text(purchase.get("priceAttitude")),
        safe_text(purchase.get("trustRequirementLevel")),
        safe_text(purchase.get("qualityRequirementLevel")),
        safe_text(purchase.get("convenienceNeedLevel")),

        safe_text(purchase.get("brandSensitivityLevel")),
        safe_text(purchase.get("localReputationSensitivityLevel")),
        safe_text(purchase.get("expertAuthoritySensitivityLevel")),
        safe_text(purchase.get("certificationSensitivityLevel")),
        safe_text(purchase.get("socialProofSensitivityLevel")),

        safe_text(usage.get("privacySensitivityLevel")),
        safe_text(usage.get("dataSharingResistanceLevel")),
        safe_text(usage.get("locationSharingResistanceLevel")),
        safe_text(usage.get("identityVerificationAcceptanceLevel")),

        safe_text(usage.get("supportChannelPreference")),
        safe_text(usage.get("humanSupportNeedLevel")),
        safe_text(usage.get("selfServiceReadiness")),
        safe_text(usage.get("complaintLikelihoodLevel")),
        safe_text(usage.get("refundSensitivityLevel")),

        safe_text(usage.get("adReceptivenessLevel")),
        safe_text(usage.get("preferredMessageStyle")),
        Json(as_list(usage.get("persuasionTriggers"))),
        Json(as_list(usage.get("messageResistancePoints"))),
        safe_text(usage.get("tonePreference")),

        safe_text(usage.get("dailyRoutinePattern")),
        Json(as_list(usage.get("availableTimeSlots"))),
        safe_text(usage.get("weekendActivityLevel")),
        safe_text(usage.get("weekdayPressureLevel")),
        safe_text(usage.get("scheduleFlexibilityLevel")),

        safe_text(usage.get("accessibilityNeedLevel")),
        safe_text(usage.get("mobilityConstraintLevel")),
        safe_text(usage.get("transportDependency")),
        safe_text(usage.get("inPersonServiceDifficulty")),

        safe_text(usage.get("socialInfluenceLevel")),
        safe_text(usage.get("wordOfMouthInfluenceLevel")),
        safe_text(usage.get("familyInfluenceLevel")),
        safe_text(usage.get("peerInfluenceLevel")),
        safe_text(usage.get("communityInfluenceLevel")),

        safe_text(usage.get("socialValueSensitivityLevel")),
        safe_text(usage.get("ecoFriendlySensitivityLevel")),
        safe_text(usage.get("localBusinessSupportLevel")),
        safe_text(usage.get("publicGoodOrientationLevel")),

        safe_text(usage.get("serviceComprehensionLevel")),
        safe_text(usage.get("onboardingDifficultyLevel")),
        safe_text(usage.get("instructionPreference")),
        safe_text(usage.get("learningWillingnessLevel")),

        Json(as_list(purchase.get("likelyPositivePoints"))),
        Json(as_list(purchase.get("likelyConcerns"))),
        Json(as_list(purchase.get("messageHooks"))),
        Json(as_list(purchase.get("avoidMessages"))),

        Json(result.get("traitScores")),
        Json(as_dict(reasoning.get("traitScoreReasonJson"))),
        Json(as_dict(reasoning.get("traitScoreConfidenceJson"))),

        quality.get("dataRichnessScore"),
        quality.get("personaConsistencyScore"),
        quality.get("selectionUsefulnessScore"),
        safe_text(quality.get("personaRiskLevel")),

        quality.get("overallConfidence"),
        Json(as_dict(reasoning.get("evidenceJson"))),
        Json(source_columns_json),
        Json(as_list(reasoning.get("lowConfidenceFields"))),
        Json(as_list(reasoning.get("unknownFields"))),
        Json(raw_response),
    )


INSERT_COLUMNS = [
    "persona_profile_id", "source_record_id", "source_id",
    "model_name", "model_version", "prompt_version", "normalization_version",
    "profile_summary_ko", "one_line_persona_ko", "representativeness_segment", "core_identity_tags",
    "employment_status", "occupation_group", "occupation_domain", "occupation_seniority", "work_style", "previous_occupation_group",
    "household_stage", "family_decision_role", "household_responsibility_level", "has_parenting_context", "has_senior_context", "has_caregiving_context",
    "socioeconomic_tier", "estimated_income_level", "estimated_asset_level", "disposable_income_level", "financial_pressure_level", "housing_stability_level", "income_source_type", "spending_power_level", "subscription_affordability", "one_time_purchase_affordability", "premium_acceptance_level", "price_elasticity_level", "financial_decision_role",
    "pet_ownership_status", "pet_types", "pet_care_involvement_level", "pet_spending_tendency", "pet_related_needs",
    "health_status_inferred", "physical_activity_level", "mobility_level", "health_concern_level", "safety_concern_level", "hygiene_sensitivity_level", "medical_care_relevance", "diet_health_orientation", "health_limitations",
    "sports_interest_level", "sports_participation_level", "sports_types", "exercise_motivation", "outdoor_activity_level", "sports_sociality_level",
    "arts_interest_level", "culture_consumption_level", "preferred_arts_types", "creative_activity_level", "aesthetic_sensitivity_level", "culture_spending_tendency",
    "food_preference_profile", "preferred_cuisines", "meal_style", "delivery_usage_level", "dining_out_level", "home_cooking_level", "food_adventurousness_level", "healthy_eating_level", "food_price_sensitivity_level", "food_quality_sensitivity_level", "food_hygiene_sensitivity_level", "food_social_context",
    "travel_interest_level", "travel_style", "travel_frequency_level", "planning_style", "mobility_preference", "local_exploration_level", "travel_spending_tendency",
    "hobby_categories", "hobby_activity_level", "social_hobby_level", "homebody_level", "outdoor_orientation_level", "hobby_spending_tendency",
    "life_contexts", "primary_customer_role", "customer_roles", "channel_affinities", "primary_channel", "domain_affinities", "weak_domains", "negative_contexts",
    "attitude_tags", "personality_tags", "decision_temperament", "risk_tolerance_level", "change_resistance_level", "emotional_reactivity_level",
    "purchase_decision_style", "main_purchase_drivers", "main_purchase_barriers", "price_attitude", "trust_requirement_level", "quality_requirement_level", "convenience_need_level",
    "brand_sensitivity_level", "local_reputation_sensitivity_level", "expert_authority_sensitivity_level", "certification_sensitivity_level", "social_proof_sensitivity_level",
    "privacy_sensitivity_level", "data_sharing_resistance_level", "location_sharing_resistance_level", "identity_verification_acceptance_level",
    "support_channel_preference", "human_support_need_level", "self_service_readiness", "complaint_likelihood_level", "refund_sensitivity_level",
    "ad_receptiveness_level", "preferred_message_style", "persuasion_triggers", "message_resistance_points", "tone_preference",
    "daily_routine_pattern", "available_time_slots", "weekend_activity_level", "weekday_pressure_level", "schedule_flexibility_level",
    "accessibility_need_level", "mobility_constraint_level", "transport_dependency", "in_person_service_difficulty",
    "social_influence_level", "word_of_mouth_influence_level", "family_influence_level", "peer_influence_level", "community_influence_level",
    "social_value_sensitivity_level", "eco_friendly_sensitivity_level", "local_business_support_level", "public_good_orientation_level",
    "service_comprehension_level", "onboarding_difficulty_level", "instruction_preference", "learning_willingness_level",
    "likely_positive_points", "likely_concerns", "message_hooks", "avoid_messages",
    "trait_scores_json", "trait_score_reason_json", "trait_score_confidence_json",
    "data_richness_score", "persona_consistency_score", "selection_usefulness_score", "persona_risk_level",
    "overall_confidence", "evidence_json", "source_columns_json", "low_confidence_fields", "unknown_fields", "raw_response",
]


def upsert_results(conn, source_by_id: Dict[int, Dict[str, Any]], results: List[Dict[str, Any]], args, raw_response: Dict[str, Any]) -> None:
    values = [build_db_row(source_by_id, result, args, raw_response) for result in results]

    columns_sql = ", ".join(INSERT_COLUMNS)
    update_sql = ",\n        ".join([
        f"{col} = EXCLUDED.{col}"
        for col in INSERT_COLUMNS
        if col not in {"persona_profile_id", "model_version", "prompt_version", "normalization_version"}
    ])

    sql = f"""
    INSERT INTO persona_llm_understanding_profile (
        {columns_sql}
    )
    VALUES %s
    ON CONFLICT (persona_profile_id, model_version, prompt_version, normalization_version) DO UPDATE SET
        {update_sql},
        updated_at = CURRENT_TIMESTAMP;
    """

    with conn.cursor() as cur:
        execute_values(cur, sql, values, page_size=len(values))


# -----------------------------
# 실패 로그 / 리포트
# -----------------------------

def write_jsonl(path: str, obj: Dict[str, Any]) -> None:
    ensure_dir(path)
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


def print_results_summary(results: List[Dict[str, Any]]) -> None:
    counters = {
        "employment": Counter(),
        "occupation": Counter(),
        "domain": Counter(),
        "primary_role": Counter(),
        "primary_channel": Counter(),
        "spending_power": Counter(),
        "pet": Counter(),
        "health": Counter(),
        "confidence_bucket": Counter(),
    }

    for r in results:
        work = r.get("work", {})
        economy = r.get("economy", {})
        pet = r.get("pet", {})
        health = r.get("health", {})
        customer = r.get("customerUnderstanding", {})
        quality = r.get("quality", {})

        counters["employment"][work.get("employmentStatus", "UNKNOWN")] += 1
        counters["occupation"][work.get("occupationGroup", "UNKNOWN")] += 1
        counters["domain"][work.get("occupationDomain", "UNKNOWN")] += 1
        counters["primary_role"][customer.get("primaryCustomerRole", "UNKNOWN")] += 1
        counters["primary_channel"][customer.get("primaryChannel", "UNKNOWN")] += 1
        counters["spending_power"][economy.get("spendingPowerLevel", "UNKNOWN")] += 1
        counters["pet"][pet.get("petOwnershipStatus", "UNKNOWN")] += 1
        counters["health"][health.get("healthStatusInferred", "UNKNOWN")] += 1

        conf = clamp_number(quality.get("overallConfidence"), 0, 1, 0)
        if conf >= 0.8:
            bucket = "0.8+"
        elif conf >= 0.6:
            bucket = "0.6~0.8"
        elif conf >= 0.4:
            bucket = "0.4~0.6"
        else:
            bucket = "~0.4"
        counters["confidence_bucket"][bucket] += 1

    print()
    print("=" * 100)
    print("응답 요약")
    print("=" * 100)
    for name, counter in counters.items():
        print()
        print(f"## {name}")
        for k, v in counter.most_common(20):
            print(f"- {k}: {v}")
    print("=" * 100)


# -----------------------------
# 실행
# -----------------------------

# -----------------------------
# 병렬 LLM 호출용
# -----------------------------

def call_llm_batch_only(args, batch_no: int, personas: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    스레드에서 실행되는 함수.
    DB 커넥션은 사용하지 않고 DeepSeek 호출 + JSON 검증만 수행한다.
    저장은 메인 스레드에서 순차 처리한다.
    """
    expected_ids = [int(p["persona_profile_id"]) for p in personas]
    raw_response = call_deepseek(args, personas)
    results = extract_results(raw_response, expected_ids)

    return {
        "batch_no": batch_no,
        "ids": expected_ids,
        "personas": personas,
        "raw_response": raw_response,
        "results": results,
    }


def process_llm_batch(conn, args, personas: List[Dict[str, Any]]) -> Tuple[int, int, List[Dict[str, Any]]]:
    expected_ids = [int(p["persona_profile_id"]) for p in personas]
    source_by_id = {int(p["persona_profile_id"]): p for p in personas}

    raw_response = call_deepseek(args, personas)
    results = extract_results(raw_response, expected_ids)

    if args.dry_run:
        print()
        print("=" * 100)
        print("DRY RUN RAW RESPONSE")
        print("=" * 100)
        print(json.dumps(raw_response, ensure_ascii=False, indent=2)[:12000])
        print("=" * 100)
    else:
        upsert_results(conn, source_by_id, results, args, raw_response)
        conn.commit()

    return len(results), 0, results


def run(conn, args) -> None:
    ensure_table(conn)

    if args.reset:
        reset_table(conn, args.model_version, args.prompt_version, args.normalization_version)

    last_id = args.start_id or 0
    processed = 0
    success_count = 0
    fail_count = 0
    all_results_for_summary: List[Dict[str, Any]] = []
    batch_no = 0

    fail_path = args.fail_jsonl or f"./logs/deepseek_understanding_failures_{now_string()}.jsonl"

    while True:
        limit_remaining = None
        if args.limit is not None:
            limit_remaining = args.limit - processed
            if limit_remaining <= 0:
                break

        rows = fetch_personas(conn, args, last_id, limit_remaining)
        if not rows:
            break

        # fetch_personas는 id > last_id 기준. skip_existing 사용 시 last_id는 fetch 결과 끝까지 이동해야 함.
        last_id = int(rows[-1]["persona_profile_id"])

        llm_batches: List[Tuple[int, List[Dict[str, Any]]]] = []
        for i in range(0, len(rows), args.llm_batch_size):
            personas = rows[i:i + args.llm_batch_size]
            if not personas:
                continue
            batch_no += 1
            llm_batches.append((batch_no, personas))

        if not llm_batches:
            if args.dry_run:
                break
            continue

        log(
            f"[PARALLEL] fetched_personas={len(rows)}, "
            f"llm_batches={len(llm_batches)}, max_workers={args.max_workers}"
        )

        # max_workers=1이면 기존처럼 순차 실행. 디버깅과 안정성 확인용.
        if args.max_workers == 1:
            for current_batch_no, personas in llm_batches:
                ids = [int(p["persona_profile_id"]) for p in personas]

                try:
                    log(f"[LLM] batch={current_batch_no}, personas={len(personas)}, id_range={ids[0]}~{ids[-1]}")

                    result_obj = call_llm_batch_only(args, current_batch_no, personas)
                    results = result_obj["results"]
                    raw_response = result_obj["raw_response"]
                    source_by_id = {int(p["persona_profile_id"]): p for p in personas}

                    if args.dry_run:
                        print()
                        print("=" * 100)
                        print("DRY RUN RAW RESPONSE")
                        print("=" * 100)
                        print(json.dumps(raw_response, ensure_ascii=False, indent=2)[:12000])
                        print("=" * 100)
                    else:
                        upsert_results(conn, source_by_id, results, args, raw_response)
                        conn.commit()

                    ok = len(results)
                    success_count += ok
                    processed += len(personas)

                    if args.summary_sample_limit <= 0 or len(all_results_for_summary) < args.summary_sample_limit:
                        remaining = max(0, args.summary_sample_limit - len(all_results_for_summary))
                        all_results_for_summary.extend(results[:remaining])

                    log(f"[OK] batch={current_batch_no}, saved={ok}, total_success={success_count}, processed={processed}")

                    if args.sleep > 0:
                        time.sleep(args.sleep)

                except Exception as e:
                    fail_count += len(personas)
                    processed += len(personas)

                    error_obj = {
                        "time": datetime.now().isoformat(),
                        "batch_no": current_batch_no,
                        "ids": ids,
                        "error": str(e),
                    }
                    write_jsonl(fail_path, error_obj)
                    log(f"[FAIL] batch={current_batch_no}, ids={ids[0]}~{ids[-1]}, error={e}")

                    if args.stop_on_error:
                        raise

        else:
            # 병렬 실행: DeepSeek 호출만 스레드에서 수행하고, DB 저장은 메인 스레드에서 순차 수행.
            with ThreadPoolExecutor(max_workers=args.max_workers) as executor:
                future_to_meta = {}

                for current_batch_no, personas in llm_batches:
                    ids = [int(p["persona_profile_id"]) for p in personas]
                    log(f"[LLM_SUBMIT] batch={current_batch_no}, personas={len(personas)}, id_range={ids[0]}~{ids[-1]}")

                    future = executor.submit(call_llm_batch_only, args, current_batch_no, personas)
                    future_to_meta[future] = {
                        "batch_no": current_batch_no,
                        "ids": ids,
                        "personas": personas,
                    }

                    # 너무 동시에 때리는 것을 피하고 싶을 때 사용.
                    # 기본값 0.2초면 4개 병렬에서도 약간 완충된다.
                    if args.sleep > 0:
                        time.sleep(args.sleep)

                for future in as_completed(future_to_meta):
                    meta = future_to_meta[future]
                    current_batch_no = meta["batch_no"]
                    ids = meta["ids"]
                    personas = meta["personas"]

                    try:
                        result_obj = future.result()
                        results = result_obj["results"]
                        raw_response = result_obj["raw_response"]
                        source_by_id = {int(p["persona_profile_id"]): p for p in personas}

                        if args.dry_run:
                            print()
                            print("=" * 100)
                            print(f"DRY RUN RAW RESPONSE batch={current_batch_no}")
                            print("=" * 100)
                            print(json.dumps(raw_response, ensure_ascii=False, indent=2)[:12000])
                            print("=" * 100)
                        else:
                            upsert_results(conn, source_by_id, results, args, raw_response)
                            conn.commit()

                        ok = len(results)
                        success_count += ok
                        processed += len(personas)

                        if args.summary_sample_limit <= 0 or len(all_results_for_summary) < args.summary_sample_limit:
                            remaining = max(0, args.summary_sample_limit - len(all_results_for_summary))
                            all_results_for_summary.extend(results[:remaining])

                        log(
                            f"[OK] batch={current_batch_no}, saved={ok}, "
                            f"total_success={success_count}, processed={processed}"
                        )

                    except Exception as e:
                        fail_count += len(personas)
                        processed += len(personas)

                        error_obj = {
                            "time": datetime.now().isoformat(),
                            "batch_no": current_batch_no,
                            "ids": ids,
                            "error": str(e),
                        }
                        write_jsonl(fail_path, error_obj)
                        log(f"[FAIL] batch={current_batch_no}, ids={ids[0]}~{ids[-1]}, error={e}")

                        if args.stop_on_error:
                            raise

        if args.dry_run:
            break

    if all_results_for_summary:
        print_results_summary(all_results_for_summary)

    log(f"[DONE] processed={processed}, success={success_count}, failed={fail_count}, last_id={last_id}")
    if fail_count > 0:
        log(f"[FAIL_LOG] {fail_path}")


def parse_args():
    parser = argparse.ArgumentParser(description="DeepSeek 기반 페르소나 이해 프로필 생성")

    # DB
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="precustomer")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")

    # DeepSeek
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--model-name", default=DEFAULT_MODEL_NAME)
    parser.add_argument("--model-version", default=DEFAULT_MODEL_VERSION)
    parser.add_argument("--prompt-version", default=PROMPT_VERSION)
    parser.add_argument("--normalization-version", default=NORMALIZATION_VERSION)
    parser.add_argument("--thinking-mode", choices=["enabled", "disabled"], default="disabled",
                        help="DeepSeek V4 thinking mode. 대량 정규화는 기본 disabled 권장")
    parser.add_argument("--reasoning-effort", choices=["high", "max"], default="high",
                        help="--thinking-mode enabled일 때만 사용")
    parser.add_argument("--temperature", type=float, default=0.1)
    parser.add_argument("--max-tokens", type=int, default=12000)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--max-retries", type=int, default=3)
    parser.add_argument("--retry-sleep", type=float, default=2.0)
    parser.add_argument("--sleep", type=float, default=0.2)

    # 처리 범위
    parser.add_argument("--start-id", type=int, default=0)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--fetch-batch-size", type=int, default=100)
    parser.add_argument("--llm-batch-size", type=int, default=5)
    parser.add_argument("--max-workers", type=int, default=1,
                        help="동시 DeepSeek 호출 수. 최대 4 권장/제한")

    # 동작 옵션
    parser.add_argument("--skip-existing", action="store_true")
    parser.add_argument("--reset", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--stop-on-error", action="store_true")
    parser.add_argument("--fail-jsonl", default=None)
    parser.add_argument("--summary-sample-limit", type=int, default=200)

    return parser.parse_args()


def main():
    args = parse_args()
    started_at = datetime.now()

    log("[START] deepseek_normalize_persona_understanding")
    log(f"[CONFIG] db={args.host}:{args.port}/{args.dbname}, user={args.user}")
    log(f"[CONFIG] model={args.model_version}, prompt={args.prompt_version}, schema={args.normalization_version}, thinking={args.thinking_mode}")
    log(f"[CONFIG] start_id={args.start_id}, limit={args.limit}, fetch_batch_size={args.fetch_batch_size}, llm_batch_size={args.llm_batch_size}, max_workers={args.max_workers}")
    log(f"[CONFIG] dry_run={args.dry_run}, skip_existing={args.skip_existing}, reset={args.reset}")

    if args.llm_batch_size < 1:
        raise ValueError("--llm-batch-size는 1 이상이어야 합니다.")

    if args.max_workers < 1:
        raise ValueError("--max-workers는 1 이상이어야 합니다.")

    if args.max_workers > 4:
        raise ValueError("--max-workers는 최대 4까지만 허용합니다.")

    conn = connect_db(args)

    try:
        run(conn, args)
    except KeyboardInterrupt:
        conn.rollback()
        log("[INTERRUPT] 사용자 중단")
        raise
    except Exception as e:
        conn.rollback()
        log("[ERROR] 실행 실패")
        log(str(e))
        raise
    finally:
        conn.close()
        log(f"[END] elapsed={datetime.now() - started_at}")


if __name__ == "__main__":
    main()
