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
| AI 모델 | DeepSeek V4 Flash |

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

비동기 실행 + 진행상황 실시간 폴링 지원. 브라우저를 닫아도 서버에서 계속 실행됩니다.

---

## 페르소나 데이터 적재

```bash
# Parquet → JSONL 변환
pip install pandas pyarrow
python scripts/data_prepare/inspect_parquet.py /path/to/nemotron_personas_korea.parquet --rows 5
python scripts/data_prepare/convert_nemotron_personas.py /path/to/nemotron_personas_korea.parquet ./data/personas.full.jsonl --limit 1000000

# 대량 import (PostgreSQL 필수, H2 불가)
./gradlew bootRun --args='--app.persona.import-enabled=true --app.persona.import-path=./data/personas.full.jsonl'
```

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
