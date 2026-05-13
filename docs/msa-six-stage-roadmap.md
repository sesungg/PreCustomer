# MSA 6단계 전환 로드맵

이 프로젝트는 포트폴리오용 MSA를 보여주되, 운영 안정성을 해치지 않도록 단계적으로 분리한다.

## 1단계: 실행 프로세스 분리

현재 완료된 형태:

- `public-web`: `/`, `/orders/**`
- `admin-web`: `/admin/**`, `/api/shopping/naver/**`, `/uploads/**`
- `report-worker`: HTTP 서버 없이 `report_job` DB queue 소비

Gradle app 모듈별 JAR를 서로 다른 profile로 실행한다.

## 2단계: 관리자 인증/권한

이번 단계에서 `admin-web`에 Spring Security 기반 세션 로그인을 붙였다.

- `ROLE_ADMIN`: `/admin/**`, `/api/shopping/naver/**`, `/uploads/**`
- anonymous: `/`, `/orders/**`, 정적 리소스
- 관리자 계정: `ADMIN_USERNAME`, `ADMIN_PASSWORD`
- local 기본값: `admin/admin`
- `prod`에서 `admin`, `change-me`, `change-me-before-prod` 같은 기본/placeholder 비밀번호를 쓰면 앱 시작을 중단한다.

## 3단계: 서비스 경계 명확화

현재 경계:

| 실행 단위 | 책임 | 주요 코드 |
| --- | --- | --- |
| `public-web` | 주문 접수 | `order.web`, `order.service` |
| `admin-web` | 운영자 액션, 리포트 조회, 쇼핑 분석 관리 | `order.web.AdminOrderController`, `report.web`, `modules.shopping.web` |
| `report-worker` | 리포트 생성, retry, stop/resume | `report.job`, `report.service.ReportPipelineService` |
| `persona-data` future | 페르소나 원본/프로필/라벨/ML 점수 | `persona.importer`, `report.domain.PersonaProfile` |
| `shopping-data` future | 외부 쇼핑 후보/가격 분석 | `modules.shopping` |

코드에서는 `ReportJobQueue` 인터페이스를 추가해 admin-web/worker가 DB queue 구현에 직접 고정되지 않게 했다.

## 4단계: DB schema 경계

아직 테이블을 실제로 옮기지는 않는다. 대신 `docs/sql/09_msa_schema_boundaries.sql`로 future schema와 table owner를 선언한다.

목표 schema:

- `customer_app`
- `admin_app`
- `report_pipeline`
- `persona_data`
- `shopping_data`

실제 table move, cross-schema FK 정리, 다중 datasource는 운영 검증 후 진행한다.

## 5단계: 메시지 큐 전환 준비

현재는 PostgreSQL DB queue가 정답이다.

- `SELECT ... FOR UPDATE SKIP LOCKED`
- retry/backoff
- cancel request
- job step tracking
- partial result preservation

RabbitMQ/Kafka는 아직 넣지 않는다. 대신 `ReportJobQueue` 포트를 기준으로 나중에 아래 구현체를 추가할 수 있다.

- `DbReportJobQueue`: 현재 구현
- `RabbitReportJobQueue`: future
- `KafkaReportJobQueue`: future

## 6단계: Gateway/Auth Server 준비

이번 단계에서 Nginx gateway 예시와 Docker Compose 실행 구성을 추가했다.

- gateway `/` -> `public-web`
- gateway `/admin/**` -> `admin-web`
- gateway `/api/shopping/naver/**` -> `admin-web`
- gateway `/uploads/**` -> `admin-web`

별도 auth-service는 아직 만들지 않는다. 관리자 사용자/권한 도메인, refresh token, 서비스 간 인증이 필요해지는 시점에 분리한다. 지금은 admin-web 세션 인증으로 충분하고, 포트폴리오에서는 “왜 아직 auth-service를 만들지 않았는지”가 오히려 좋은 설명 포인트다.

## 로컬 실행

```bash
./gradlew bootJarAll

ADMIN_USERNAME=admin \
ADMIN_PASSWORD=local-secret \
docker compose -f compose.msa.yml up --build
```

gateway는 `http://localhost:8088`에서 확인한다.

직접 실행은 기존 Gradle task를 사용한다.

```bash
./gradlew bootRunPublicWeb
./gradlew bootRunAdminWeb
./gradlew bootRunWorker
```

## 다음 단계

1. 관리자 계정을 DB 기반으로 전환
2. 관리자 액션 audit log 추가
3. 고객 리포트 조회를 order id가 아닌 access token URL로 전환
4. schema별 DB role 분리
5. 운영 메트릭/헬스체크 추가
6. 큐 트래픽이 커질 때 RabbitMQ 또는 Kafka PoC
