# CustomerPreview / 미리고객

1인 창업자, 사이드 프로젝트 개발자, 스마트스토어 셀러, SaaS 운영자를 위한 **AI 기반 가상고객 반응 분석 리포트** 서비스입니다.

NVIDIA NEMOTRON 기반 한국형 가상고객 페르소나 데이터 100만 건에서 계층화 샘플링으로 선별한 30명의 반응을 DeepSeek AI가 시뮬레이션하여 관심도, 구매 의향, 이탈 이유, 불신 포인트, 개선 제안을 **컨설팅 리포트** 형태로 제공합니다.

> 실제 소비자 조사가 아닌 AI 기반 사전 반응 진단입니다. 결과나 성과를 보장하지 않습니다.

---

## 실행 방법

```bash
./gradlew test
./gradlew bootRun
```

브라우저에서 `http://localhost:8080` 접속.

---

## 기술 스택

| 계층 | 기술 |
| ---- | ---- |
| 언어 | Java 17 |
| 프레임워크 | Spring Boot 3.3.5 |
| 빌드 | Gradle |
| 템플릿 | Thymeleaf |
| DB | PostgreSQL (로컬/운영), H2 (테스트) |
| ORM | Spring Data JPA + JdbcTemplate |
| 외부 API | OpenFeign (DeepSeek, Naver) |
| 차트 | Chart.js |
| 이미지 분석 | Gemini 2.5 Flash Lite |
| AI 모델 | DeepSeek V4 Flash / V4 Pro |

---

## 환경변수

```bash
export DEEPSEEK_API_KEY="sk-..."
export GEMINI_API_KEY="..."
export NAVER_SHOPPING_CLIENT_ID="..."
export NAVER_SHOPPING_CLIENT_SECRET="..."
```

---

## DB 프로필

### 로컬 (PostgreSQL + Docker)

```bash
./gradlew bootRun
```

### H2 (인메모리)

```bash
./gradlew bootRun --args='--spring.profiles.active=h2'
```

H2 콘솔: `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:precustomer`)

---

## 주요 URL

| URL | 설명 |
| --- | ---- |
| `GET /` | 랜딩페이지 |
| `GET /orders/new` | 리포트 신청 폼 |
| `POST /orders` | 신청 저장 (이미지 업로드 포함) |
| `GET /admin/orders` | 관리자 주문 목록 |
| `GET /admin/orders/{id}` | 주문 상세 + 리포트 생성 |
| `POST /admin/orders/{id}/generate` | 리포트 생성 (비동기) |
| `GET /admin/orders/{id}/progress` | 생성 진행상황 폴링 |
| `GET /admin/reports/{orderId}` | 리포트 상세 (차트 + 분석) |
| `GET /admin/reports/{orderId}/personas/{id}` | 개별 가상고객 반응 |

### 네이버 쇼핑 API

| 메서드 | URL | 설명 |
| ------ | --- | ---- |
| POST | `/api/shopping/naver/search` | 검색 실행 |
| GET | `/api/shopping/naver/search-groups/{id}/candidates` | 후보 상품 조회 |
| GET | `/api/shopping/naver/search-groups/{id}/price-analysis` | 가격대 분석 |
| GET | `/api/shopping/naver/search-groups/{id}/report-context` | 리포트 context |

---

## 리포트 생성 파이프라인

```
[리포트 생성] 클릭
  → Step 0: URL 크롤링 (page_snapshot)
  → Step 1: 이미지 분석 (Gemini Vision)
  → Step 2: 상품 타겟 프로필 (DeepSeek)
  → Step 3: 가상고객 30명 선별
  → Step 4: 가상고객별 반응 생성 (DeepSeek)
  → Step 5: 최종 리포트 취합 (DeepSeek)
  → 완료 → 리포트 화면에서 차트/분석/가상고객별 상세 확인
```

### 페르소나 샘플링 방식

100만 건의 NEMOTRON 페르소나 풀에서 직업군 기준 **계층화 샘플링**(Stratified Sampling)으로 30명을 선별합니다.

1. NEMOTRON 소스 페르소나를 우선 사용, 부족하면 SEED 페르소나로 보충
2. 랜덤 윈도우로 후보 풀 확보 (약 500~1,000명)
3. 직업군으로 그룹화한 후 그룹별 비례 할당
4. 그룹 내 무작위 추출로 다양성 보장
5. 선별 결과는 `selected_persona` 테이블에 저장

