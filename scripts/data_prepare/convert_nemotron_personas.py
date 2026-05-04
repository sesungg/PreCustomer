import argparse
import json
from pathlib import Path

import pandas as pd


def text(value, default=""):
    if pd.isna(value):
        return default
    value = str(value).strip()
    return value if value else default


def age_group(age_value):
    try:
        age = int(float(age_value))
    except Exception:
        return "미상"

    if age < 20:
        return "10대"
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


def numeric_age(age_value):
    try:
        return int(float(age_value))
    except Exception:
        return None


def digital_familiarity(age_value, education_level):
    try:
        age = int(float(age_value))
    except Exception:
        age = None

    edu = text(education_level)

    if age is not None:
        if age <= 35:
            return "높음"
        if age <= 55:
            return "보통"
        if age >= 65:
            return "낮음"

    if "대학교" in edu or "대학" in edu:
        return "보통 이상"

    return "보통"


def buying_sensitivity(row):
    housing = text(row.get("housing_type"))
    family_type = text(row.get("family_type"))
    career_goals = text(row.get("career_goals_and_ambitions"))

    signals = []

    if "전·월세" in housing or "월세" in housing:
        signals.append("가격 민감 가능성 높음")
    if "자녀" in family_type or "배우자" in family_type:
        signals.append("가족 지출 고려")
    if "생활비" in career_goals or "꾸준" in career_goals or "건강" in career_goals:
        signals.append("실용성과 안정성 중시")

    return ", ".join(signals) if signals else "보통"


def build_pain_points(row):
    career_goals = text(row.get("career_goals_and_ambitions"))
    cultural = text(row.get("cultural_background"))
    age = text(row.get("age"))

    parts = []

    if career_goals:
        parts.append(career_goals)

    try:
        numeric_age = int(float(age))
        if numeric_age >= 60:
            parts.append("새로운 디지털 서비스 사용 시 복잡한 절차와 신뢰성을 중요하게 볼 가능성이 있습니다.")
    except Exception:
        pass

    if cultural:
        parts.append(cultural)

    return " ".join(parts).strip()


def build_persona_summary(row):
    persona = text(row.get("persona"))
    professional = text(row.get("professional_persona"))
    family = text(row.get("family_persona"))
    cultural = text(row.get("cultural_background"))

    parts = []

    if persona:
        parts.append(persona)
    if professional:
        parts.append(professional)
    if family:
        parts.append(family)
    if cultural:
        parts.append(cultural)

    return "\n".join(parts).strip()


def build_interests(row):
    hobbies = text(row.get("hobbies_and_interests"))
    hobbies_list = text(row.get("hobbies_and_interests_list"))
    sports = text(row.get("sports_persona"))
    arts = text(row.get("arts_persona"))
    travel = text(row.get("travel_persona"))
    culinary = text(row.get("culinary_persona"))

    parts = [hobbies, hobbies_list, sports, arts, travel, culinary]
    return "\n".join([p for p in parts if p]).strip()


def build_raw_data(row):
    keys = [
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

    raw = {}
    for key in keys:
        value = row.get(key)
        if value is not None and not pd.isna(value):
            raw[key] = str(value)

    return raw


def convert_row(row):
    province = text(row.get("province"), "미상")
    district = text(row.get("district"), "")
    region = f"{province} {district}".strip()

    age = row.get("age")
    education_level = row.get("education_level")

    return {
        "source": "NEMOTRON",
        "sourceId": text(row.get("uuid")),
        "age": numeric_age(age),
        "ageGroup": age_group(age),
        "gender": text(row.get("sex"), "미상"),
        "region": region,
        "province": province,
        "district": district,
        "occupation": text(row.get("occupation"), "미상"),
        "personaSummary": build_persona_summary(row),
        "interests": build_interests(row),
        "painPoints": build_pain_points(row),
        "digitalFamiliarity": digital_familiarity(age, education_level),
        "buyingSensitivity": buying_sensitivity(row),
        "rawData": build_raw_data(row),
        "active": True,
    }


def parse_args():
    parser = argparse.ArgumentParser(
        description="Convert Nemotron-Personas-Korea parquet data to CustomerPreview JSONL."
    )
    parser.add_argument("input_path", type=Path, help="Input parquet file or directory containing parquet files.")
    parser.add_argument("output_jsonl", type=Path, help="Output JSONL path.")
    parser.add_argument(
        "positional_limit",
        nargs="?",
        type=int,
        help="Optional row limit kept for backward compatibility. Prefer --limit.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Maximum rows to sample before conversion. Defaults to 1000.",
    )

    args = parser.parse_args()
    if args.limit is not None and args.positional_limit is not None:
        parser.error("Use either positional limit or --limit, not both.")

    args.limit = args.limit if args.limit is not None else args.positional_limit
    if args.limit is None:
        args.limit = 1000
    if args.limit <= 0:
        parser.error("limit must be a positive integer.")

    return args


def main():
    args = parse_args()
    input_path = args.input_path
    output_path = args.output_jsonl
    limit = args.limit

    if input_path.is_dir():
        parquet_files = sorted(input_path.glob("*.parquet"))
        if not parquet_files:
            raise FileNotFoundError(f"No parquet files found in {input_path}")
        df = pd.concat([pd.read_parquet(file) for file in parquet_files], ignore_index=True)
    else:
        df = pd.read_parquet(input_path)

    # 너무 큰 데이터는 우선 샘플만 사용
    if len(df) > limit:
        df = df.sample(n=limit, random_state=42)

    records = []
    for _, row in df.iterrows():
        record = convert_row(row)

        # 최소 품질 필터
        if not record["personaSummary"]:
            continue
        if record["occupation"] == "미상":
            continue
        if record["region"] == "미상":
            continue

        records.append(record)

    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(f"Input rows: {len(df)}")
    print(f"Output records: {len(records)}")
    print(f"Created: {output_path}")


if __name__ == "__main__":
    main()
