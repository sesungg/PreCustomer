# IntelliJ 실행 가이드

## 기본 설정

1. IntelliJ에서 `/Users/ssg/dev/PreCustomerReport`를 Gradle 프로젝트로 엽니다.
2. Project SDK와 Gradle JVM을 Java 17로 맞춥니다.
3. 로컬 PostgreSQL을 실행합니다.

기본 DB 설정:

```text
URL: jdbc:postgresql://localhost:5432/precustomer
User: postgres
Password: postgres
```

## Gradle Task 실행

IntelliJ Gradle tool window에서 아래 task를 실행할 수 있습니다.

```bash
bootRun              # api-gateway 실행
bootRunPublicWeb     # public-web만 실행
bootRunAdminWeb      # admin-web만 실행
bootRunGateway       # api-gateway 실행
bootRunWorker        # report-worker 실행
```

기본 실행 포트:

| 앱 | 포트 |
| --- | --- |
| Public Web | `8080` |
| Admin Web | `8081` |
| API Gateway | `8088` |
| Report Worker | HTTP 서버 없음 |

## Application Run Configuration

직접 Application Run Configuration을 만들 때는 아래처럼 설정합니다.

| 이름 | Main class | Program arguments | 주요 환경변수 |
| --- | --- | --- | --- |
| Public Web | `com.example.personareport.PublicWebApplication` | `--spring.profiles.active=local,public-web` | 필요 시 API key |
| Admin Web | `com.example.personareport.AdminWebApplication` | `--spring.profiles.active=local,admin-web` | `ADMIN_USERNAME=admin;ADMIN_PASSWORD=local-secret` |
| API Gateway | `com.example.personareport.gateway.ApiGatewayApplication` | `--spring.profiles.active=local,gateway` | `ADMIN_USERNAME=admin;ADMIN_PASSWORD=local-secret;GATEWAY_JWT_SECRET=dev-jwt-secret-for-intellij-32bytes;GATEWAY_PASSPORT_SECRET=dev-passport-secret-for-intellij-32bytes` |
| Report Worker | `com.example.personareport.ReportWorkerApplication` | `--spring.profiles.active=local,worker` | `DEEPSEEK_API_KEY=...;GEMINI_API_KEY=...` |

## 분리 실행

분리 구조를 확인하려면 아래 네 개를 각각 별도 Run Configuration으로 실행합니다.

```text
Public Web
Admin Web
API Gateway
Report Worker
```

브라우저는 gateway 기준 `http://localhost:8088`로 접속합니다.
