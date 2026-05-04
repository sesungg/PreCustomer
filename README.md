# CustomerPreview / 미리고객

CustomerPreview는 1인 창업자, 사이드 프로젝트 개발자, 스마트스토어 셀러, 전자책/강의 판매자, SaaS 운영자, 공모전 참가자를 위한 9,900원 베타 리포트 MVP입니다.

사용자가 아이디어, 앱 소개글, 판매페이지, 상세페이지, 공모전 기획안 등을 입력하면 AI 기반 가상고객 시뮬레이션으로 관심도, 사용/구매 의향, 이탈 이유, 불신 포인트, 개선 제안을 웹 리포트로 확인할 수 있습니다.

이 리포트는 실제 소비자 조사가 아니라 출시 전 참고용 사전 반응 진단입니다. 결과나 성과를 보장하지 않습니다.

## 기술 스택

- Java 17
- Spring Boot 3.x
- Gradle
- Spring Web
- Spring Data JPA
- Validation
- Thymeleaf
- H2 test/local optional
- PostgreSQL production profile
- Lombok

## 실행 방법

```bash
./gradlew test
./gradlew bootRun
```

브라우저에서 `http://localhost:8080`으로 접속합니다.

이 저장소의 `gradlew`는 로컬에 Gradle이 없으면 Gradle 배포 파일을 내려받아 실행합니다.

## PostgreSQL 실행 방법

기본 프로필은 `local`이며 Docker PostgreSQL을 사용합니다.

- JDBC URL 기본값: `jdbc:postgresql://localhost:5432/postgres`
- username 기본값: `postgres`
- password 기본값: `postgres`

```bash
./gradlew bootRun
```

다른 DB 이름을 쓰려면 환경변수로 바꿉니다.

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/precustomer \
DATABASE_USERNAME=postgres \
DATABASE_PASSWORD=postgres \
./gradlew bootRun
```

리포트는 `generated_reaction_report`, `persona_reaction`, `report_*` 컬렉션 테이블에 JPA로 저장됩니다.

## H2 실행 방법

H2가 필요하면 `h2` 프로필로 실행합니다.

- H2 콘솔: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:precustomer`
- username: `sa`
- password: 비워둠

```bash
./gradlew bootRun --args='--spring.profiles.active=h2'
```

## PostgreSQL 설정 방법

운영 기준 예시는 `prod` 프로필에 있습니다.

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/precustomer \
DATABASE_USERNAME=precustomer \
DATABASE_PASSWORD=change-me \
./gradlew bootRun --args='--spring.profiles.active=prod'
```

운영에서는 Flyway/Liquibase 같은 마이그레이션 도구를 추가하고 `ddl-auto=validate` 기준으로 관리하는 것을 권장합니다.

## 주요 URL

- `GET /`: 랜딩페이지
- `GET /orders/new`: 리포트 신청 폼
- `POST /orders`: 신청 저장
- `GET /orders/{id}/complete`: 신청 완료 페이지
- `GET /admin/orders`: 관리자 주문 목록
- `GET /admin/orders/{id}`: 관리자 주문 상세
- `POST /admin/orders/{id}/paid`: 입금 확인 처리
- `POST /admin/orders/{id}/generate`: AI 리포트 생성
- `POST /admin/orders/{id}/failed`: 실패 처리
- `GET /admin/reports/{reportId}`: 리포트 상세

## 현재 MVP 범위

- 랜딩페이지
- 신청 폼과 완료 페이지
- 관리자 주문 목록/상세
- 주문 상태 변경
- Mock AI 기반 리포트 생성
- 설정 기반 DeepSeek API 리포트 생성과 Mock fallback
- 웹 리포트 상세 화면
- 인쇄 친화적인 리포트 스타일

## 운영 전 반드시 추가해야 할 것

- 관리자 로그인
- 결제 연동
- 이메일 발송
- PDF 다운로드
- 개인정보 처리방침과 데이터 보관/삭제 정책
- DB 마이그레이션 도구

## 민감정보 입력 금지

사용자는 신청 폼에 다음 정보를 입력하면 안 됩니다.

- 개인정보
- 고객 DB
- 매출자료
- 계약서
- 내부 기획자료
- 병원, 금융, 법률 등 민감정보

## AI 리포트 안내

리포트 생성은 `AiReactionReportGenerator` 인터페이스를 통해 동작합니다.

지원 provider:

- `mock`: 로컬 Mock 리포트 생성기
- `deepseek`: DeepSeek API 기반 리포트 생성기

기본값은 `mock`입니다. `deepseek`로 설정하더라도 `DEEPSEEK_API_KEY`가 비어 있거나 API 호출, 응답 파싱에 실패하면 애플리케이션이 중단되지 않고 Mock 리포트로 fallback됩니다.

DeepSeek 모델 기본값은 `deepseek-v4-flash`입니다.

### DeepSeek 연동 방법

Mac zsh 기준 환경변수 설정:

```bash
export DEEPSEEK_API_KEY="발급받은_API_KEY"
```

현재 터미널에서 DeepSeek provider로 실행:

```bash
DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY" \
./gradlew bootRun --args='--app.ai.provider=deepseek'
```

Mock provider로 실행:

```bash
./gradlew bootRun --args='--app.ai.provider=mock'
```

API Key 없이 DeepSeek provider를 켜면 Mock으로 fallback됩니다.

```bash
unset DEEPSEEK_API_KEY
./gradlew bootRun --args='--app.ai.provider=deepseek'
```

설정 기본값:

```yaml
app:
  ai:
    provider: mock
  deepseek:
    base-url: https://api.deepseek.com
    model: deepseek-v4-flash
    api-key: ${DEEPSEEK_API_KEY:}
    temperature: 0.4
    max-tokens: 6000
