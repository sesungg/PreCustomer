# Implementation Plan

## Overview

PreCustomerReport 프로젝트는 기존 PreCustomer MVP를 기반으로, persona_profile(약 100만 명)을 활용한 AI 기반 가상고객 반응 리포트 서비스를 고도화한다.

기존 PreCustomer 프로젝트는 이미 랜딩페이지, 신청 폼, 관리자 페이지, Mock/DeepSeek 리포트 생성, 리포트 상세 페이지까지 구현된 상태다. 이번 작업의 핵심은 **persona_profile 기반 페르소나 선별 알고리즘 고도화**와 **상세페이지 URL 분석 기능 추가**다.

현재 PersonaSampler는 키워드 기반 단순 랜덤 윈도우 샘플링만 수행한다. 이를 적합도(fit)와 다양성(diversity)을 함께 고려하는 방식으로 개선해야 한다. 또한 사용자가 URL을 입력하면 크롤링을 시도하고, 실패 시 대체 정보로 처리하는 상세페이지 분석 파이프라인을 추가한다.

## Types

기존 엔티티/레코드 타입은 유지하고, 새로운 DTO와 enum만 추가한다.

### 신규 타입

**PageAnalysisResult (레코드)**
- `pageUrl: String` — 원본 URL
- `pageTitle: String` — 크롤링한 페이지 제목 (nullable)
- `pageDescription: String` — 크롤링한 페이지 설명/본문 요약 (nullable)
- `extractedPrice: String` — 추출한 가격 정보 (nullable)
- `extractedImages: List<String>` — 추출한 대표 이미지 URL 목록
- `analysisStatus: PageAnalysisStatus` — 분석 상태
- `errorMessage: String` — 실패 시 오류 메시지 (nullable)

**PageAnalysisStatus (enum)**
- `SUCCESS` — URL 크롤링 성공
- `BLOCKED` — 크롤링 차단됨 (로그인/봇 차단 등)
- `INVALID_URL` — 유효하지 않은 URL
- `NOT_PROVIDED` — URL 미제공
- `FALLBACK` — URL 실패 후 대체 정보 사용

**PersonaSelectionStrategy (enum)**
- `BALANCED` — 적합도 + 다양성 균형 (기본값)
- `FIT_FIRST` — 적합도 우선
- `DIVERSITY_FIRST` — 다양성 우선

**PersonaSelectionResult (레코드)**
- `selectedPersonas: List<PersonaProfile>` — 선별된 페르소나 목록
- `selectionStrategy: PersonaSelectionStrategy` — 사용된 전략
- `fitScores: Map<Long, Double>` — 페르소나 ID별 적합도 점수
- `diversityMetrics: Map<String, Integer>` — 다양성 지표 (직업군별, 연령대별 분포)

### 기존 타입 변경

**OrderRequest (레코드) — pageUrl 필드 활용 강화**
- 변경 없음, 기존 `pageUrl` 필드를 URL 분석 파이프라인에서 활용

**ReactionReportOrder (엔티티) — 변경 없음**
- 기존 `pageUrl`, `priceText` 필드를 URL 분석 결과로 업데이트할 수 있도록 `updateFromPageAnalysis()` 메서드 추가

## Files

기존 파일 구조를 유지하며, 새로운 파일만 추가하고 일부 기존 파일을 수정한다.

### 신규 생성 파일

1. **`src/main/java/com/example/personareport/report/analysis/PageAnalysisService.java`**
   - URL 크롤링 및 페이지 분석 서비스
   - Jsoup을 사용한 HTML 파싱
   - 메타 태그, OG 태그, 본문 텍스트 추출
   - 가격 정보 패턴 매칭
   - 크롤링 차단/실패 처리

2. **`src/main/java/com/example/personareport/report/analysis/PageAnalysisStatus.java`**
   - 페이지 분석 상태 enum

3. **`src/main/java/com/example/personareport/report/analysis/PageAnalysisResult.java`**
   - 페이지 분석 결과 DTO

