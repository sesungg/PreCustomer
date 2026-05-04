# AGENTS.md

## Project
This project is a Spring Boot MVP for "미리고객 / CustomerPreview".

The service provides an AI-based virtual customer reaction report.
It is not a real market research product and must not claim guaranteed sales improvement.

## Product Positioning
- Korean name: 미리고객
- English name: CustomerPreview
- Core product: 가상고객 100명 반응 리포트
- Target users:
  - 1인 창업자
  - 사이드 프로젝트 개발자
  - 스마트스토어 셀러
  - 전자책/강의 판매자
  - SaaS 운영자
  - 공모전 참가자
- Price assumption: beta report 9,900 KRW

## Development Goal
Build a small MVP, not a full SaaS.

The MVP should support:
1. Landing page
2. Report request form
3. Request completion page
4. Admin order list
5. Admin order detail
6. Mock AI report generation
7. Report detail page

Do not implement these unless explicitly requested:
- User signup
- User login
- Subscription
- Automatic payment
- Team features
- Complex dashboard
- Charts
- Social login
- Email sending
- PDF download

## Tech Stack
- Java 17
- Spring Boot 3.x
- Gradle
- Spring Web
- Spring Data JPA
- Validation
- Thymeleaf
- H2 for local development
- PostgreSQL for production
- Lombok allowed
- Simple CSS or Tailwind CDN allowed

## Code Style
- Keep code simple and maintainable for a backend developer.
- Prefer clear package separation.
- Avoid over-engineering.
- Use meaningful class, method, and variable names.
- Add TODO comments for production-required features.
- Do not introduce unnecessary dependencies.

## Package Structure
Use this structure:

com.example.personareport
- order
  - domain
  - repository
  - service
  - web
  - dto
- report
  - domain
  - repository
  - service
  - web
  - ai
  - dto
- common
  - entity
  - exception

## UI Rules
- Use Korean text for the service UI.
- Do not use emojis.
- Keep the design minimal and trustworthy.
- Mobile responsive layout is required.
- Admin pages should be table-based and simple.
- Report pages should look good when printed.

## Business Copy Rules
Never say:
- 실제 고객 조사
- 매출 보장
- 구매 전환 보장
- 100% 검증

Prefer:
- AI 기반 가상고객 시뮬레이션
- 사전 반응 진단
- 출시 전 참고용 리포트
- 한국형 가상고객 반응 분석

## Privacy Rules
The UI must warn users not to enter:
- 개인정보
- 고객 DB
- 매출자료
- 계약서
- 내부 기획자료
- 병원, 금융, 법률 등 민감정보

## Verification
Before finishing a task, check:
- ./gradlew test
- ./gradlew bootRun if possible
- Main user flow:
  - GET /
  - GET /orders/new
  - POST /orders
  - GET /orders/{id}/complete
  - GET /admin/orders
  - GET /admin/orders/{id}
  - POST /admin/orders/{id}/generate
  - GET /admin/reports/{reportId}

## Response Rule
After making changes, summarize:
1. What changed
2. Which files changed
3. How to run or verify
4. Any remaining TODOs