이 방식으로 특정 직업군에 편향되지 않으면서도 상품과 관련성 높은 페르소나를 고르게 분포시킵니다.

비동기 실행 + 진행상황 실시간 폴링 지원. 브라우저를 닫아도 서버에서 계속 실행됩니다.

---

## 페르소나 데이터 파이프라인

### 1. 원본 데이터

NVIDIA NEMOTRON 기반 한국형 가상고객 페르소나 데이터 100만 건을 사용합니다. 각 페르소나는 연령, 성별, 지역, 직업, 관심사, 소비 성향, 생활 맥락 등의 정보를 포함합니다.

### 2. 데이터 준비

```bash
# Parquet → JSONL 변환 (100만 건)
python scripts/data_prepare/convert_nemotron_personas.py /path/to/nemotron_personas_korea.parquet ./data/personas.full.jsonl --limit 1000000

# 생활 맥락, 직업, 텍스트 정규화
python scripts/data_prepare/normalize_persona_context_v2.py ...
```

### 3. DeepSeek 라벨링 → 머신러닝 점수 예측

먼저 페르소나 3,000건을 샘플링하여 **DeepSeek V4 Flash**로 분석, 10가지 소비 성향 지표에 점수를 할당합니다.

| 지표 | 설명 |
| ---- | ---- |
| 디지털 친숙도 | 디지털 서비스/앱 사용에 익숙한 정도 |
| 가격 민감도 | 가격, 가성비에 민감한 정도 |
| 신뢰 민감도 | 후기, 검증, 인증을 중요하게 보는 정도 |
| 편리함 중시도 | 시간 절약, 편의성을 중요하게 보는 정도 |
| 품질 중시도 | 품질, 전문성, 완성도를 중시하는 정도 |
| 새로움 선호도 | 새로운 서비스/상품에 열려 있는 정도 |
| 지역 선호도 | 동네, 지역, 오프라인 상권에 반응할 가능성 |
| 가족 의견 영향도 | 가족 관련 의사결정 영향도 |
| 건강/안전 민감도 | 건강, 안전, 위생에 민감한 정도 |
| 후기 의존도 | 후기, 평판, 추천에 의존하는 정도 |

DeepSeek이 라벨링한 3,000건을 학습 데이터로 ML 모델을 훈련하고, 나머지 약 **997,000건**의 페르소나에 대해 10가지 성향 점수를 예측합니다.

```bash
python scripts/ml/deepseek_label_personas.py --sample-size 3000
python scripts/ml/train_persona_score_model_v2.py
python scripts/ml/predict_persona_scores.py
```

### 4. 대량 import

```bash
# PostgreSQL 필수 (H2 불가)
./gradlew bootRun --args='--app.persona.import-enabled=true --app.persona.import-path=./data/personas.full.jsonl'
```

### 5. 리포트 요청 시 페르소나 선별 기준

리포트 요청이 들어오면 100만 건의 페르소나 풀에서 다음 기준으로 **30명**을 선별합니다.

1. **적합도 점수 계산**: 주문의 타겟 유형(SAAS, 스마트스토어, 전자책 등)과 페르소나의 직업, 연령대, 관심사, 구매 민감도를 비교하여 적합도 점수 산출
2. **계층화 샘플링**: 직업군별로 그룹화한 후 그룹별 비례 할당으로 다양성 보장
3. **관점 다양성 확보**: 적합도 상위 50% (핵심 타겟) + 중간 30% (인접 타겟) + 하위 20% (회의적 타겟)로 구성하여 긍정/부정/중립 시각을 균형 있게 확보
4. **NEMOTRON 우선, SEED 보충**: ML 점수가 있는 NEMOTRON 페르소나를 우선 사용, 부족 시 SEED 페르소나로 보충

---

## DB 마이그레이션

```bash
psql -h localhost -U postgres -d precustomer -f docs/sql/01_migrate_tables.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/02_add_image_paths.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/03_shopping_tables.sql
```

---

## 디자인 원칙

- Pretendard 폰트, 슬레이트 + 블루 컬러 팔레트
- McKinsey 스타일 컨설팅 리포트 레이아웃
- 모든 화면 문구 한국어 (전문용어/영어 표현 배제)
- `@media print` A4 인쇄 지원
- 모바일 반응형

---

## 운영 전 추가 필요

- 관리자 로그인
- 결제 연동
- 이메일 발송
- PDF 다운로드
- DB 마이그레이션 도구 (Flyway/Liquibase)