4. **`src/main/java/com/example/personareport/report/analysis/PageAnalysisConfig.java`**
   - 페이지 분석 설정 (타임아웃, User-Agent 등)

5. **`src/main/java/com/example/personareport/report/selection/PersonaSelectionStrategy.java`**
   - 페르소나 선별 전략 enum

6. **`src/main/java/com/example/personareport/report/selection/PersonaSelectionResult.java`**
   - 페르소나 선별 결과 DTO

7. **`src/main/java/com/example/personareport/report/selection/PersonaSelector.java`**
   - 향상된 페르소나 선별 서비스
   - 적합도 점수 계산 (직업, 연령대, 관심사, 소비 성향 기반)
   - 다양성 보장 샘플링 (계층화 샘플링)
   - 핵심 타겟, 인접 타겟, 회의적 타겟 분류

8. **`src/main/java/com/example/personareport/report/selection/PersonaFitCalculator.java`**
   - 페르소나-주문 간 적합도 점수 계산기
   - occupation, interests, painPoints, buyingSensitivity 기반 매칭

### 수정 파일

1. **`build.gradle`** — Jsoup 의존성 추가
2. **`src/main/java/com/example/personareport/report/ai/PersonaSampler.java`** — PersonaSelector를 사용하도록 리팩토링
3. **`src/main/java/com/example/personareport/report/service/ReportService.java`** — URL 분석 결과를 리포트 생성에 활용
4. **`src/main/java/com/example/personareport/order/service/OrderService.java`** — 주문 생성 시 URL 분석 파이프라인 추가
5. **`src/main/java/com/example/personareport/order/domain/ReactionReportOrder.java`** — `updateFromPageAnalysis()` 메서드 추가
6. **`src/main/resources/application.yml`** — 페이지 분석 설정 추가

## Functions

### 신규 함수

**PageAnalysisService**
- `analyze(String pageUrl): PageAnalysisResult` — URL 분석 메인 메서드
- `tryCrawl(String url): PageAnalysisResult` — 실제 크롤링 시도
- `extractMetaTags(Document doc): Map<String, String>` — 메타 태그 추출
- `extractPrice(String text): String` — 가격 패턴 추출
- `extractImages(Document doc): List<String>` — 이미지 URL 추출
- `buildFallbackResult(String url, String reason): PageAnalysisResult` — 실패 시 fallback 결과 생성

**PersonaSelector**
- `selectFor(ReactionReportOrder order, int size): PersonaSelectionResult` — 향상된 페르소나 선별
- `calculateFitScore(PersonaProfile profile, ReactionReportOrder order): double` — 적합도 점수 계산
- `stratifiedSample(List<ScoredPersona> candidates, int size): List<PersonaProfile>` — 계층화 샘플링
- `classifyPersona(PersonaProfile profile, ReactionReportOrder order): PersonaCategory` — 핵심/인접/회의적 분류

**PersonaFitCalculator**
- `calculate(PersonaProfile profile, ReactionReportOrder order): double` — 종합 적합도 점수
- `occupationFit(String occupation, TargetType targetType): double` — 직업 적합도
- `ageGroupFit(String ageGroup, TargetType targetType): double` — 연령대 적합도
- `interestFit(String interests, String targetDescription): double` — 관심사 적합도
- `buyingSensitivityFit(String sensitivity, String priceText): double` — 소비 성향 적합도

### 수정 함수

**PersonaSampler.sampleFor()**
- 기존: 키워드 기반 랜덤 윈도우 샘플링
- 변경: PersonaSelector를 호출하도록 위임, PersonaSelector가 없으면 기존 로직 유지

**ReportService.generateReport()**
- 기존: 단순 Mock/DeepSeek 생성
- 변경: URL 분석 결과가 있으면 리포트 생성 시 추가 컨텍스트로 활용

**OrderService.createOrder()**
- 기존: 단순 저장
- 변경: pageUrl이 있으면 URL 분석 파이프라인 실행, 분석 결과를 order에 반영

