# Persona Labeling Final Pipeline

## 핵심 변경점

- DeepSeek 결과에 `label_status`와 `train_weight`를 추가합니다.
- `persona_feature_score`에는 숫자 점수를 저장합니다.
- `persona_label_review.reason_json`에는 reason, confidence, label_status, train_weight를 저장합니다.
- ML 학습은 score별 `train_weight`를 기준으로 합니다.
- 사람 검수는 `human_review_app.py` 웹 페이지에서 진행합니다.

## 1. 환경변수

```bash
export DATABASE_URL_SQLALCHEMY="postgresql+psycopg2://postgres:postgres@localhost:5432/precustomer"
export DATABASE_URL="postgresql://postgres:postgres@localhost:5432/precustomer"
export DEEPSEEK_API_KEY="너의_API_KEY"
export DEEPSEEK_MODEL="deepseek-v4-pro"
export LABEL_PROMPT_VERSION="PERSONA_SCORE_LABEL_V2_RECHECK"
```

## 2. worker 자체 검증

```bash
python3 deepseek_score_label_worker.py --self-test
```

## 3. DeepSeek 테스트 실행

```bash
python3 deepseek_score_label_worker.py \
  --input persona_pipeline_output/samples/reanalysis_sample_20260508_164631.jsonl \
  --output-dir persona_pipeline_output/deepseek_outputs/pro_recheck_final_test \
  --limit 5
```

## 4. DB 저장 dry-run

```bash
python3 import_deepseek_labels.py \
  --input persona_pipeline_output/deepseek_outputs/pro_recheck_final_test/reanalysis_sample_20260508_164631.deepseek_success.jsonl \
  --label-batch-id pro_recheck_final_test \
  --dry-run
```

## 5. DB 저장

```bash
python3 import_deepseek_labels.py \
  --input persona_pipeline_output/deepseek_outputs/pro_recheck_final/reanalysis_sample_20260508_164631.deepseek_success.jsonl \
  --label-batch-id pro_recheck_20260508_final \
  --label-source DEEPSEEK_PRO \
  --score-source DEEPSEEK_PSEUDO \
  --score-model-version deepseek_v4_pro_prompt_v2
```

## 6. 라벨 품질 분포 확인

```bash
python3 compare_label_quality.py \
  --label-batch-id pro_recheck_20260508_final \
  --label-source DEEPSEEK_PRO
```

## 7. 사람 검수 웹앱

```bash
pip install flask
python3 human_review_app.py
```

브라우저에서 열기:

```text
http://127.0.0.1:5001/review?label_batch_id=pro_recheck_20260508_final&label_source=DEEPSEEK_PRO
```

UNKNOWN만 검수:

```text
http://127.0.0.1:5001/review?label_batch_id=pro_recheck_20260508_final&label_source=DEEPSEEK_PRO&target_score=review_dependency_score&status=UNKNOWN
```

## 8. 학습

기본은 `DEEPSEEK_PRO / deepseek_v4_pro_prompt_v2`의 `train_weight`를 사용합니다.

```bash
python3 train_score_model_weighted.py \
  --model-version persona_score_ridge_v2 \
  --mode strong_weak
```

보수적으로 STRONG만 사용:

```bash
python3 train_score_model_weighted.py \
  --model-version persona_score_ridge_v2_strong_only \
  --mode strong_only
```

## 주의

- `overall_confidence`는 참고용입니다.
- 학습에서는 score별 `train_weight`를 사용합니다.
- `UNKNOWN`은 숫자가 저장되어도 학습에서는 제외됩니다.
