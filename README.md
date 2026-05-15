# PreCustomerReport

1인 창업자, 사이드 프로젝트 개발자, 스마트스토어 셀러, SaaS 운영자를 위한 **AI 기반 가상고객 반응 분석 리포트** 서비스입니다.

NVIDIA NEMOTRON 기반 한국형 가상고객 페르소나 데이터 100만 건에서 계층화 샘플링으로 선별한 가상고객의 반응을 DeepSeek AI가 시뮬레이션하여 관심도, 구매 의향, 이탈 이유, 불신 포인트, 개선 제안을 **컨설팅 리포트** 형태로 제공합니다.

> 실제 소비자 조사가 아닌 AI 기반 사전 반응 진단입니다. 결과나 성과를 보장하지 않습니다.

---

## 실행 방법

```bash
./gradlew test
./gradlew bootRun
```

브라우저에서 `http://localhost:8080` 접속.

### public/admin/worker/gateway 분리 실행

로컬에서 사용자 화면, 관리자 화면, API Gateway, 리포트 worker를 별도 프로세스로 분리해 확인할 수 있습니다.

```bash
# 터미널 1: 사용자 랜딩/신청 화면
./gradlew bootRunPublicWeb

# 터미널 2: 관리자 주문/리포트 화면
ADMIN_USERNAME=admin ADMIN_PASSWORD=local-secret ./gradlew bootRunAdminWeb

# 터미널 3: gateway 인증/라우팅
ADMIN_USERNAME=admin ADMIN_PASSWORD=local-secret ./gradlew bootRunGateway

# 터미널 4: report_job 큐를 처리하는 worker
./gradlew bootRunWorker
```

기본 포트는 public-web `8080`, admin-web `8081`, gateway `8088`입니다. worker는 HTTP 서버를 띄우지 않습니다.

운영에서는 app 모듈별 JAR를 각각 실행합니다.

```bash
./gradlew bootJarAll

java -jar apps/public-web/build/libs/precustomer-public-web-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod,public-web
java -jar apps/admin-web/build/libs/precustomer-admin-web-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod,admin-web
java -jar apps/api-gateway/build/libs/precustomer-api-gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod,gateway
java -jar apps/report-worker/build/libs/precustomer-report-worker-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod,worker
```

gateway 포함 실행은 아래처럼 확인할 수 있습니다.

```bash
./gradlew bootJarAll
ADMIN_USERNAME=admin \
ADMIN_PASSWORD=local-secret \
GATEWAY_JWT_SECRET=dev-jwt-secret-for-compose-32bytes-minimum \
GATEWAY_PASSPORT_SECRET=dev-passport-secret-for-compose-32bytes \
docker compose -f compose.msa.yml up --build
```

자세한 분리 운영 방식은 [`docs/report-worker-migration.md`](docs/report-worker-migration.md), 6단계 전환 방향은 [`docs/msa-six-stage-roadmap.md`](docs/msa-six-stage-roadmap.md)를 참고하세요.
multi-repo 로컬 분리는 [`docs/multi-repo-msa-split.md`](docs/multi-repo-msa-split.md)와 [`tools/export-multi-repo-msa.sh`](tools/export-multi-repo-msa.sh)를 사용합니다.
서비스별 운영 경계는 [`docs/full-msa-transition.md`](docs/full-msa-transition.md)에 정리되어 있습니다.

---

## 프로젝트 구조

```text
apps/
  api-gateway/     # public/admin 라우팅, JWT 로그인, 내부 Passport 발급
  combined-web/    # 기존처럼 public/admin web을 한 프로세스로 실행
  public-web/      # 사용자 랜딩/신청 화면 실행 JAR
  admin-web/       # 관리자 화면 실행 JAR
  report-worker/   # report_job 처리 worker 실행 JAR
modules/
  precustomer-contracts/ # 서비스 간 공유 계약
```

각 app은 더 이상 공통 core 모듈에 의존하지 않고, 자신의 controller, service, repository, template, resource를 직접 보유합니다.

---

## 기술 스택

| 계층 | 기술 |
| ---- | ---- |
| 언어 | Java 17 |
| 프레임워크 | Spring Boot 3.3.5 |
| 빌드 | Gradle multi-module |
| 템플릿 | Thymeleaf |
| DB | PostgreSQL (로컬/운영), H2 (테스트) |
| ORM | Spring Data JPA + JdbcTemplate |
| 외부 API | OpenFeign (DeepSeek, Naver) |
| 차트 | Chart.js |
| 이미지 분석 | Gemini 2.5 Flash Lite |
| AI 모델 | DeepSeek V4 Flash / V4 Pro |
| PDF 생성 | Flying Saucer + OpenPDF |
| Markdown 파싱 | CommonMark |

---

## 환경변수

