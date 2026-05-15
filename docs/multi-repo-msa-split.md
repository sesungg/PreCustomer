# Multi-repo MSA 분리 가이드

이 프로젝트는 먼저 Gradle multi-module로 실행 경계를 나눴고, 다음 단계로 로컬 multi-repo 워크스페이스를 생성할 수 있다.

## Repo 구성

```text
PreCustomerMSA/
  precustomer-contracts/     # 서비스 간 공유 계약 repo
  precustomer-api-gateway/   # Spring Cloud Gateway + JWT/Passport repo
  precustomer-public-web/    # customer-facing web service repo
  precustomer-admin-web/     # admin web service repo
  precustomer-report-worker/ # report job worker service repo
  precustomer-platform/      # compose, SQL orchestration repo
```

## 분리 방식

- `precustomer-contracts`는 Gateway와 서비스가 공유하는 Passport 계약을 담는 작은 라이브러리 repo다.
- public/admin/worker repo는 더 이상 공통 core repo에 의존하지 않는다.
- public/admin/worker repo는 각자의 controller, service, repository, template, resource를 자체 소유한다.
- `precustomer-api-gateway`는 `com.example:precustomer-contracts:0.0.1-SNAPSHOT`에 의존한다.
- 로컬에서는 각 repo의 `settings.gradle`이 sibling repo를 Gradle composite build로 자동 참조한다.
- 운영/CI에서는 contracts를 Maven local, GitHub Packages, Nexus 같은 registry에 publish한 뒤 service repo가 version dependency로 받으면 된다.
- `precustomer-platform`은 Docker Compose와 SQL 문서를 담는다.
- 각 service repo는 생성 시 GitHub Actions CI를 포함한다.

## 생성

```bash
./tools/export-multi-repo-msa.sh ../PreCustomerMSA
```

이미 대상 디렉터리가 비어 있지 않으면 스크립트는 중단한다. 기존 repo를 덮어쓰지 않기 위한 보호 장치다.

## 로컬 검증

```bash
cd ../PreCustomerMSA/precustomer-contracts
./gradlew test

cd ../precustomer-api-gateway
./gradlew bootJar

cd ../precustomer-public-web
./gradlew bootJar

cd ../precustomer-admin-web
./gradlew bootJar

cd ../precustomer-report-worker
./gradlew bootJar
```

각 repo에는 초기 커밋이 생성되므로 바로 remote를 붙여 push할 수 있다.

## Compose 실행

각 service jar를 먼저 빌드한 뒤 platform repo에서 실행한다.

```bash
cd ../PreCustomerMSA/precustomer-platform
ADMIN_USERNAME=admin \
ADMIN_PASSWORD=local-secret \
GATEWAY_JWT_SECRET=dev-jwt-secret-for-compose-32bytes-minimum \
GATEWAY_PASSPORT_SECRET=dev-passport-secret-for-compose-32bytes \
docker compose -f compose.msa.yml up --build
```

## 다음 단계

1. GitHub에 `precustomer-contracts`, `precustomer-api-gateway`, `precustomer-public-web`, `precustomer-admin-web`, `precustomer-report-worker`, `precustomer-platform` repo 생성
2. 각 로컬 repo에 remote 추가 후 push
3. `docs/sql/10_msa_service_roles.sql` 또는 platform repo의 `infra/postgres/init/00_msa_roles.sql`로 서비스별 DB role 적용
4. contracts 라이브러리를 GitHub Packages나 사설 Maven registry로 publish
5. service repo의 composite build fallback은 유지하되 CI에서는 contracts registry dependency로 빌드
6. 서비스별 Docker image와 deployment manifest 분리
