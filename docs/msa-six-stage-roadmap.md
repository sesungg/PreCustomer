# MSA 전환 로드맵

이 프로젝트는 운영 안정성을 해치지 않도록 단계적으로 MSA 구조로 분리한다.

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

이번 단계에서 Spring Cloud Gateway 실행 앱과 Docker Compose 실행 구성을 추가했다.

- gateway `/` -> `public-web`
- gateway `/admin/**` -> `admin-web`
- gateway `/api/shopping/naver/**` -> `admin-web`
- gateway `/uploads/**` -> `admin-web`
- gateway `/auth/login` -> JWT 발급
- gateway가 admin 요청마다 내부 `X-PreCustomer-Passport`를 발급
- admin-web은 Passport를 검증해 `ROLE_ADMIN`으로 인증 처리

별도 auth-service는 아직 만들지 않는다. 관리자 사용자/권한 도메인, refresh token, 서비스 간 인증이 필요해지는 시점에 분리한다. 지금은 gateway가 얇은 인증 진입점 역할을 맡고, admin-web 세션 로그인은 직접 실행/개발용 fallback으로 유지한다.

## 7단계: 공통 core 제거

`precustomer-core` 모듈을 제거하고 app별 코드 소유권을 분리했다.

- `public-web`, `admin-web`, `report-worker`는 더 이상 공통 core Gradle 모듈에 의존하지 않는다.
- 각 app은 자신의 controller, service, repository, template, resource를 보유한다.
- 서비스 간 공유 코드는 `precustomer-contracts`에 남긴다.
- 아직 DB table은 공유하므로, 서비스별 DB 완전 분리는 다음 단계에서 진행한다.
- `combined-web` 모놀리식 실행 모듈은 제거하고, gateway/public/admin/worker만 실행 단위로 유지한다.
- 이번 단계는 모듈 의존성 제거와 실행 단위 정리가 목표이며, 운영 데이터 이동은 별도 절차로 진행한다.

## 로컬 실행

```bash
./gradlew bootJarAll

ADMIN_USERNAME=admin \
ADMIN_PASSWORD=local-secret \
GATEWAY_JWT_SECRET=dev-jwt-secret-for-compose-32bytes-minimum \
GATEWAY_PASSPORT_SECRET=dev-passport-secret-for-compose-32bytes \
docker compose -f compose.msa.yml up --build
```

gateway는 `http://localhost:8088`에서 확인한다.

기본 포트는 public-web `8080`, admin-web `8081`, gateway `8088`이며, report-worker는 HTTP 서버를 띄우지 않는다.

직접 실행은 기존 Gradle task를 사용한다.

```bash
./gradlew bootRunPublicWeb
./gradlew bootRunAdminWeb
./gradlew bootRunGateway
./gradlew bootRunWorker
```

## 다음 단계

1. 관리자 계정을 DB 기반으로 전환
2. 관리자 액션 audit log 추가
3. 고객 리포트 조회를 order id가 아닌 access token URL로 전환
4. 서비스별 DB table ownership 분리
5. public/admin/worker 간 직접 DB 공유 제거
6. 큐 트래픽이 커질 때 RabbitMQ 또는 Kafka PoC
