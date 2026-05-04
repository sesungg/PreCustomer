#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

import pandas as pd


def text(row, *names, default=""):
    for name in names:
        if name in row and pd.notna(row[name]):
            value = str(row[name]).strip()
            if value:
                return value
    return default


def age_group(row):
    age = text(row, "age", default="")
    if age.isdigit():
        return f"{int(age) // 10 * 10}대"
    return text(row, "ageGroup", "age_group", default="미입력")


def convert_row(row):
    raw = {key: normalize_raw_value(value) for key, value in row.items()}
    region = " ".join(part for part in [
        text(row, "province", default=""),
        text(row, "district", "region", default="")
    ] if part).strip()

    return {
        "source": "NEMOTRON",
        "sourceId": text(row, "uuid", "id", "sourceId", default=""),
        "age": int(text(row, "age", default="0")) if text(row, "age", default="").isdigit() else None,
        "ageGroup": age_group(row),
        "gender": text(row, "sex", "gender", default="미입력"),
        "region": region or "미입력",
        "province": text(row, "province", default=""),
        "district": text(row, "district", "region", default=""),
        "occupation": text(row, "occupation", default="미입력"),
        "personaSummary": "\n".join(part for part in [
            text(row, "persona", default=""),
            text(row, "professional_persona", default=""),
            text(row, "family_persona", default=""),
            text(row, "cultural_background", default="")
        ] if part),
        "interests": "\n".join(part for part in [
            text(row, "hobbies_and_interests", default=""),
            text(row, "hobbies_and_interests_list", default=""),
            text(row, "travel_persona", default=""),
            text(row, "culinary_persona", default="")
        ] if part),
        "painPoints": "\n".join(part for part in [
            text(row, "career_goals_and_ambitions", default=""),
            text(row, "painPoints", default=""),
            text(row, "cultural_background", default="")
        ] if part),
        "digitalFamiliarity": text(row, "digitalFamiliarity", "digital_familiarity", default="보통"),
        "buyingSensitivity": text(row, "buyingSensitivity", "buying_sensitivity", default="보통"),
        "rawData": raw,
        "active": True,
    }


def normalize_raw_value(value):
    if pd.isna(value):
        return None
    if hasattr(value, "item"):
        return value.item()
    return value


def main():
    parser = argparse.ArgumentParser(description="Convert a limited persona parquet sample to app JSONL format.")
    parser.add_argument("parquet_path")
    parser.add_argument("output_jsonl")
    parser.add_argument("--limit", type=int, default=1000)
    args = parser.parse_args()

    frame = pd.read_parquet(args.parquet_path).head(args.limit)
    output_path = Path(args.output_jsonl)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8") as file:
        for _, row in frame.iterrows():
            file.write(json.dumps(convert_row(row), ensure_ascii=False) + "\n")

    print(f"wrote {len(frame)} personas to {output_path}")


if __name__ == "__main__":
    main()
