package com.example.personareport.report.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** DeepSeek API 호출 전용 서비스. DB 트랜잭션 없이 외부 API만 호출. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {

    @Value("${app.deepseek.api-key:${DEEPSEEK_API_KEY:}}")
    private String apiKey;

    private final DeepSeekFeignClient deepSeek;
    private final ObjectMapper objectMapper;

    /**
     * DeepSeek API 호출. auth 헤더 자동 설정. JSON 응답 파싱 + 코드블록 제거 + truncation 확인.
     * Python call_llm() 동일 로직.
     */
    public Map<String, Object> callDeepSeek(String systemPrompt, String userPrompt,
                                             String model, double temperature,
                                             int maxTokens, String thinkingMode) {
        String auth = "Bearer " + resolveApiKey();
        Map<String, Object> thinking = "enabled".equals(thinkingMode)
                ? Map.of("type", "enabled")
                : Map.of("type", "disabled");

        var req = new DeepSeekFeignClient.DeepSeekRequest(
                model, List.of(
                        new DeepSeekFeignClient.Message("system", systemPrompt),
                        new DeepSeekFeignClient.Message("user", userPrompt)),
                Map.of("type", "json_object"), temperature, maxTokens, false, thinking);

        RuntimeException lastError = null;
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String rawResponse = deepSeek.chatCompletion(auth, req);
                JsonNode root = objectMapper.readTree(rawResponse);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    throw new RuntimeException("DeepSeek 응답에 choices가 없습니다. body="
                            + compact(rawResponse, 1000));
                }
                JsonNode choice = choices.get(0);
                String content = choice.path("message").path("content").asText("");

                if ("length".equals(choice.path("finish_reason").asText(""))) {
                    throw new RuntimeException("DeepSeek 응답이 maxTokens에 의해 잘렸습니다.");
                }

                return parseJsonContent(content);
            } catch (Exception e) {
                RuntimeException wrapped = e instanceof RuntimeException re
                        ? re
                        : new RuntimeException(e.getMessage(), e);
                lastError = wrapped;
                if (attempt < maxRetries) {
                    log.warn("DeepSeek 호출 실패 attempt={}/{}: {}", attempt, maxRetries, wrapped.getMessage());
                    sleepBeforeRetry(attempt, wrapped);
                }
            }
        }
        throw new RuntimeException("DeepSeek 호출 실패: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
    }

    public boolean isTransientFailure(Throwable e) {
        String message = collectMessages(e).toLowerCase();
        return message.contains("read timed out")
                || message.contains("timeout")
                || message.contains("응답에 choices가 없습니다")
                || message.contains("error while extracting response")
                || message.contains("service is too busy")
                || message.contains("service_unavailable")
                || message.contains("internal_error")
                || message.contains("internal server error")
                || message.contains("503")
                || message.contains("500")
                || message.contains("too many requests")
                || message.contains("429");
    }

    private String compact(String value, int maxLength) {
        if (value == null) return "";
        String compacted = value.replaceAll("\\s+", " ").trim();
        return compacted.length() > maxLength ? compacted.substring(0, maxLength) : compacted;
    }

    private String collectMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        while (cur != null) {
            if (cur.getMessage() != null) sb.append(cur.getMessage()).append('\n');
            if (cur instanceof FeignException feign && feign.contentUTF8() != null) {
                sb.append(feign.contentUTF8()).append('\n');
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonContent(String rawContent) {
        String content = cleanJsonString(rawContent);
        try {
            return objectMapper.readValue(content, Map.class);
        } catch (Exception e) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readValue(content.substring(start, end + 1), Map.class);
                } catch (Exception ignored) {}
            }
            log.error("DeepSeek JSON 파싱 실패. 원본 응답(처음 500자): {}",
                    content.substring(0, Math.min(500, content.length())));
            throw new RuntimeException("DeepSeek JSON 파싱 실패", e);
        }
    }

    private void sleepBeforeRetry(int attempt, Throwable error) {
        try {
            long delayMillis = isTransientFailure(error)
                    ? Math.min(30000L, 5000L * (1L << (attempt - 1)))
                    : 2000L * attempt;
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DeepSeek 재시도 대기 중 인터럽트", e);
        }
    }

    /** 제품 타겟 프로필 생성 호출. */
    public Map<String, Object> callTargetProfile(String payloadJson, String model, double temperature,
                                                  int maxTokens, String thinkingMode) {
        String systemPrompt = buildTargetProfileSystemPrompt();
        return callDeepSeek(systemPrompt, payloadJson, model, temperature, maxTokens, thinkingMode);
    }

    /** 페르소나 반응 생성 호출 (batch). 결과는 {results: [...]} 형태. */
    public Map<String, Object> callReactions(String productPayloadJson, String personasJson,
                                              String model, double temperature,
                                              int maxTokens, String thinkingMode) {
        String systemPrompt = buildReactionSystemPrompt();
        String userPrompt = "{\"product\": " + productPayloadJson + ", \"personas\": " + personasJson + "}";
        return callDeepSeek(systemPrompt, userPrompt, model, temperature, maxTokens, thinkingMode);
    }

    /** 최종 리포트 생성 호출. */
    public Map<String, Object> callFinalReport(String aggregateJson, String model, double temperature,
                                                int maxTokens, String thinkingMode) {
        String systemPrompt = buildFinalReportSystemPrompt();
        return callDeepSeek(systemPrompt, aggregateJson, model, temperature, maxTokens, thinkingMode);
    }

    // ── Prompt builders ───────────────────────────────────────────

    private String buildTargetProfileSystemPrompt() {
        return """
            너는 상품 상세페이지를 분석해 '가상 고객 100명 샘플링용 상품 타겟 프로필'을 만드는 분류기다.

            목표:
            - 상품 상세페이지 텍스트, 가격, 고객 질문, 그리고 Vision 모델이 직접 분석한 이미지 분석 결과를 읽고 어떤 고객군을 뽑아야 리포트가 유용할지 정의한다.
            - 상품마다 Python 코드를 고치지 않기 위해, 모든 상품별 판단은 이 JSON에 담는다.

            절대 규칙:
            - 출력은 JSON 객체 하나만 반환한다. 마크다운 금지.
            - 아래 템플릿의 키 이름을 바꾸지 마라.
            - 없는 근거를 사실처럼 만들지 마라.
            - order.shippingPolicyText가 있으면 가격 민감도와 구매 저항 요인 판단에 반영하되, 조건부/멤버십 무료배송을 완전 무료배송처럼 단정하지 마라.
            - 이미지 분석 결과에 보이는 구성품, 원산지, 용량, 소재, 기능, 주의사항, 리뷰/평점 문구를 타겟 프로필과 purchaseDrivers/purchaseBarriers에 반영한다.
            - 성별/연령 가중치는 고정관념이 아니라 상품 맥락에 따른 약한 prior로만 둔다.
            - 특정 성별/연령을 완전히 배제하지 마라.
            - keyword는 보조 신호다. 키워드만으로 고객을 뽑게 만들지 마라.
            - samplingStrategy에는 반드시 LOW_FIT_CONTROL과 STRATIFIED_RANDOM을 포함한다.

            selectionWeights:
            - 아래 10개 키를 모두 포함한다.
            - 값은 0.0~2.0 사이 숫자다.
            - 매우 관련 있으면 1.2~1.8, 보통이면 0.7~1.1, 거의 무관하면 0.0~0.4.
            - 키: digitalAffinity, priceSensitivity, trustSensitivity, convenienceNeed, qualitySensitivity, noveltyAcceptance, localAffinity, familyDecision, healthSafetySensitivity, reviewDependency

            demographicPriors:
            - ageGroups에는 다음 키를 모두 포함한다: 10대 이하, 20대, 30대, 40대, 50대, 60대, 70대 이상
            - genders에는 다음 키를 모두 포함한다: MALE, FEMALE, UNKNOWN
            - 값은 0.5~1.5 사이 숫자다. 보통 0.8~1.2 범위 안에서 보수적으로 설정한다.

            samplingStrategy:
            - 아래 그룹을 모두 포함한다: CORE_TARGET, ADJACENT_TARGET, TRUST_PRICE_SKEPTIC, LOW_FIT_CONTROL, STRATIFIED_RANDOM
            - ratio 합은 1.0이어야 한다.
            - 기본 추천: CORE_TARGET 0.35, ADJACENT_TARGET 0.20, TRUST_PRICE_SKEPTIC 0.20, LOW_FIT_CONTROL 0.15, STRATIFIED_RANDOM 0.10.

            출력 템플릿:
            {
              "productCategory": "UNKNOWN",
              "productType": "UNKNOWN",
              "productName": "UNKNOWN",
              "targetSummary": "UNKNOWN",
              "coreKeywords": [],
              "exclusionKeywords": [],
              "purchaseDrivers": [],
              "purchaseBarriers": [],
              "audienceHypotheses": [
                {"group": "CORE_TARGET", "name": "UNKNOWN", "priority": "HIGH", "description": "UNKNOWN", "whyRelevant": "UNKNOWN"}
              ],
              "comparisonAudiences": [
                {"group": "LOW_FIT_CONTROL", "name": "UNKNOWN", "description": "UNKNOWN", "whyNeededForReport": "UNKNOWN"}
              ],
              "selectionWeights": {
                "digitalAffinity": 1.0, "priceSensitivity": 1.0, "trustSensitivity": 1.0,
                "convenienceNeed": 1.0, "qualitySensitivity": 1.0, "noveltyAcceptance": 1.0,
                "localAffinity": 1.0, "familyDecision": 1.0, "healthSafetySensitivity": 1.0,
                "reviewDependency": 1.0
              },
              "demographicPriors": {
                "ageGroups": {"10대 이하": 1.0, "20대": 1.0, "30대": 1.0, "40대": 1.0, "50대": 1.0, "60대": 1.0, "70대 이상": 1.0},
                "genders": {"MALE": 1.0, "FEMALE": 1.0, "UNKNOWN": 1.0}
              },
              "samplingStrategy": {
                "CORE_TARGET": {"ratio": 0.35, "description": "핵심 구매 가능성이 높은 고객군"},
                "ADJACENT_TARGET": {"ratio": 0.20, "description": "주변 구매 가능 고객군"},
                "TRUST_PRICE_SKEPTIC": {"ratio": 0.20, "description": "가격/신뢰/후기/안전성을 따지는 회의층"},
                "LOW_FIT_CONTROL": {"ratio": 0.15, "description": "비타겟 또는 낮은 적합도의 비교군"},
                "STRATIFIED_RANDOM": {"ratio": 0.10, "description": "층화 랜덤 비교군"}
              },
              "messageAngles": [],
              "reportFocusPoints": [],
              "confidence": 0.7
            }
            """.stripIndent().trim();
    }

    private String buildReactionSystemPrompt() {
        return """
            너는 상품 상세페이지에 대한 한국 소비자 반응을 생성하는 분석가다.

            목표:
            - 선택된 페르소나가 실제로 이 상세페이지를 본 것처럼 반응을 작성한다.
            - 과하게 긍정적으로 쓰지 않는다.
            - selectionGroup의 역할을 반영한다.
              - CORE_TARGET: 구매 가능성이 높은 핵심층
              - ADJACENT_TARGET: 주변 타겟층. 관심은 있으나 확신은 낮을 수 있음
              - TRUST_PRICE_SKEPTIC: 가격, 후기, 성분, 정품, 안전성 때문에 망설이는 층
              - LOW_FIT_CONTROL: 비타겟/낮은 적합도 비교군. 구매 의향이 낮아야 자연스러움
              - STRATIFIED_RANDOM: 일반 비교군. 긍정/중립/부정이 섞일 수 있음

            작성 원칙:
            - 반드시 페르소나 정보, 상품 상세페이지 정보, 그리고 Vision 모델이 직접 분석한 이미지 분석 결과를 연결해서 판단한다.
            - 없는 사실을 만들지 않는다.
            - product.sourceEvidence를 반드시 확인하고 URL, 이미지, 네이버 쇼핑, LLM 추론 출처를 섞지 않는다.
            - product.sourceEvidence.userInput.shippingPolicyText가 있으면 배송비 판단의 최우선 근거다. 입력 문구를 그대로 해석하되, 조건부 무료배송/멤버십 무료배송은 "조건 충족 시"로 표현하고 완전 무료배송처럼 단정하지 않는다.
            - product.sourceEvidence.url.screenshotPrimary가 true이면 상세페이지 캡처 이미지가 1차 근거다. URL 본문이 아니라 imageAnalyses에서 보이는 정보만 객관 근거로 사용한다.
            - URL 크롤링 상태가 FALLBACK/FAILED/MISSING이면 리뷰나 평점이 "없다"고 단정하지 말고 "확인되지 않았다"고만 표현한다.
            - URL rawMetaJson 또는 sourceEvidence.url에 reviewCount/ratingScore가 있으면 후기/평점 근거로 최우선 반영한다.
            - 캡처 이미지의 visibleText/imageSummary에 리뷰 수나 평점이 보이면 image 근거로 반드시 반영한다. 단, 이미지에 보이지 않는 리뷰/평점은 추론하지 않는다.
            - image.items의 visibleClaims, visiblePrices, visibleUsageInstructions, visualPurchaseDrivers, visualPurchaseBarriers, safetyOrComplianceNotes에 보이는 핵심 문구, 구성품, 원산지, 용량, 소재, 기능, 주의사항을 상세페이지 반응에 반영한다.
            - 가격 경쟁력과 원산지/식품 안전 신뢰도는 분리한다. 수입산이라서 신뢰도는 낮아질 수 있지만, 그 이유만으로 priceAcceptanceScore를 낮추지 않는다.
            - priceAcceptanceScore는 표시 가격, 중량, 배송비 포함 실구매가, 네이버 가격 위치를 기준으로 판단한다.
            - 배송비가 미확인인 경우 가격을 과도하게 낮게 평가하지 말고 "배송비 확인 필요"를 missingInformation 또는 concerns에 넣는다. 배송비 입력값이 있으면 네이버 쇼핑 데이터로 다른 배송 조건을 덮어쓰지 않는다.
            - 네이버 쇼핑 비교는 보조 근거다. 원산지, 부위, 중량, 냉장/냉동, 배송비 조건이 다른 후보를 근거로 가격이 비싸다고 단정하지 않는다.
            - 네이버 priceAnalysis.priceLevel이 LOW이고 기준가가 중앙값보다 낮다면, 배송비가 비싸다는 명확한 근거가 없는 한 가격 경쟁력은 긍정적으로 평가한다.
            - 냉동/신선식품의 단순 변심 반품 제한은 일반적인 카테고리 조건으로 다루고 치명적 리스크처럼 과장하지 않는다.
            - 이미지 기반 설명 이해도는 이미지에 실제로 보이는 정보만 기준으로 판단한다. URL 본문 정보와 이미지 정보를 구분한다.
            - 의학적 효과를 단정하지 않는다.
            - 건강보조식품은 개인차, 섭취 주의, 안전성 우려를 현실적으로 다룬다.
            - 구매 의향 점수는 그룹별 역할과 페르소나 성향을 반영한다.
            - LOW_FIT_CONTROL이 높은 구매의향을 보이면 안 된다.
            - TRUST_PRICE_SKEPTIC은 완전 비타겟이 아니라 망설이는 사람이어야 한다.
            - 모든 응답을 비슷한 문장으로 반복하지 않는다.

            출력 규칙:
            - JSON 객체 하나만 반환한다.
            - 최상위 키는 results.
            - results 길이는 입력 personas 길이와 같아야 한다.
            - 각 result의 personaProfileId는 입력값과 일치해야 한다.
            - 점수는 0~100 정수다.
            - sentiment는 POSITIVE, NEUTRAL, NEGATIVE, MIXED 중 하나다.
            - decisionStatus는 BUY, CONSIDER, HESITATE, NOT_BUY 중 하나다.

            출력 템플릿:
            {
              "results": [
                {
                  "personaProfileId": 123,
                  "reactionSelectedPersonaId": 456,
                  "selectionGroup": "CORE_TARGET",
                  "purchaseIntentScore": 70,
                  "targetFitScore": 75,
                  "priceAcceptanceScore": 65,
                  "trustScore": 60,
                  "detailPageClarityScore": 70,
                  "sentiment": "MIXED",
                  "decisionStatus": "CONSIDER",
                  "firstImpression": "상세페이지를 처음 봤을 때의 반응",
                  "likelyReaction": "이 사람이 실제로 어떤 판단을 할지",
                  "priceReaction": "가격/할인/용량에 대한 반응",
                  "trustReviewReaction": "후기/평점/브랜드/정품/성분 신뢰에 대한 반응",
                  "detailPageFeedback": "상세페이지 문구/정보/이미지에 대한 피드백",
                  "positivePoints": [],
                  "concerns": [],
                  "missingInformation": [],
                  "purchaseBarriers": [],
                  "persuasionMessages": [],
                  "recommendedDetailPageFixes": [],
                  "segmentLabel": "이 페르소나를 설명하는 짧은 라벨",
                  "representativeQuote": "실제 소비자처럼 말한 한 문장"
                }
              ]
            }
            """.stripIndent().trim();
    }

    private String buildFinalReportSystemPrompt() {
        return """
            너는 상세페이지에 대한 가상 고객 N명 반응을 종합해 판매자/마케터가 바로 개선에 사용할 수 있는 최종 리포트를 작성하는 분석가다.

            목표:
            - N명의 개별 반응을 단순 나열하지 말고, 구매 의향/타겟 적합성/가격 저항/신뢰 저항/상세페이지 개선점으로 종합한다.
            - CORE_TARGET, ADJACENT_TARGET, TRUST_PRICE_SKEPTIC, LOW_FIT_CONTROL, STRATIFIED_RANDOM의 역할 차이를 반영한다.
            - 판매자가 상세페이지를 어떻게 고쳐야 하는지 명확한 우선순위를 제시한다.
            - 결과는 실제 서비스 리포트로 보여줄 수 있을 정도로 구체적이어야 한다.

            중요한 안전 규칙:
            - 건강보조식품, 식품, 의료, 안전 관련 상품에서는 효능이나 안전성을 단정하지 마라.
            - 개별 반응에 포함된 의학적 효능/안전성/인증 관련 단정 문장은 그대로 인용하지 마라.
            - "부작용이 없다", "효과가 입증됐다", "대부분 안전하다", "인증된 시설에서 생산된다", "정기적으로 성분검사를 한다" 같은 단정 문장은 금지한다.
            - 상세페이지에 명시되지 않은 인증, 제조시설, 성분검사, 전문가 의견, 임상 근거, 환불 정책, 안전성 근거를 사실처럼 쓰지 마라.
            - 필요한 경우 "이런 정보가 상세페이지에 추가되면 신뢰를 높일 수 있다", "섭취 주의사항이 명확하면 좋다", "전문가 상담이 필요한 사용자를 위한 안내가 필요하다"처럼 개선 제안으로만 표현하라.
            - 질환자, 임산부, 약 복용자, 고령자 관련 내용은 반드시 "섭취 전 주의사항 확인 필요", "전문가 상담 필요" 수준으로 표현하라.

            작성 규칙:
            - 한국어로 작성한다.
            - 과장된 광고 문구가 아니라 분석 리포트 문체로 작성한다.
            - 숫자와 비율을 적극 활용한다.
            - aggregate.sourceEvidence를 기준으로 URL 추출 데이터, 이미지 분석 데이터, 네이버 쇼핑 비교 데이터, LLM 추론을 구분한다.
            - aggregate.sourceEvidence.userInput.shippingPolicyText가 있으면 배송비 판단의 최우선 근거로 반드시 반영한다. 조건부 무료배송, 멤버십 무료배송, 유료배송, 완전 무료배송의 차이를 유지하고 실구매가는 계산 가능할 때만 단정한다.
            - aggregate.sourceEvidence.url.screenshotPrimary가 true이면 상세페이지 전체 캡처 이미지를 1차 근거로 설명하고, URL 크롤링 실패를 리포트 실패로 취급하지 않는다.
            - 가격 요약에서는 원산지 신뢰도와 가격 경쟁력을 분리한다. 수입산 신뢰 우려는 trustSummary/riskSummary에서 다루고 priceSummary의 직접 감점 근거로 쓰지 않는다.
            - 배송비 포함 실구매가가 확인되면 그 값을 가격 판단의 우선 근거로 쓴다. 배송비가 조건부이거나 미확인인 경우 표시가 기준 판단과 조건/불확실성을 함께 적는다.
            - 네이버 쇼핑 비교에 배송비/리뷰/평점이 없으면 그 한계를 명시하고, 조건이 다른 비교 상품으로 가격 점수를 강하게 낮추지 않는다.
            - URL 크롤링이 실패했으면 "후기 부재"라고 쓰지 말고 "URL 리뷰/평점 확인 실패"라고 표현한다. reviewCount/ratingScore가 있으면 반드시 반영한다.
            - 캡처 이미지에 리뷰 수나 평점이 보이면 이미지 근거로 반드시 반영한다. 보이지 않으면 "캡처 기준 미확인"으로만 쓴다.
            - detailPageSummary와 개선안에는 image.items에서 확인되는 핵심 문구, 구성품, 원산지, 용량, 소재, 기능, 주의사항을 근거로 반영한다. 없으면 누락 정보로 분류하되 사실처럼 채우지 않는다.
            - 냉동/신선식품의 단순 변심 반품 제한은 카테고리 일반 제약으로 분류하고 과도한 단점으로 쓰지 않는다.
            - 이미지에서 보이는 정보와 URL 상세페이지에서 확인된 정보를 구분해 설명한다.
            - reportMarkdown에는 "확인된 근거"와 "추론/개선 제안"이 섞이지 않게 작성한다. 캡처나 입력값에 없는 할인율, 리뷰 수, 평점, 구성품, 원산지, 용량, 소재, 기능, 주의사항은 사실처럼 쓰지 않는다.
            - "좋다/나쁘다"만 말하지 말고 왜 그런지 설명한다.
            - 개별 반응을 그대로 복붙하지 말고, 공통 패턴과 예외 패턴을 정리한다.
            - 상세페이지 개선안은 우선순위 HIGH/MEDIUM/LOW로 나눈다.
            - 구매 전환을 높이는 메시지와 신뢰를 높이는 정보 보완을 구분한다.
            - 최종 판정은 STRONG, PROMISING, MIXED, WEAK, RISKY 중 하나로 한다.

            출력 규칙:
            - JSON 객체 하나만 반환한다.
            - 마크다운 코드블록을 쓰지 마라.
            - reportMarkdown은 최종 사용자에게 보여줄 수 있는 Markdown 리포트 전문이다.
            - reportMarkdown에도 안전 규칙을 적용한다.

            출력 템플릿:
            {
              "finalVerdict": "PROMISING",
              "executiveSummary": "한눈에 보는 결론",
              "targetValidationSummary": "타겟 적합성 요약",
              "purchaseIntentSummary": "구매 의향 요약",
              "priceSummary": "가격 반응 요약",
              "trustSummary": "신뢰/후기/성분/정품 반응 요약",
              "detailPageSummary": "상세페이지 이해도와 구성 요약",
              "segmentSummary": "세그먼트별 반응 요약",
              "improvementSummary": "개선 방향 요약",
              "riskSummary": "주의할 리스크 요약",
              "keyMetrics": {
                "responseCount": 0,
                "avgPurchaseIntent": 0,
                "avgTargetFit": 0,
                "avgPriceAcceptance": 0,
                "avgTrust": 0,
                "avgDetailPageClarity": 0,
                "buyRatio": 0,
                "considerRatio": 0,
                "hesitateRatio": 0,
                "notBuyRatio": 0
              },
              "positiveInsights": [
                {"title": "긍정 인사이트 제목", "evidence": "근거", "affectedSegments": ["CORE_TARGET"], "businessMeaning": "비즈니스 의미"}
              ],
              "negativeInsights": [
                {"title": "우려 인사이트 제목", "evidence": "근거", "affectedSegments": ["TRUST_PRICE_SKEPTIC"], "businessMeaning": "비즈니스 의미"}
              ],
              "segmentAnalysis": [
                {"segment": "CORE_TARGET", "summary": "세그먼트 반응 요약", "purchaseIntentLevel": "HIGH", "mainDrivers": [], "mainBarriers": [], "recommendedMessage": "이 세그먼트에 적합한 메시지 방향"}
              ],
              "priceAnalysis": {"summary": "가격 반응 종합", "acceptableSegments": [], "resistantSegments": [], "recommendations": []},
              "trustAnalysis": {"summary": "신뢰 반응 종합", "trustedElements": [], "trustBarriers": [], "recommendations": []},
              "detailPageImprovementPriorities": [
                {"priority": "HIGH", "issue": "문제", "whyItMatters": "중요한 이유", "recommendedFix": "개선안", "expectedImpact": "예상 효과"}
              ],
              "messageRecommendations": [
                {"targetSegment": "CORE_TARGET", "messageDirection": "메시지 방향", "exampleCopy": "예시 문구", "reason": "이유"}
              ],
              "reportMarkdown": "# 최종 리포트\\n..."
            }
            """.stripIndent().trim();
    }

    // ── helpers ───────────────────────────────────────────────────

    private String resolveApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            String env = System.getenv("DEEPSEEK_API_KEY");
            if (env != null && !env.isBlank()) return env;
            throw new RuntimeException("DEEPSEEK_API_KEY가 설정되지 않았습니다.");
        }
        return apiKey;
    }

    static String cleanJsonString(String content) {
        if (content == null || content.isBlank()) return "{}";
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return content.trim();
    }
}
