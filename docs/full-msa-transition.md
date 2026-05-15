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
- core/service/platform CI workflow 생성

## 아직 남기는 경계

`precustomer-core`는 shared library로 남긴다. 이 단계에서 shared library를 완전히 없애면 JPA entity, 템플릿, 파이프라인, 테스트를 서비스별로 동시에 재작성해야 하므로 리포트 생성 안정성이 크게 흔들린다.

다음 단계에서 core를 더 작게 나눌 수 있다.

```text
precustomer-order-domain
precustomer-report-contracts
precustomer-persona-domain
precustomer-shopping-contracts
```

그 뒤 public/admin/worker는 shared JPA entity 대신 HTTP API 또는 event contract로 통신한다.

## 로컬 검증

```bash
./gradlew clean test bootJarAll

target="$(mktemp -d)"
./tools/export-multi-repo-msa.sh "$target"
(cd "$target/precustomer-contracts" && ./gradlew test)
(cd "$target/precustomer-core" && ./gradlew test)
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