## Classes

### 신규 클래스

**PageAnalysisService** (`report/analysis` 패키지)
- `@Service`, `@RequiredArgsConstructor`
- 메서드: `analyze()`, `tryCrawl()`, `extractMetaTags()`, `extractPrice()`, `extractImages()`, `buildFallbackResult()`
- 의존성: `RestTemplate` 또는 `Jsoup.connect()`

**PersonaSelector** (`report/selection` 패키지)
- `@Component`, `@RequiredArgsConstructor`
- 메서드: `selectFor()`, `calculateFitScore()`, `stratifiedSample()`, `classifyPersona()`
- 의존성: `PersonaProfileRepository`, `PersonaFitCalculator`

**PersonaFitCalculator** (`report/selection` 패키지)
- `@Component`
- 메서드: `calculate()`, `occupationFit()`, `ageGroupFit()`, `interestFit()`, `buyingSensitivityFit()`

### 수정 클래스

**PersonaSampler** — PersonaSelector로 위임하는 브릿지 역할로 변경
**ReactionReportOrder** — `updateFromPageAnalysis()` 메서드 추가
**OrderService** — URL 분석 파이프라인 통합
**ReportService** — URL 분석 결과 활용

## Dependencies

Jsoup 의존성을 build.gradle에 추가한다.

```
implementation 'org.jsoup:jsoup:1.18.1'
```

Jsoup은 HTML 파싱, 메타 태그 추출, OG 태그 파싱, 본문 텍스트 추출에 사용된다. RestTemplate 대신 Jsoup을 선택한 이유는:
- HTML 문서 파싱에 특화됨
- User-Agent 설정, 타임아웃 설정, 리퍼러 설정 등 크롤링에 필요한 기능 내장
- CSS selector로 메타 태그와 OG 태그 추출이 용이함

## Testing

### 신규 테스트 파일

1. **`src/test/java/com/example/personareport/PageAnalysisServiceTest.java`**
   - URL 분석 성공/실패 시나리오 테스트
   - Mock URL 응답을 사용한 크롤링 테스트
   - Fallback 처리 테스트

2. **`src/test/java/com/example/personareport/PersonaSelectorTest.java`**
   - 적합도 점수 계산 테스트
   - 다양성 보장 샘플링 테스트
   - 편향 방지 테스트 (특정 직업군 과다 선별 방지)

### 기존 테스트 수정

**PersonaSamplerTest** — PersonaSelector 통합 후에도 기존 동작 보장 확인
**OrderReportFlowTest** — URL 분석 파이프라인 통합 후 플로우 테스트

## Implementation Order

기존 PreCustomer 코드가 이미 PreCustomerReport로 복사된 상태에서, 다음 순서로 구현한다.

1. **build.gradle 수정** — Jsoup 의존성 추가
2. **PageAnalysisStatus enum 생성** — 페이지 분석 상태 정의
3. **PageAnalysisResult 레코드 생성** — 페이지 분석 결과 DTO
4. **PageAnalysisConfig 클래스 생성** — 페이지 분석 설정
5. **PageAnalysisService 클래스 생성** — URL 크롤링 및 분석 서비스
6. **PersonaSelectionStrategy enum 생성** — 페르소나 선별 전략
7. **PersonaSelectionResult 레코드 생성** — 페르소나 선별 결과 DTO
8. **PersonaFitCalculator 클래스 생성** — 적합도 점수 계산기
9. **PersonaSelector 클래스 생성** — 향상된 페르소나 선별 서비스
10. **ReactionReportOrder 수정** — `updateFromPageAnalysis()` 메서드 추가
11. **OrderService 수정** — URL 분석 파이프라인 통합
12. **ReportService 수정** — URL 분석 결과 활용
13. **PersonaSampler 수정** — PersonaSelector 위임
14. **application.yml 수정** — 페이지 분석 설정 추가
15. **테스트 파일 생성 및 기존 테스트 수정**
16. **./gradlew test 실행** — 전체 테스트 검증
