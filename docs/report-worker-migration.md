# public/admin/worker 분리 운영 가이드

1차 MSA 전환 단계에서는 같은 코드베이스와 같은 DB를 공유하되, Gradle app 모듈별 실행 JAR로 사용자 web, 관리자 web, 리포트 worker를 분리한다.

## 구조

```text
사용자 요청
  -> public-web 프로세스
  -> report_order INSERT

관리자 요청
  -> admin-web 프로세스
  -> report_job INSERT

리포트 생성
  -> worker 프로세스
  -> report_job SELECT FOR UPDATE SKIP LOCKED
  -> 리포트 파이프라인 실행
```

public-web 프로세스는 랜딩 페이지와 리포트 신청 화면만 담당한다. `/`, `/orders/**` 라우트만 노출한다.

admin-web 프로세스는 주문 관리, 리포트 조회, MV 관리, 네이버 쇼핑 관리 API를 담당한다. `/admin/**`, `/api/shopping/naver/**` 라우트를 노출한다. 리포트 생성 요청을 받으면 `report_job`만 생성하고 직접 파이프라인을 실행하지 않는다.

worker 프로세스는 HTTP 서버를 띄우지 않는다. 같은 DB의 `report_job`에서 `PENDING` 작업을 하나씩 가져와 실행한다.

관리자 라우트는 Spring Security 세션 로그인으로 보호한다. 운영 환경에서는 `ADMIN_USERNAME`, `ADMIN_PASSWORD`를 반드시 설정한다.

## 로컬 실행

기존 단일 프로세스 개발 실행은 유지한다.

```bash
./gradlew bootRun
```

public-web, admin-web, gateway, worker를 분리해서 확인하려면 터미널을 네 개 띄운다.

```bash
# terminal 1: public web only
./gradlew bootRunPublicWeb

# terminal 2: admin web only
./gradlew bootRunAdminWeb

# terminal 3: gateway
ADMIN_USERNAME=admin ADMIN_PASSWORD=local-secret ./gradlew bootRunGateway

# terminal 4: worker only
./gradlew bootRunWorker
```

## 운영 실행

app 모듈별 JAR를 빌드한 뒤 각 프로세스로 실행한다.

```bash
./gradlew bootJarAll
```

```bash
# public web
java -jar apps/public-web/build/libs/precustomer-public-web-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod,public-web
```

```bash
# admin web
java -jar apps/admin-web/build/libs/precustomer-admin-web-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod,admin-web
```

```bash
# worker
java -jar apps/report-worker/build/libs/precustomer-report-worker-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod,worker
```

환경변수로도 강제할 수 있다.

```bash
# public web
export APP_WEB_PUBLIC_ENABLED=true
export APP_WEB_ADMIN_ENABLED=false
export REPORT_PIPELINE_WORKER_ENABLED=false

# admin web
export APP_WEB_PUBLIC_ENABLED=false
export APP_WEB_ADMIN_ENABLED=true
export REPORT_PIPELINE_WORKER_ENABLED=false

# worker
export APP_WEB_PUBLIC_ENABLED=false
export APP_WEB_ADMIN_ENABLED=false
export REPORT_PIPELINE_WORKER_ENABLED=true
```

## 필수 조건

- public-web, admin-web, worker는 같은 PostgreSQL DB를 바라봐야 한다.
- `docs/sql/07_report_job_queue.sql`이 적용되어 있어야 한다.
- `docs/sql/08_allow_stopped_order_status.sql`이 적용되어 있어야 한다.
- 서비스별 DB 계정을 사용할 경우 `docs/sql/10_msa_service_roles.sql`이 적용되어 있어야 한다.
- worker 서버에는 Python 파이프라인 스크립트와 이미지 업로드 디렉토리에 접근할 수 있는 경로가 필요하다.
- worker 서버에는 `DEEPSEEK_API_KEY`, `GEMINI_API_KEY`, `NAVER_SHOPPING_CLIENT_ID`, `NAVER_SHOPPING_CLIENT_SECRET`이 설정되어야 한다.
- admin-web 서버에는 `ADMIN_USERNAME`, `ADMIN_PASSWORD`가 설정되어야 한다.

## Gateway 실행

gateway 포함 실행 예시는 `compose.msa.yml`에 있다. Gateway는 Spring Cloud Gateway 앱이며, `/auth/login`에서 JWT를 발급하고 admin 요청에는 내부 Passport 헤더를 붙인다.

```bash
./gradlew bootJarAll
ADMIN_USERNAME=admin \
ADMIN_PASSWORD=local-secret \
GATEWAY_JWT_SECRET=dev-jwt-secret-for-compose-32bytes-minimum \
GATEWAY_PASSPORT_SECRET=dev-passport-secret-for-compose-32bytes \
docker compose -f compose.msa.yml up --build
```

gateway는 `http://localhost:8088`에서 public/admin 경로를 분기한다.

## 아직 하지 않는 것

이 단계에서는 서비스별 DB 물리 분리와 외부 메시지 큐를 도입하지 않는다. `ReportJobQueue` 포트와 `docs/sql/09_msa_schema_boundaries.sql`로 다음 분리 지점을 먼저 고정한다.
