#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
CustomerPreview / 미리고객 - 10개 페르소나 성향 점수 ML 학습 v2

수정 사항:
- scikit-learn 버전에 따라 mean_squared_error(squared=False)가 안 먹는 문제 해결
  - RMSE를 직접 sqrt(MSE)로 계산
- Ridge 기본 solver의 sparse TF-IDF 수치 불안정 문제 완화
  - Ridge(solver="lsqr") 사용
- y/pred에 NaN, inf가 섞여도 방어
- 학습/검증 예측값을 0~100으로 clip
- 검증 결과 출력 강화

모델 의미:
- TF-IDF: 텍스트를 숫자 벡터로 바꾸는 방식
- Ridge Regression: 연속 점수 예측용 선형 모델
- solver="lsqr": sparse matrix에서 비교적 안정적인 Ridge 풀이 방식

설치:
pip install pandas numpy scikit-learn joblib

실행:
python3 ./scripts/train_persona_score_model_v2.py \
  --input ./ml_data/train_persona_scores_deepseek_3000.csv \
  --model-output ./models/persona_score_ridge_v1.joblib \
  --metrics-output ./models/persona_score_ridge_v1_metrics.json
"""

import argparse
import json
import math
import warnings
from datetime import datetime
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline


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


TEXT_COLUMNS = [
    "age_group",
    "gender",
    "region",
    "province",
    "district",
    "occupation",
    "education_level",
    "family_type",
    "housing_type",
    "persona_summary",
    "search_text",
]


def safe_text(value):
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

    # 너무 긴 텍스트는 학습/예측 시간을 늘린다.
    # 4000자 정도면 성향 추론에는 충분하다.
    if len(search_text) > 4000:
        search_text = search_text[:4000]

    parts.append(f"상세설명: {search_text}")

    return "\n".join(part for part in parts if part.strip())


def load_dataset(input_path):
    df = pd.read_csv(input_path)

    missing = [col for col in SCORE_COLUMNS if col not in df.columns]
    if missing:
        raise RuntimeError(f"점수 컬럼이 없습니다: {missing}")

    df["model_text"] = df.apply(build_model_text, axis=1)

    # 점수는 0~100 범위로 방어
    for col in SCORE_COLUMNS:
        df[col] = (
            pd.to_numeric(df[col], errors="coerce")
            .replace([np.inf, -np.inf], np.nan)
            .fillna(50)
            .clip(0, 100)
        )

    # 빈 텍스트 방어
    empty_text_count = (df["model_text"].fillna("").str.strip() == "").sum()
    if empty_text_count > 0:
        print(f"[WARN] model_text가 빈 row 수: {empty_text_count}")

    return df


def rmse_score(y_true, y_pred):
    """
    scikit-learn 버전에 따라 mean_squared_error(squared=False)가 안 될 수 있어서
    RMSE를 직접 계산한다.
    """
    mse = mean_squared_error(y_true, y_pred)
    return math.sqrt(float(mse))


def sanitize_prediction(y_pred):
    """
    모델 예측값에 NaN/inf가 섞이는 것을 방어한다.
    """
    y_pred = np.asarray(y_pred, dtype=float)
    y_pred = np.nan_to_num(y_pred, nan=50.0, posinf=100.0, neginf=0.0)
    y_pred = np.clip(y_pred, 0, 100)
    return y_pred


def evaluate(y_true, y_pred):
    metrics = {}

    y_true = np.asarray(y_true, dtype=float)
    y_true = np.nan_to_num(y_true, nan=50.0, posinf=100.0, neginf=0.0)
    y_true = np.clip(y_true, 0, 100)

    y_pred = sanitize_prediction(y_pred)

    for idx, col in enumerate(SCORE_COLUMNS):
        mae = mean_absolute_error(y_true[:, idx], y_pred[:, idx])
        rmse = rmse_score(y_true[:, idx], y_pred[:, idx])

        metrics[col] = {
            "mae": round(float(mae), 4),
            "rmse": round(float(rmse), 4),
            "true_avg": round(float(np.mean(y_true[:, idx])), 4),
            "pred_avg": round(float(np.mean(y_pred[:, idx])), 4),
            "true_min": round(float(np.min(y_true[:, idx])), 4),
            "true_max": round(float(np.max(y_true[:, idx])), 4),
            "pred_min": round(float(np.min(y_pred[:, idx])), 4),
            "pred_max": round(float(np.max(y_pred[:, idx])), 4),
        }

    metrics["overall"] = {
        "mae": round(float(mean_absolute_error(y_true, y_pred)), 4),
        "rmse": round(float(rmse_score(y_true, y_pred)), 4),
    }

    return metrics


def parse_args():
    parser = argparse.ArgumentParser(description="CustomerPreview persona score model trainer v2")

    parser.add_argument("--input", required=True)
    parser.add_argument("--model-output", required=True)
    parser.add_argument("--metrics-output", required=True)

    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--random-state", type=int, default=42)

    parser.add_argument("--max-features", type=int, default=60000)
    parser.add_argument("--min-df", type=int, default=2)
    parser.add_argument("--max-df", type=float, default=0.95)
    parser.add_argument("--alpha", type=float, default=2.0)

    return parser.parse_args()


def main():
    args = parse_args()

    started_at = datetime.now()
    print("[START] 모델 학습 시작")
    print(f"[CONFIG] input={args.input}")

    df = load_dataset(args.input)
    print(f"[DATA] rows={len(df)}")

    if len(df) < 100:
        print("[WARN] 학습 데이터가 100건 미만입니다. 모델 품질이 매우 불안정할 수 있습니다.")

    X = df["model_text"].values
    y = df[SCORE_COLUMNS].values.astype(float)

    X_train, X_valid, y_train, y_valid = train_test_split(
        X,
        y,
        test_size=args.test_size,
        random_state=args.random_state,
    )

    pipeline = Pipeline(
        steps=[
            (
                "tfidf",
                TfidfVectorizer(
                    max_features=args.max_features,
                    min_df=args.min_df,
                    max_df=args.max_df,
                    ngram_range=(1, 2),
                    sublinear_tf=True,
                    dtype=np.float64,
                ),
            ),
            (
                "model",
                Ridge(
                    alpha=args.alpha,
                    solver="lsqr",
                    fit_intercept=True,
                    random_state=args.random_state,
                ),
            ),
        ]
    )

    # 일부 sklearn/numpy 조합에서 내부 RuntimeWarning이 나올 수 있으므로
    # 학습은 계속 진행하되 예측값 sanitize로 방어한다.
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        pipeline.fit(X_train, y_train)

    train_pred = sanitize_prediction(pipeline.predict(X_train))
    valid_pred = sanitize_prediction(pipeline.predict(X_valid))

    metrics = {
        "created_at": datetime.now().isoformat(),
        "train_rows": len(X_train),
        "valid_rows": len(X_valid),
        "score_columns": SCORE_COLUMNS,
        "model": {
            "type": "tfidf_ridge",
            "max_features": args.max_features,
            "min_df": args.min_df,
            "max_df": args.max_df,
            "ngram_range": [1, 2],
            "alpha": args.alpha,
            "solver": "lsqr",
        },
        "train_metrics": evaluate(y_train, train_pred),
        "valid_metrics": evaluate(y_valid, valid_pred),
    }

    model_bundle = {
        "pipeline": pipeline,
        "score_columns": SCORE_COLUMNS,
        "text_columns": TEXT_COLUMNS,
        "model_type": "tfidf_ridge",
        "created_at": datetime.now().isoformat(),
        "build_model_text_version": "v1",
        "prediction_clip_range": [0, 100],
    }

    model_path = Path(args.model_output)
    metrics_path = Path(args.metrics_output)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    metrics_path.parent.mkdir(parents=True, exist_ok=True)

    joblib.dump(model_bundle, model_path)

    with metrics_path.open("w", encoding="utf-8") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    print()
    print("=" * 80)
    print("모델 학습 완료")
    print("=" * 80)
    print(f"model: {model_path}")
    print(f"metrics: {metrics_path}")
    print(f"elapsed: {datetime.now() - started_at}")

    print()
    print("Validation MAE")
    for col, m in metrics["valid_metrics"].items():
        if col == "overall":
            continue
        print(
            f"- {col}: "
            f"MAE={m['mae']}, RMSE={m['rmse']}, "
            f"true_avg={m['true_avg']}, pred_avg={m['pred_avg']}, "
            f"pred_min={m['pred_min']}, pred_max={m['pred_max']}"
        )

    print()
    print(f"Overall MAE={metrics['valid_metrics']['overall']['mae']}")
    print("=" * 80)


if __name__ == "__main__":
    main()