```

### DeepSeek 테스트 방법

1. `DEEPSEEK_API_KEY`를 설정합니다.
2. `./gradlew bootRun --args='--app.ai.provider=deepseek'`로 실행합니다.
3. `/orders/new`에서 신청서를 작성합니다.
4. `/admin/orders/{id}`에서 리포트 생성을 누릅니다.
5. `/admin/reports/{reportId}`에서 페르소나 반응과 최종 진단이 표시되는지 확인합니다.

자동 테스트는 실제 DeepSeek 서버를 호출하지 않고 OpenAI 호환 `/chat/completions` 응답을 로컬 테스트 서버로 검증합니다.

리포트는 AI 기반 가상고객 시뮬레이션이며 실제 소비자 조사나 성과 보장을 의미하지 않습니다.

## 페르소나 데이터 적재

페르소나 데이터는 PostgreSQL 기준으로 적재합니다. H2로 100만 건 import를 실행하지 마세요.

- 기본 `local` 실행에서는 `src/main/resources/data/personas.sample.jsonl` 파일이 있으면 1,000개 샘플을 적재합니다.
- 대량 import는 `app.persona.import-enabled=true`일 때만 실행됩니다.
- 대량 import는 JPA `saveAll`이 아니라 `JdbcTemplate` batch insert를 사용합니다.
- JSONL 파일을 전체 메모리에 올리지 않고 한 줄씩 streaming으로 읽습니다.
- `sourceId`가 있으면 중복 방지 키로 사용합니다.
- `sourceId`가 없으면 `rawData.uuid` 또는 최상위 `uuid`를 `sourceId`로 사용합니다.
- 리포트 생성 시에는 `PersonaSampler`가 active 상태의 페르소나 중 필요한 수만 선택합니다. DeepSeek 프롬프트에는 선택된 10명만 전달합니다.
- NEMOTRON 데이터가 있으면 우선 사용하고, 부족하면 SEED 페르소나로 보충합니다.

Parquet 원본은 애플리케이션에서 직접 읽지 않습니다. Python 스크립트로 JSONL로 변환한 뒤 적재합니다.

```bash
pip install pandas pyarrow
python scripts/inspect_parquet.py /Users/ssg/dev/datasets/data/nemotron_personas_korea.parquet --rows 5
python scripts/convert_personas.py /Users/ssg/dev/datasets/data/nemotron_personas_korea.parquet ./data/personas.full.jsonl --limit 1000000
```

대량 import 실행:

```bash
./gradlew bootRun --args='--spring.profiles.active=local --app.persona.import-enabled=true --app.persona.import-path=./data/personas.full.jsonl'
```

import가 끝나면 다음 실행부터는 반드시 `import-enabled=false`로 되돌리세요. 기본값은 false입니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local --app.persona.import-enabled=false'
```

### PostgreSQL 인덱스

대량 import 실행 시 애플리케이션이 아래 인덱스를 `create index if not exists`로 생성합니다. SQL 파일은 `docs/sql/persona_profile_indexes.sql`에 있습니다.

```sql
create unique index if not exists ux_persona_profile_source_id
    on persona_profile (source_id)
    where source_id is not null;

create index if not exists ix_persona_profile_source
    on persona_profile (source);

create index if not exists ix_persona_profile_active
    on persona_profile (active);

create index if not exists ix_persona_profile_age_group
    on persona_profile (age_group);

create index if not exists ix_persona_profile_province
    on persona_profile (province);

create index if not exists ix_persona_profile_district
    on persona_profile (district);

create index if not exists ix_persona_profile_occupation
    on persona_profile (occupation);

create index if not exists ix_persona_profile_source_active_province
    on persona_profile (source, active, province);

create index if not exists ix_persona_profile_source_active_age_group
    on persona_profile (source, active, age_group);
```