```bash
export DEEPSEEK_API_KEY="sk-..."
export GEMINI_API_KEY="..."
export NAVER_SHOPPING_CLIENT_ID="..."
export NAVER_SHOPPING_CLIENT_SECRET="..."
export ADMIN_USERNAME="admin"
export ADMIN_PASSWORD="local-secret"
export GATEWAY_JWT_SECRET="change-this-jwt-secret-at-least-32-bytes"
export GATEWAY_PASSPORT_SECRET="change-this-passport-secret-32-bytes"

# 선택 (기본값 사용 가능)
export REPORT_SELECTED_COUNT=100          # 선별 가상고객 수 (기본 30, 운영 시 100 권장)
export PIPELINE_SCRIPTS_DIR=./scripts/pipeline  # Python 스크립트 경로
export REPORT_PERSONA_CANDIDATE_LIMIT=50000     # 후보 페르소나 풀 크기
export REPORT_REACTION_CONCURRENCY=3            # 반응 생성 동시 처리 수
export APP_WEB_PUBLIC_ENABLED=true              # public-web 프로세스에서 true
export APP_WEB_ADMIN_ENABLED=false              # public-web 프로세스에서 false
export REPORT_PIPELINE_WORKER_ENABLED=false     # web 프로세스에서는 false, worker 프로세스에서는 true
```

---

## DB 프로필

### 로컬 (PostgreSQL)

```bash
./gradlew bootRun
```

### H2 (인메모리, 테스트용)

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
| `POST /admin/orders/{id}/paid` | 입금 확인 처리 |
| `POST /admin/orders/{id}/generate` | 리포트 생성 시작/재개 (비동기) |
| `POST /admin/orders/{id}/stop` | 리포트 생성 중지 |
| `POST /admin/orders/{id}/regenerate` | 리포트 전체 재생성 |
| `GET /admin/orders/{id}/progress` | 생성 진행상황 폴링 |
| `GET /admin/reports/{orderId}` | 리포트 상세 (차트 + 분석) |
| `GET /admin/reports/{orderId}/pdf` | 리포트 PDF 다운로드 |
| `GET /admin/reports/{orderId}/persona/{id}` | 개별 가상고객 반응 |
| `GET /admin/mv/status` | Normalized Score MV 상태 조회 |
| `POST /admin/mv/refresh` | Normalized Score MV 갱신 |
| `POST /admin/mv/recreate` | Normalized Score MV 재생성 |

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
  → Step 1: URL 크롤링 (page_snapshot)           ← crawl_page_snapshot.py
  → Step 2: 네이버 쇼핑 경쟁 상품 분석
  → Step 3: 이미지 분석 (Gemini Vision)           ← 이미지 업로드 시에만 실행
  → Step 4: 상품 타겟 프로필 생성 (DeepSeek)
  → Step 5: 가상고객 N명 선별                     ← MV 기반 정규화 점수 활용
  → Step 6: 가상고객별 반응 생성 (DeepSeek)
  → Step 7: 최종 리포트 취합 (DeepSeek)
  → 완료 → 리포트 화면에서 차트/분석/가상고객별 상세 확인
```

각 단계는 DB 산출물 기준으로 완료 여부를 확인하여, 중단 후 재실행 시 완료된 단계를 건너뛰고 미완료 단계부터 재개합니다.

### 각 단계 설명

**Step 1 — URL 크롤링** (`crawl_page_snapshot.py`)
사용자가 입력한 상세페이지 URL에 접속하여 페이지 제목, 상품명, 가격 정보, 본문 텍스트, 이미지 URL, 리뷰 개수, 평점, 배송 정보 등을 추출합니다. 추출 결과는 `page_snapshot` 테이블에 저장됩니다.

**Step 2 — 네이버 쇼핑 경쟁 상품 분석**
상품명으로 네이버 쇼핑 API를 호출하여 경쟁 상품 가격대, 리뷰 수, 브랜드 분포 등을 분석합니다. 결과는 최종 리포트 컨텍스트로 활용됩니다.

**Step 3 — 이미지 분석** (`analyze_detail_page_images.py` + Gemini 2.5 Flash Lite)
사용자가 업로드한 상세페이지 이미지를 Gemini Vision API로 분석합니다. 상품명, 가격 표기, 주요 판매 포인트, 타겟 고객 추정, 신뢰 요소 등을 추출하여 `page_image_analysis` 테이블에 저장합니다. 이미지 업로드가 없으면 이 단계는 건너뜁니다.

**Step 4 — 상품 타겟 프로필 생성** (DeepSeek V4 Flash)
크롤링된 페이지 정보와 이미지 분석 결과를 종합하여 상품 카테고리, 핵심 키워드, 구매 동인/장벽, 메시지 앵글, 타겟 고객 가설 등을 분석합니다. 결과는 `product_target_profile` 테이블에 저장됩니다.

**Step 5 — 가상고객 선별** (Java Pipeline)
`mv_persona_normalized_score` Materialized View에서 정규화된 백분위 점수를 기반으로 후보를 조회한 뒤, 아래 5개 그룹 Quota로 선별합니다.

| 그룹 | 설명 |
| ---- | ---- |
| CORE_TARGET | 핵심 타겟 (35%) |
| ADJACENT_TARGET | 인접 타겟 (20%) |
| TRUST_PRICE_SKEPTIC | 신뢰/가격 회의론자 (20%) |
| LOW_FIT_CONTROL | 저적합 대조군 (15%) |
| STRATIFIED_RANDOM | 층화 무작위 (10%) |

MV가 없을 경우 원점수 기반 fallback 쿼리로 자동 전환됩니다.

**Step 6 — 가상고객별 반응 생성** (DeepSeek V4 Flash)
선별된 가상고객 각각에 대해 구매 의향, 타겟 적합도, 가격 수용도, 신뢰도, 페이지 이해도 점수와 함께 첫인상, 예상 반응, 대표 발언, 긍정 포인트, 우려사항, 구매 장벽, 개선 제안을 생성합니다. 결과는 `persona_reaction` 테이블에 저장됩니다.

**Step 7 — 최종 리포트 취합** (DeepSeek V4 Flash)
전체 반응을 종합하여 종합 점수, 최종 진단, 구매 의향 분석, 가격/신뢰도/타겟 분석, 고객군별 분석, 개선 제안, 리스크 분석, Markdown 전문 리포트를 생성합니다. 결과는 `final_report` 테이블에 저장됩니다.

---

## 페르소나 데이터 파이프라인

### 1. 원본 데이터

NVIDIA NEMOTRON 기반 한국형 가상고객 페르소나 데이터 100만 건을 사용합니다. 각 페르소나는 연령, 성별, 지역, 직업, 관심사, 소비 성향, 생활 맥락 등의 정보를 포함합니다.

### 2. 데이터 준비

```bash
# Parquet → JSONL 변환 (100만 건)
python scripts/data_prepare/convert_nemotron_personas.py \
  /path/to/nemotron_personas_korea.parquet ./data/personas.full.jsonl --limit 1000000

