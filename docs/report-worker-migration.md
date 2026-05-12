# 리포트 Worker 분리 운영 가이드

리포트 생성은 1차 MSA 전환 단계에서 같은 코드베이스와 같은 DB를 공유하되, 실행 프로세스를 web과 worker로 분리한다.

## 구조

```text
사용자/관리자 요청
  -> web 프로세스
  -> report_job INSERT
  -> worker 프로세스
  -> report_job SELECT FOR UPDATE SKIP LOCKED
  -> 리포트 파이프라인 실행
```

web 프로세스는 HTTP 요청과 관리자 화면을 담당한다. 리포트 생성 요청을 받으면 `report_job`만 생성하고 직접 파이프라인을 실행하지 않는다.

worker 프로세스는 HTTP 서버를 띄우지 않는다. 같은 DB의 `report_job`에서 `PENDING` 작업을 하나씩 가져와 실행한다.

## 로컬 실행

기존 단일 프로세스 개발 실행은 유지한다.

```bash
./gradlew bootRun
```

web과 worker를 분리해서 확인하려면 터미널을 두 개 띄운다.

```bash
# terminal 1: web only
./gradlew bootRunWeb

# terminal 2: worker only
./gradlew bootRunWorker
```

## 운영 실행

같은 JAR를 두 프로세스로 실행한다.

```bash
./gradlew bootJar
```

```bash
# web
java -jar build/libs/precustomer-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod,web
```

```bash
# worker
java -jar build/libs/precustomer-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod,worker
```

환경변수로도 강제할 수 있다.

```bash
# web
export REPORT_PIPELINE_WORKER_ENABLED=false

# worker
export REPORT_PIPELINE_WORKER_ENABLED=true
```

## 필수 조건

- web과 worker는 같은 PostgreSQL DB를 바라봐야 한다.
- `docs/sql/07_report_job_queue.sql`이 적용되어 있어야 한다.
- worker 서버에는 Python 파이프라인 스크립트와 이미지 업로드 디렉토리에 접근할 수 있는 경로가 필요하다.
- worker 서버에는 `DEEPSEEK_API_KEY`, `GEMINI_API_KEY`, `NAVER_SHOPPING_CLIENT_ID`, `NAVER_SHOPPING_CLIENT_SECRET`이 설정되어야 한다.

## 아직 하지 않는 것

이 단계에서는 서비스별 DB 분리, 메시지 큐, API gateway를 도입하지 않는다. 먼저 worker 분리 배포로 리포트 생성 안정성과 재개/중지/재생성 흐름을 검증한 뒤 다음 단계로 이동한다.
