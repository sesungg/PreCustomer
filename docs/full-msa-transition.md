# Full MSA 전환 상태

현재 전환 기준은 “한 프로세스가 모든 일을 하는 구조”가 아니라, 서비스별 실행/빌드/DB 계정/검증을 분리하는 것이다.

## 서비스

| 서비스 | 책임 | 실행 산출물 | DB 계정 |
| --- | --- | --- | --- |
| public-web | 고객 랜딩, 리포트 신청 | `precustomer-public-web-*.jar` | `precustomer_public_web` |
| admin-web | 주문/리포트/쇼핑/MV 관리 | `precustomer-admin-web-*.jar` | `precustomer_admin_web` |
| report-worker | `report_job` 소비, 리포트 파이프라인 실행 | `precustomer-report-worker-*.jar` | `precustomer_report_worker` |
| api-gateway | public/admin HTTP routing, JWT 로그인, 내부 Passport 발급 | `precustomer-api-gateway-*.jar` | 없음 |
| postgres | shared DB, schema/role boundary | PostgreSQL | 서비스별 계정 |

## 적용된 MSA 운영 경계

- app별 Gradle bootJar
- app별 Docker build arg
- Spring Cloud Gateway route 분기
- gateway JWT 인증과 내부 `X-PreCustomer-Passport` 전달
- actuator health/readiness endpoint
- Compose healthcheck와 `depends_on.condition: service_healthy`
- 서비스별 DB login role
- schema boundary SQL
- multi-repo export
- export된 repo별 초기 Git commit
- contracts/service/platform CI workflow 생성

## 아직 남기는 경계

`precustomer-core` 모듈은 제거했다. public/admin/worker는 더 이상 공통 app code Gradle 모듈에 의존하지 않고, 공통으로 남기는 코드는 `precustomer-contracts`의 서비스 간 계약으로 제한한다.

이번 단계는 모듈 의존성 제거가 목적이다. 서비스별로 실제로 필요한 코드만 남기는 정리는 DB table ownership과 HTTP/event 통신 경계를 분리할 때 함께 진행한다.

아직 남은 큰 경계는 DB 물리 분리다. 현재는 서비스별 DB 계정과 schema boundary만 먼저 둔 상태이며, 실제 table ownership과 API/event 통신 전환은 다음 단계에서 진행한다.

```text
precustomer-report-contracts
precustomer-shopping-contracts
precustomer-order-events
precustomer-persona-events
```

그 뒤 public/admin/worker는 같은 DB table을 직접 공유하지 않고 HTTP API 또는 event contract로 통신한다.

## 로컬 검증

```bash
./gradlew clean test bootJarAll

target="$(mktemp -d)"
./tools/export-multi-repo-msa.sh "$target"
(cd "$target/precustomer-contracts" && ./gradlew test)
(cd "$target/precustomer-api-gateway" && ./gradlew bootJar)
(cd "$target/precustomer-public-web" && ./gradlew bootJar)
(cd "$target/precustomer-admin-web" && ./gradlew bootJar)
(cd "$target/precustomer-report-worker" && ./gradlew bootJar)
```

## Compose 실행 전 준비

새 DB volume에서는 `infra/postgres/init/00_msa_roles.sql`이 자동 실행된다.

기존 DB volume을 쓰는 경우 아래 SQL을 직접 적용한다.

```bash
psql -h localhost -U postgres -d precustomer -f docs/sql/10_msa_service_roles.sql
```
