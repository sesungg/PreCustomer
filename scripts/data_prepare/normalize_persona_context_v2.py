#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CustomerPreview / 미리고객 - 페르소나 정규화 레이어 생성 스크립트

역할
- persona_profile 100만 건을 읽어 선택 로직에 필요한 정규화 테이블을 만든다.
- persona_occupation_normalized: 직업명 → 고용상태/직업군/도메인
- persona_text_section: 긴 search_text → 섹션별 분리
- persona_life_context: 생활맥락 태그 + confidence + evidence

실행 예시
python3 ./scripts/normalize_persona_context.py \
  --dbname precustomer \
  --batch-size 5000 \
  --reset-normalized

테스트
python3 ./scripts/normalize_persona_context.py \
  --dbname precustomer \
  --batch-size 500 \
  --limit 1000 \
  --dry-run
"""

import argparse
import re
from collections import Counter, defaultdict
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

import psycopg2
from psycopg2.extras import RealDictCursor, execute_values

OCCUPATION_RULE_VERSION = "occupation_rule_v2"
LIFE_CONTEXT_RULE_VERSION = "life_context_rule_v2"

SECTION_MARKERS = [
    ("SUMMARY", "대표 성향:"),
    ("PROFESSIONAL", "직업 관련 성향:"),
    ("FAMILY", "가족 관련 성향:"),
    ("CULTURE", "문화적 배경:"),
    ("SKILL", "기술 및 전문성:"),
    ("HOBBY", "취미와 관심사:"),
    ("CAREER_GOAL", "경력 목표와 포부:"),
    ("SPORTS", "스포츠 관련 성향:"),
    ("ARTS", "예술 관련 성향:"),
    ("TRAVEL", "여행 관련 성향:"),
    ("FOOD", "음식 관련 성향:"),
]

# occupation 컬럼에 "학생"이 직접 들어있지 않아도 학생 맥락을 잡기 위한 키워드.
# 단, 10대/20대에만 보조 판정으로 사용한다.
STUDENT_CONTEXT_KEYWORDS = [
    "대학생", "고등학생", "중학생", "학생",
    "캠퍼스", "강의", "수업", "과제", "시험",
    "동아리", "학과", "전공 수업", "학교생활",
    "기숙사", "통학", "졸업반", "학점", "교내", "교양 수업",
]


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


def safe_text(v: Any) -> str:
    return "" if v is None else str(v).strip()


def norm_space(text: Any) -> str:
    return re.sub(r"\s+", " ", safe_text(text)).strip()


def contains_any(text: Any, keywords: List[str]) -> bool:
    t = safe_text(text).lower()
    return any(k.lower() in t for k in keywords)


def evidence(text: Any, keywords: List[str], max_len: int = 180) -> str:
    t = norm_space(text)
    low = t.lower()
    for k in keywords:
        idx = low.find(k.lower())
        if idx >= 0:
            s = max(0, idx - 50)
            e = min(len(t), idx + len(k) + 110)
            return t[s:e]
    return t[:max_len]



def has_student_context(row: Dict[str, Any]) -> bool:
    """
    원본 occupation에 학생이 없어도 10대/20대 + 강한 학교생활 단서가 있으면 학생으로 보정한다.

    주의:
    - 20대 + 4년제 대학교 같은 학력 정보만으로는 학생으로 보지 않는다.
    - 이미 명확한 직업이 있으면 search_text에 학교 단어가 있어도 학생으로 덮어쓰지 않는다.
    """
    age_group = safe_text(row.get("age_group"))
    occupation = safe_text(row.get("occupation"))

    if contains_any(occupation, ["학생", "대학생", "고등학생", "중학생"]):
        return True

    if occupation and occupation != "무직" and "구직" not in occupation:
        return False

    if age_group not in ["10대", "20대"]:
        return False

    text = " ".join([
        safe_text(row.get("persona_summary")),
        safe_text(row.get("search_text")),
    ])

    return contains_any(text, STUDENT_CONTEXT_KEYWORDS)

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
    ddl = """
    CREATE TABLE IF NOT EXISTS persona_occupation_normalized (
        id BIGSERIAL PRIMARY KEY,
        persona_profile_id BIGINT NOT NULL,
        occupation_raw VARCHAR(500),
        employment_status VARCHAR(50) NOT NULL,
        occupation_group VARCHAR(100) NOT NULL,
        occupation_domain VARCHAR(100),
        previous_occupation_raw VARCHAR(500),
        previous_occupation_group VARCHAR(100),
        normalized_rule_version VARCHAR(100) NOT NULL DEFAULT 'occupation_rule_v1',
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_persona_occupation_normalized_profile
            FOREIGN KEY (persona_profile_id)
            REFERENCES persona_profile(id)
            ON DELETE CASCADE,
        CONSTRAINT uk_persona_occupation_normalized_profile
            UNIQUE (persona_profile_id)
    );

    CREATE INDEX IF NOT EXISTS idx_persona_occupation_normalized_status
        ON persona_occupation_normalized(employment_status);
    CREATE INDEX IF NOT EXISTS idx_persona_occupation_normalized_group
        ON persona_occupation_normalized(occupation_group);
    CREATE INDEX IF NOT EXISTS idx_persona_occupation_normalized_domain
        ON persona_occupation_normalized(occupation_domain);

    COMMENT ON TABLE persona_occupation_normalized IS '페르소나 직업 정규화 테이블';
    COMMENT ON COLUMN persona_occupation_normalized.persona_profile_id IS '페르소나 프로필 식별자';
    COMMENT ON COLUMN persona_occupation_normalized.occupation_raw IS '원본 직업명';
    COMMENT ON COLUMN persona_occupation_normalized.employment_status IS '고용 상태. EMPLOYED, UNEMPLOYED, JOB_SEEKING, RETIRED_OR_INACTIVE, STUDENT, UNKNOWN';
    COMMENT ON COLUMN persona_occupation_normalized.occupation_group IS '정규화 직업군. 사무/관리, 온라인커머스/판매, 의료/돌봄 등';
    COMMENT ON COLUMN persona_occupation_normalized.occupation_domain IS '직업 도메인. LOCAL_SERVICE, ECOMMERCE, OFFICE_WORK, HEALTHCARE 등';
    COMMENT ON COLUMN persona_occupation_normalized.previous_occupation_raw IS '구직중인 경우 이전 직업 원문';
    COMMENT ON COLUMN persona_occupation_normalized.previous_occupation_group IS '구직중인 경우 이전 직업군';
    COMMENT ON COLUMN persona_occupation_normalized.normalized_rule_version IS '정규화 룰 버전';
    COMMENT ON COLUMN persona_occupation_normalized.created_at IS '생성 시각';
    COMMENT ON COLUMN persona_occupation_normalized.updated_at IS '수정 시각';

    CREATE TABLE IF NOT EXISTS persona_life_context (
        id BIGSERIAL PRIMARY KEY,
        persona_profile_id BIGINT NOT NULL,
        context_code VARCHAR(100) NOT NULL,
        confidence NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
        evidence TEXT,
        source_section VARCHAR(100),
        rule_version VARCHAR(100) NOT NULL DEFAULT 'life_context_rule_v1',
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_persona_life_context_profile
            FOREIGN KEY (persona_profile_id)
            REFERENCES persona_profile(id)
            ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_persona_life_context_profile
        ON persona_life_context(persona_profile_id);
    CREATE INDEX IF NOT EXISTS idx_persona_life_context_code
        ON persona_life_context(context_code);
    CREATE INDEX IF NOT EXISTS idx_persona_life_context_confidence
        ON persona_life_context(context_code, confidence);
    CREATE UNIQUE INDEX IF NOT EXISTS uk_persona_life_context_profile_code_rule
        ON persona_life_context(persona_profile_id, context_code, rule_version);

    COMMENT ON TABLE persona_life_context IS '페르소나 생활 맥락 태그 테이블';
    COMMENT ON COLUMN persona_life_context.persona_profile_id IS '페르소나 프로필 식별자';
    COMMENT ON COLUMN persona_life_context.context_code IS '생활 맥락 코드. SENIOR_LIFE, PARENTING, LOCAL_LIVING 등';
    COMMENT ON COLUMN persona_life_context.confidence IS '생활 맥락 신뢰도. 0~1';
    COMMENT ON COLUMN persona_life_context.evidence IS '이 맥락을 부여한 근거 문장 또는 키워드';
    COMMENT ON COLUMN persona_life_context.source_section IS '근거가 나온 텍스트 섹션. FAMILY, CULTURE, FOOD 등';
    COMMENT ON COLUMN persona_life_context.rule_version IS '생활 맥락 추출 룰 버전';
    COMMENT ON COLUMN persona_life_context.created_at IS '생성 시각';

    CREATE TABLE IF NOT EXISTS persona_text_section (
        id BIGSERIAL PRIMARY KEY,
        persona_profile_id BIGINT NOT NULL,
        section_type VARCHAR(100) NOT NULL,
        section_text TEXT NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_persona_text_section_profile
            FOREIGN KEY (persona_profile_id)
            REFERENCES persona_profile(id)
            ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_persona_text_section_profile
        ON persona_text_section(persona_profile_id);
    CREATE INDEX IF NOT EXISTS idx_persona_text_section_type
        ON persona_text_section(section_type);
    CREATE UNIQUE INDEX IF NOT EXISTS uk_persona_text_section_profile_type
        ON persona_text_section(persona_profile_id, section_type);

    COMMENT ON TABLE persona_text_section IS '페르소나 긴 설명 텍스트의 섹션 분리 테이블';
    COMMENT ON COLUMN persona_text_section.persona_profile_id IS '페르소나 프로필 식별자';
    COMMENT ON COLUMN persona_text_section.section_type IS '섹션 유형. SUMMARY, PROFESSIONAL, FAMILY, CULTURE, SKILL, HOBBY, CAREER_GOAL, SPORTS, ARTS, TRAVEL, FOOD';
    COMMENT ON COLUMN persona_text_section.section_text IS '섹션별 텍스트';
    COMMENT ON COLUMN persona_text_section.created_at IS '생성 시각';
    """
    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()


def reset_tables(conn) -> None:
    log("[RESET] 정규화 테이블 초기화 시작")
    with conn.cursor() as cur:
        cur.execute("TRUNCATE TABLE persona_life_context RESTART IDENTITY")
        cur.execute("TRUNCATE TABLE persona_text_section RESTART IDENTITY")
        cur.execute("TRUNCATE TABLE persona_occupation_normalized RESTART IDENTITY")
    conn.commit()
    log("[RESET] 정규화 테이블 초기화 완료")


def extract_previous_occupation(occupation: str) -> str:
    occ = safe_text(occupation)
    for p in [r"^전직\s+(.+?),\s*현재\s*구직중$", r"^전직\s+(.+?)\s+현재\s*구직중$"]:
        m = re.search(p, occ)
        if m:
            return safe_text(m.group(1))
    return ""


def employment_status(occupation: str, age_group: str, source_text: str = "", is_student_context: bool = False) -> str:
    occ = safe_text(occupation)
    age = safe_text(age_group)
    txt = f"{occ} {source_text}"

    if is_student_context:
        return "STUDENT"

    if not occ:
        return "UNKNOWN"
    if contains_any(occ, ["학생", "대학생", "고등학생", "중학생"]):
        return "STUDENT"
    if contains_any(occ, ["현재 구직중", "구직중", "취업 준비", "취준"]):
        return "JOB_SEEKING"
    if occ == "무직":
        if age in ["60대", "70대 이상"] or contains_any(txt, ["은퇴", "노후", "경로당", "복지관", "손주", "시니어"]):
            return "RETIRED_OR_INACTIVE"
        return "UNEMPLOYED"
    if contains_any(occ, ["은퇴"]):
        return "RETIRED_OR_INACTIVE"
    return "EMPLOYED"

def occupation_group_from_raw(occupation: str, age_group: str = "", is_student_context: bool = False) -> str:
    occ = safe_text(occupation)
    age = safe_text(age_group)

    if is_student_context:
        return "학생"

    prev = extract_previous_occupation(occ)
    if prev:
        return "취준/구직"
    if not occ:
        return "기타"
    if occ == "무직":
        return "시니어/비경제활동" if age in ["60대", "70대 이상"] else "일반무직"
    if contains_any(occ, ["학생", "대학생", "고등학생", "중학생"]):
        return "학생"
    if contains_any(occ, ["구직", "취준", "취업 준비"]):
        return "취준/구직"

    # 구체 직업 우선
    if contains_any(occ, ["온라인 쇼핑 판매원", "스마트스토어", "쇼핑몰", "이커머스", "통신 판매", "전자상거래"]):
        return "온라인커머스/판매"
    if contains_any(occ, ["개발", "소프트웨어", "데이터", "IT", "정보", "엔지니어", "프로그래머", "시스템"]):
        return "IT/디지털"
    if contains_any(occ, ["교사", "강사", "교수", "교육", "학습지", "보육교사", "유치원"]):
        return "교육"
    if contains_any(occ, ["간호", "의료", "요양", "돌봄", "복지", "치료", "간호조무사", "보건"]):
        return "의료/돌봄"
    if contains_any(occ, ["변호사", "회계사", "세무사", "의사", "약사", "법률", "감사 사무원", "컨설턴트", "전문가"]):
        return "전문직"
    if contains_any(occ, ["부동산"]):
        return "부동산/자산관리"
    if contains_any(occ, ["사무", "행정", "관리", "기획", "회계", "경리", "총무", "비서", "품질 관리", "자재 관리"]):
        return "사무/관리"
    if contains_any(occ, ["영업", "마케팅", "상담", "고객 상담", "텔레마케터", "판매원", "상점 판매원", "상품 판매"]):
        return "영업/마케팅/상담"
    if contains_any(occ, ["음식", "조리", "주방", "서비스", "카페", "미용", "청소", "제빵", "제과", "반찬"]):
        return "서비스/외식"
    if contains_any(occ, ["운전", "운송", "화물", "배달", "택시", "버스", "철도", "교통 관제"]):
        return "운전/운송"
    if contains_any(occ, ["생산", "제조", "기계", "정비", "설비", "전기", "전자", "조작원", "용접", "공조기", "냉동", "냉장", "제어장치"]):
        return "생산/기술/정비"
    if contains_any(occ, ["건설", "토목", "시공", "목공", "현장"]):
        return "건설/현장"
    if contains_any(occ, ["예술", "디자인", "작가", "배우", "음악", "문화", "공예", "가죽"]):
        return "예술/문화"
    if contains_any(occ, ["자영업", "사업", "대표", "사장", "경영자", "상점 경영자", "소규모 상점"]):
        return "자영업/사업"
    if contains_any(occ, ["농", "어업", "축산", "임업"]):
        return "농림어업"
    return "기타"


def occupation_domain(group: str) -> str:
    return {
        "온라인커머스/판매": "ECOMMERCE",
        "사무/관리": "OFFICE_WORK",
        "영업/마케팅/상담": "SALES_MARKETING",
        "IT/디지털": "DIGITAL_TECH",
        "교육": "EDUCATION",
        "의료/돌봄": "HEALTHCARE_CARE",
        "서비스/외식": "LOCAL_SERVICE_FOOD",
        "생산/기술/정비": "FIELD_TECH_MANUFACTURING",
        "운전/운송": "TRANSPORT_LOGISTICS",
        "건설/현장": "CONSTRUCTION_FIELD",
        "자영업/사업": "SMALL_BUSINESS",
        "전문직": "PROFESSIONAL_SERVICE",
        "부동산/자산관리": "REAL_ESTATE_FINANCE",
        "예술/문화": "ART_CULTURE",
        "농림어업": "AGRI_FISHERY",
        "학생": "STUDENT",
        "취준/구직": "JOB_SEEKING",
        "시니어/비경제활동": "SENIOR_INACTIVE",
        "일반무직": "UNEMPLOYED",
        "기타": "OTHER",
    }.get(group, "OTHER")


def normalize_occupation(row: Dict[str, Any]) -> Dict[str, Any]:
    occ = safe_text(row.get("occupation"))
    age_group = safe_text(row.get("age_group"))
    src = " ".join([safe_text(row.get("persona_summary")), safe_text(row.get("search_text"))])

    student_ctx = has_student_context(row)

    group = occupation_group_from_raw(occ, age_group, is_student_context=student_ctx)
    prev_raw = extract_previous_occupation(occ)
    prev_group = occupation_group_from_raw(prev_raw, age_group) if prev_raw else ""

    return {
        "persona_profile_id": int(row["id"]),
        "occupation_raw": occ,
        "employment_status": employment_status(occ, age_group, src, is_student_context=student_ctx),
        "occupation_group": group,
        "occupation_domain": occupation_domain(group),
        "previous_occupation_raw": prev_raw,
        "previous_occupation_group": prev_group,
        "normalized_rule_version": OCCUPATION_RULE_VERSION,
    }

def parse_sections(search_text: str, persona_summary: str = "") -> Dict[str, str]:
    txt = safe_text(search_text)
    summary = safe_text(persona_summary)
    sections: Dict[str, str] = {}
    if summary:
        sections["PROFILE_SUMMARY"] = norm_space(summary)
    if not txt:
        return sections

    positions = []
    for sec, marker in SECTION_MARKERS:
        idx = txt.find(marker)
        if idx >= 0:
            positions.append((idx, sec, marker))
    positions.sort(key=lambda x: x[0])

    if not positions:
        sections["FULL"] = norm_space(txt)
        return sections

    base = norm_space(txt[:positions[0][0]])
    if base:
        sections["BASIC"] = base

    for i, (idx, sec, marker) in enumerate(positions):
        start = idx + len(marker)
        end = positions[i + 1][0] if i + 1 < len(positions) else len(txt)
        val = norm_space(txt[start:end])
        if val:
            sections[sec] = val
    return sections


def add_ctx(ctxs: Dict[str, Dict[str, Any]], pid: int, code: str, conf: float, ev: str, section: str) -> None:
    conf = max(0.0, min(1.0, float(conf)))
    cur = ctxs.get(code)
    if cur is None or conf > cur["confidence"]:
        ctxs[code] = {
            "persona_profile_id": pid,
            "context_code": code,
            "confidence": conf,
            "evidence": safe_text(ev)[:500],
            "source_section": safe_text(section),
            "rule_version": LIFE_CONTEXT_RULE_VERSION,
        }


def extract_life_contexts(row: Dict[str, Any], occ: Dict[str, Any], sections: Dict[str, str]) -> List[Dict[str, Any]]:
    pid = int(row["id"])
    ctxs: Dict[str, Dict[str, Any]] = {}

    age_group = safe_text(row.get("age_group"))
    family_type = safe_text(row.get("family_type"))
    occupation = safe_text(row.get("occupation"))

    summary = " ".join([sections.get("PROFILE_SUMMARY", ""), sections.get("SUMMARY", "")])
    family = sections.get("FAMILY", "")
    culture = sections.get("CULTURE", "")
    prof = sections.get("PROFESSIONAL", "")
    skill = sections.get("SKILL", "")
    hobby = sections.get("HOBBY", "")
    career = sections.get("CAREER_GOAL", "")
    sports = sections.get("SPORTS", "")
    travel = sections.get("TRAVEL", "")
    food = sections.get("FOOD", "")
    short = " ".join([safe_text(row.get("region")), age_group, occupation, family_type, summary, family, culture, prof])

    # STUDENT_LIFE
    student_kw = STUDENT_CONTEXT_KEYWORDS
    student_text = " ".join([summary, culture, hobby, career, prof])
    if occ["employment_status"] == "STUDENT":
        add_ctx(ctxs, pid, "STUDENT_LIFE", 0.9, f"employment_status=STUDENT, occupation={occupation}", "OCCUPATION")
    elif age_group in ["10대", "20대"] and contains_any(student_text, student_kw):
        add_ctx(ctxs, pid, "STUDENT_LIFE", 0.75, evidence(student_text, student_kw), "SUMMARY/CULTURE/HOBBY/CAREER")

    # SENIOR_LIFE
    if age_group in ["60대", "70대 이상"]:
        add_ctx(ctxs, pid, "SENIOR_LIFE", 0.85 if age_group == "60대" else 0.95, f"age_group={age_group}", "BASIC")
    senior_kw = ["시니어", "노인", "어르신", "은퇴", "노후", "경로당", "복지관", "손주"]
    if contains_any(short, senior_kw):
        add_ctx(ctxs, pid, "SENIOR_LIFE", 0.9, evidence(short, senior_kw), "SUMMARY/FAMILY/CULTURE")

    # PARENTING
    parenting_kw = ["자녀", "아이", "학부모", "초등학생", "교복", "육아", "등하원", "학원", "보육"]
    if contains_any(family_type, ["자녀"]) or contains_any(family, parenting_kw):
        add_ctx(ctxs, pid, "PARENTING", 0.85 if contains_any(family, parenting_kw) else 0.7, evidence(f"{family_type} {family}", parenting_kw + ["자녀"]), "FAMILY")

    # DUAL_INCOME_FAMILY
    if contains_any(short, ["맞벌이"]):
        add_ctx(ctxs, pid, "DUAL_INCOME_FAMILY", 0.9, evidence(short, ["맞벌이"]), "SUMMARY/FAMILY")
    elif contains_any(family_type, ["배우자·자녀와 거주"]) and occ["employment_status"] == "EMPLOYED":
        add_ctx(ctxs, pid, "DUAL_INCOME_FAMILY", 0.55, f"family_type={family_type}, employment_status=EMPLOYED", "BASIC")

    # BUSY_WORKER
    busy_kw = ["퇴근", "출근", "업무", "직장", "근무", "시간 부족", "바쁜", "야근", "회의"]
    if occ["employment_status"] == "EMPLOYED":
        text = " ".join([prof, summary, career])
        if contains_any(text, busy_kw):
            add_ctx(ctxs, pid, "BUSY_WORKER", 0.8, evidence(text, busy_kw), "PROFESSIONAL/CAREER_GOAL")
        elif occ["occupation_group"] in ["사무/관리", "IT/디지털", "전문직", "운전/운송", "생산/기술/정비"]:
            add_ctx(ctxs, pid, "BUSY_WORKER", 0.55, f"employment_status=EMPLOYED, occupation_group={occ['occupation_group']}", "OCCUPATION")

    # LOCAL_LIVING
    local_strong = ["동네", "단골", "전통시장", "시장 골목", "주민센터", "복지관", "경로당", "지역 사회", "상권"]
    local_weak = ["근처", "골목", "주거 단지"]
    local_text = " ".join([culture, family, food, travel, summary])
    if contains_any(local_text, local_strong):
        add_ctx(ctxs, pid, "LOCAL_LIVING", 0.85, evidence(local_text, local_strong), "CULTURE/FAMILY/FOOD/TRAVEL")
    elif contains_any(local_text, local_weak):
        add_ctx(ctxs, pid, "LOCAL_LIVING", 0.55, evidence(local_text, local_weak), "CULTURE/FAMILY/FOOD/TRAVEL")

    # OFFLINE_STORE_USER
    offline_kw = ["세탁소", "수선집", "반찬가게", "매장 방문", "직접 맡", "직접 찾아", "전통시장", "단골 식당", "동네 식당", "노포", "식당을 찾아"]
    offline_text = " ".join([food, culture, travel, summary])
    if contains_any(offline_text, offline_kw):
        add_ctx(ctxs, pid, "OFFLINE_STORE_USER", 0.8, evidence(offline_text, offline_kw), "FOOD/CULTURE/TRAVEL")

    # FOOD_HYGIENE_CONCERN
    food_hygiene_kw = ["위생", "원산지", "알레르기", "신선도", "보관", "청결"]
    food_general_kw = ["반찬", "식품", "집밥", "한식", "음식", "식사", "밥상"]
    if contains_any(f"{food} {summary}", food_hygiene_kw):
        add_ctx(ctxs, pid, "FOOD_HYGIENE_CONCERN", 0.85, evidence(f"{food} {summary}", food_hygiene_kw), "FOOD/SUMMARY")
    elif contains_any(food, food_general_kw) and contains_any(f"{family} {summary}", ["가족", "자녀", "부모", "어머니", "아버지", "집밥"]):
        add_ctx(ctxs, pid, "FOOD_HYGIENE_CONCERN", 0.5, evidence(food, food_general_kw), "FOOD")

    # HEALTH_SAFETY_CONCERN
    health_kw = ["건강", "안전", "낙상", "의료", "병원", "보호자", "위험", "질병", "약", "보행"]
    health_text = " ".join([family, sports, summary, prof])
    if contains_any(health_text, health_kw):
        add_ctx(ctxs, pid, "HEALTH_SAFETY_CONCERN", 0.85, evidence(health_text, health_kw), "FAMILY/SPORTS/SUMMARY/PROFESSIONAL")

    # DIGITAL_COMFORT
    digital_kw = ["스마트폰", "앱", "온라인", "디지털", "유튜브", "컴퓨터", "게임", "구글 캘린더", "배달 앱", "모바일"]
    digital_text = " ".join([prof, skill, hobby, food, summary])
    if occ["occupation_group"] in ["IT/디지털", "온라인커머스/판매"]:
        add_ctx(ctxs, pid, "DIGITAL_COMFORT", 0.85, f"occupation_group={occ['occupation_group']}", "OCCUPATION")
    elif contains_any(digital_text, digital_kw):
        add_ctx(ctxs, pid, "DIGITAL_COMFORT", 0.65, evidence(digital_text, digital_kw), "PROFESSIONAL/SKILL/HOBBY/FOOD")

    # ECOMMERCE_EXPERIENCE
    ecommerce_kw = ["온라인 쇼핑 판매", "스마트스토어", "쇼핑몰", "이커머스", "통신 판매", "전자상거래"]
    if occ["occupation_group"] == "온라인커머스/판매":
        add_ctx(ctxs, pid, "ECOMMERCE_EXPERIENCE", 0.95, f"occupation_raw={occupation}", "OCCUPATION")
    elif contains_any(f"{prof} {skill}", ecommerce_kw):
        add_ctx(ctxs, pid, "ECOMMERCE_EXPERIENCE", 0.8, evidence(f"{prof} {skill}", ecommerce_kw), "PROFESSIONAL/SKILL")

    # HOUSEHOLD_CARE
    household_kw = ["집안", "집밥", "부모님", "어머니", "아버지", "자녀", "아이", "가족들의 건강", "식사", "밥상", "돌봄"]
    household_text = " ".join([family, food, summary])
    if contains_any(household_text, household_kw):
        add_ctx(ctxs, pid, "HOUSEHOLD_CARE", 0.7, evidence(household_text, household_kw), "FAMILY/FOOD/SUMMARY")

    # PRICE_CONSCIOUS_LIVING
    price_kw = ["생활비", "절약", "가성비", "가격 부담", "실속", "소소한 월급", "시급", "경제적", "월급"]
    price_text = " ".join([culture, career, summary, prof])
    if contains_any(price_text, price_kw):
        add_ctx(ctxs, pid, "PRICE_CONSCIOUS_LIVING", 0.75, evidence(price_text, price_kw), "CULTURE/CAREER/SUMMARY/PROFESSIONAL")
    elif occ["employment_status"] in ["UNEMPLOYED", "JOB_SEEKING"]:
        add_ctx(ctxs, pid, "PRICE_CONSCIOUS_LIVING", 0.55, f"employment_status={occ['employment_status']}", "OCCUPATION")

    return list(ctxs.values())


def fetch_batch(conn, last_id: int, batch_size: int, remaining: Optional[int]) -> List[Dict[str, Any]]:
    size = batch_size if remaining is None else min(batch_size, remaining)
    if size <= 0:
        return []
    sql = """
    SELECT
        id,
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
        active
    FROM persona_profile
    WHERE id > %s
      AND active = TRUE
    ORDER BY id
    LIMIT %s
    """
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, (last_id, size))
        return [dict(r) for r in cur.fetchall()]


def upsert_occupations(conn, rows: List[Dict[str, Any]]) -> None:
    if not rows:
        return
    values = [(
        r["persona_profile_id"], r["occupation_raw"], r["employment_status"], r["occupation_group"],
        r["occupation_domain"], r["previous_occupation_raw"] or None, r["previous_occupation_group"] or None,
        r["normalized_rule_version"]
    ) for r in rows]
    sql = """
    INSERT INTO persona_occupation_normalized (
        persona_profile_id,
        occupation_raw,
        employment_status,
        occupation_group,
        occupation_domain,
        previous_occupation_raw,
        previous_occupation_group,
        normalized_rule_version
    ) VALUES %s
    ON CONFLICT (persona_profile_id) DO UPDATE SET
        occupation_raw = EXCLUDED.occupation_raw,
        employment_status = EXCLUDED.employment_status,
        occupation_group = EXCLUDED.occupation_group,
        occupation_domain = EXCLUDED.occupation_domain,
        previous_occupation_raw = EXCLUDED.previous_occupation_raw,
        previous_occupation_group = EXCLUDED.previous_occupation_group,
        normalized_rule_version = EXCLUDED.normalized_rule_version,
        updated_at = CURRENT_TIMESTAMP;
    """
    with conn.cursor() as cur:
        execute_values(cur, sql, values, page_size=min(len(values), 5000))


def upsert_sections(conn, rows: List[Dict[str, Any]]) -> None:
    if not rows:
        return
    values = [(r["persona_profile_id"], r["section_type"], r["section_text"]) for r in rows]
    sql = """
    INSERT INTO persona_text_section (
        persona_profile_id,
        section_type,
        section_text
    ) VALUES %s
    ON CONFLICT (persona_profile_id, section_type) DO UPDATE SET
        section_text = EXCLUDED.section_text;
    """
    with conn.cursor() as cur:
        execute_values(cur, sql, values, page_size=min(len(values), 5000))


def upsert_contexts(conn, rows: List[Dict[str, Any]]) -> None:
    if not rows:
        return
    values = [(
        r["persona_profile_id"], r["context_code"], r["confidence"], r["evidence"], r["source_section"], r["rule_version"]
    ) for r in rows]
    sql = """
    INSERT INTO persona_life_context (
        persona_profile_id,
        context_code,
        confidence,
        evidence,
        source_section,
        rule_version
    ) VALUES %s
    ON CONFLICT (persona_profile_id, context_code, rule_version) DO UPDATE SET
        confidence = EXCLUDED.confidence,
        evidence = EXCLUDED.evidence,
        source_section = EXCLUDED.source_section;
    """
    with conn.cursor() as cur:
        execute_values(cur, sql, values, page_size=min(len(values), 5000))


def process_batch(batch: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
    occ_rows: List[Dict[str, Any]] = []
    section_rows: List[Dict[str, Any]] = []
    context_rows: List[Dict[str, Any]] = []

    for row in batch:
        occ = normalize_occupation(row)
        occ_rows.append(occ)

        sections = parse_sections(row.get("search_text"), row.get("persona_summary"))
        for stype, text in sections.items():
            if text:
                section_rows.append({"persona_profile_id": int(row["id"]), "section_type": stype, "section_text": text})

        context_rows.extend(extract_life_contexts(row, occ, sections))

    return occ_rows, section_rows, context_rows


def print_dry_sample(batch, occ_rows, section_rows, context_rows, n: int) -> None:
    occ_by_id = {r["persona_profile_id"]: r for r in occ_rows}
    sections_by_id = defaultdict(list)
    contexts_by_id = defaultdict(list)
    for r in section_rows:
        sections_by_id[r["persona_profile_id"]].append(r)
    for r in context_rows:
        contexts_by_id[r["persona_profile_id"]].append(r)

    print("\n" + "=" * 100)
    print("DRY RUN SAMPLE")
    print("=" * 100)
    for row in batch[:n]:
        pid = int(row["id"])
        occ = occ_by_id.get(pid, {})
        print("\n" + "-" * 100)
        print(f"persona_profile_id={pid}")
        print(f"age_group={row.get('age_group')} gender={row.get('gender')} region={row.get('region')}")
        print(f"occupation={row.get('occupation')}")
        print(f"employment_status={occ.get('employment_status')} occupation_group={occ.get('occupation_group')} domain={occ.get('occupation_domain')} prev={occ.get('previous_occupation_raw')}")
        print("sections:", ", ".join([s["section_type"] for s in sections_by_id.get(pid, [])]))
        print("life_contexts:")
        for c in sorted(contexts_by_id.get(pid, []), key=lambda x: x["context_code"]):
            print(f"  - {c['context_code']} conf={c['confidence']} section={c['source_section']} evidence={safe_text(c['evidence'])[:120]}")
    print("=" * 100)


def print_distribution(title: str, total: int, status_c: Counter, group_c: Counter, domain_c: Counter, context_c: Counter, section_c: Counter) -> None:
    print("\n" + "=" * 100)
    print(title)
    print("=" * 100)
    print(f"total_personas={total}")
    for name, counter, limit in [
        ("employment_status", status_c, 30),
        ("occupation_group", group_c, 60),
        ("occupation_domain", domain_c, 60),
        ("life_context", context_c, 60),
        ("text_section", section_c, 30),
    ]:
        print(f"\n## {name}")
        for k, v in counter.most_common(limit):
            ratio = (v / total * 100) if total else 0
            print(f"- {k}: {v} ({ratio:.2f}%)")
    print("=" * 100)


def run(conn, args) -> None:
    ensure_tables(conn)
    if args.reset_normalized:
        reset_tables(conn)

    last_id = int(args.start_id or 0)
    processed = 0
    batch_no = 0

    status_c = Counter()
    group_c = Counter()
    domain_c = Counter()
    context_c = Counter()
    section_c = Counter()

    while True:
        remaining = None if args.limit is None else args.limit - processed
        if remaining is not None and remaining <= 0:
            break

        batch = fetch_batch(conn, last_id, args.batch_size, remaining)
        if not batch:
            break

        batch_no += 1
        start_id = batch[0]["id"]
        end_id = batch[-1]["id"]

        occ_rows, section_rows, context_rows = process_batch(batch)

        for r in occ_rows:
            status_c[r["employment_status"]] += 1
            group_c[r["occupation_group"]] += 1
            domain_c[r["occupation_domain"]] += 1
        for r in context_rows:
            context_c[r["context_code"]] += 1
        for r in section_rows:
            section_c[r["section_type"]] += 1

        if args.dry_run:
            if batch_no <= args.dry_run_batches:
                print_dry_sample(batch, occ_rows, section_rows, context_rows, args.sample_count)
        else:
            upsert_occupations(conn, occ_rows)
            upsert_sections(conn, section_rows)
            upsert_contexts(conn, context_rows)
            conn.commit()

        processed += len(batch)
        last_id = int(end_id)

        if batch_no == 1 or batch_no % args.progress_interval == 0:
            log(f"[PROGRESS] batch={batch_no} processed={processed} id_range={start_id}~{end_id} occ={len(occ_rows)} sections={len(section_rows)} contexts={len(context_rows)}")

        if args.dry_run and batch_no >= args.dry_run_batches:
            break

    print_distribution("정규화 결과 분포", processed, status_c, group_c, domain_c, context_c, section_c)

    if args.dry_run:
        conn.rollback()
        log("[DRY_RUN] DB 저장 없이 종료")
    else:
        log("[DONE] 정규화 저장 완료")


def parse_args():
    p = argparse.ArgumentParser(description="CustomerPreview 페르소나 정규화 레이어 생성")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=5432)
    p.add_argument("--dbname", default="precustomer")
    p.add_argument("--user", default="postgres")
    p.add_argument("--password", default="postgres")
    p.add_argument("--batch-size", type=int, default=5000)
    p.add_argument("--limit", type=int, default=None)
    p.add_argument("--start-id", type=int, default=0)
    p.add_argument("--reset-normalized", action="store_true")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--dry-run-batches", type=int, default=1)
    p.add_argument("--sample-count", type=int, default=5)
    p.add_argument("--progress-interval", type=int, default=10)
    return p.parse_args()


def main():
    args = parse_args()
    started = datetime.now()
    log("[START] normalize_persona_context")
    log(f"[CONFIG] db={args.host}:{args.port}/{args.dbname} user={args.user}")
    log(f"[CONFIG] batch_size={args.batch_size} limit={args.limit} dry_run={args.dry_run} reset={args.reset_normalized}")
    conn = connect_db(args)
    try:
        run(conn, args)
    except KeyboardInterrupt:
        conn.rollback()
        log("[INTERRUPT] 사용자 중단")
        raise
    except Exception as e:
        conn.rollback()
        log("[ERROR] 정규화 실패")
        log(str(e))
        raise
    finally:
        conn.close()
        log(f"[END] elapsed={datetime.now() - started}")


if __name__ == "__main__":
    main()