# 생활 맥락, 직업, 텍스트 정규화
python scripts/data_prepare/normalize_persona_context_v2.py ...
```

### 3. DeepSeek 라벨링 → 머신러닝 점수 예측

페르소나 샘플을 **DeepSeek V4 Flash**로 분석하여 10가지 소비 성향 지표에 점수를 할당하고, 이를 학습 데이터로 ML 모델(Ridge Regression v3)을 훈련합니다.

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

```bash
# 라벨링 (scripts/labeling/)
python scripts/labeling/deepseek_score_label_worker.py
python scripts/labeling/train_score_model_weighted_all_sources.py

# 전체 페르소나 점수 예측 (scripts/ml/)
python scripts/ml/predict_persona_scores.py \
  --model-path scripts/labeling/persona_pipeline_output/models/persona_score_ridge_v3_all_sources.joblib
```

### 4. Normalized Score Materialized View 생성

ML 점수 예측 완료 후 아래 SQL을 실행하여 MV를 생성합니다. 이후 점수 재예측 시마다 `/admin/mv/refresh`를 호출하면 무중단 갱신됩니다.

```bash
psql -h localhost -U postgres -d precustomer -f docs/sql/07_create_normalized_score_mv.sql
```

### 5. 대량 import

```bash
# PostgreSQL 필수 (H2 불가)
./gradlew bootRun --args='--app.persona.import-enabled=true --app.persona.import-path=./data/personas.full.jsonl'
```

---

## DB 마이그레이션

```bash
psql -h localhost -U postgres -d precustomer -f docs/sql/01_migrate_tables.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/02_add_image_paths.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/03_shopping_tables.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/04_add_report_fields.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/05_add_pipeline_entity_fields.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/06_pipeline_performance_indexes.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/07_report_job_queue.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/07_create_normalized_score_mv.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/08_allow_stopped_order_status.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/09_msa_schema_boundaries.sql
psql -h localhost -U postgres -d precustomer -f docs/sql/10_msa_service_roles.sql
```

---

## scripts 디렉토리 구조

```
scripts/
├── pipeline/          ← 런타임 실행 (Java 파이프라인이 직접 호출)
│   ├── crawl_page_snapshot.py
│   └── analyze_detail_page_images.py
├── ml/                ← 운영 ML 파이프라인
│   ├── predict_persona_scores.py      (페르소나 점수 예측 - 주기적 실행)
│   └── export_persona_training_data.py
├── labeling/          ← 모델 학습 워크플로우 (v3 최신)
│   ├── train_score_model_weighted_all_sources.py
│   ├── deepseek_score_label_worker.py
│   ├── human_review_app.py
│   └── persona_pipeline_output/models/  ← 학습된 모델 파일 (v3)
└── data_prepare/      ← DB 초기 적재용 (1회성)
    ├── convert_nemotron_personas.py
    └── ...
```

---

## 운영 전 추가 필요

- 관리자 로그인
- 결제 연동
- 이메일 발송
- DB 마이그레이션 도구 (Flyway/Liquibase)
