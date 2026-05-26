package com.example.personareport.report.web;

import com.example.personareport.report.service.ReportDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/** 리포트 상세 화면 및 개별 가상고객 반응 상세 화면. */
@Controller
@ConditionalOnProperty(prefix = "app.web", name = "admin-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
public class ReportViewController {

    private final ReportDataService reportDataService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, String> SEGMENT_KO = new LinkedHashMap<>();
    static {
        SEGMENT_KO.put("CORE_TARGET", "핵심 타겟");
        SEGMENT_KO.put("ADJACENT_TARGET", "인접 타겟");
        SEGMENT_KO.put("TRUST_PRICE_SKEPTIC", "신뢰/가격 회의");
        SEGMENT_KO.put("LOW_FIT_CONTROL", "저적합 대조군");
        SEGMENT_KO.put("STRATIFIED_RANDOM", "계층 랜덤");
    }

    private static final Map<String, String> SENTIMENT_KO = new LinkedHashMap<>();
    static {
        SENTIMENT_KO.put("POSITIVE", "긍정");
        SENTIMENT_KO.put("NEGATIVE", "부정");
        SENTIMENT_KO.put("MIXED", "복합");
        SENTIMENT_KO.put("NEUTRAL", "중립");
    }

    private static final Map<String, String> DECISION_KO = new LinkedHashMap<>();
    static {
        DECISION_KO.put("BUY", "구매 의향");
        DECISION_KO.put("CONSIDER", "고려 중");
        DECISION_KO.put("HESITATE", "망설임");
        DECISION_KO.put("NOT_BUY", "구매 안 함");
        DECISION_KO.put("UNDECIDED", "미결정");
    }

    private String ko(String key, Map<String, String> map) {
        return map.getOrDefault(key, key);
    }

    /** 리포트 상세 페이지. 종합 점수, Chart.js 차트, 분석 섹션, 가상고객 반응 표 렌더링. */
    @GetMapping("/{orderId}")
    public String viewReport(@PathVariable Long orderId, Model model) {
        var reports = reportDataService.findReportByOrderId(orderId);
        if (reports.isEmpty()) {
            return "redirect:/admin/orders/" + orderId;
        }
        var report = reports.get(0);
        model.addAttribute("report", report);

        var orders = reportDataService.findOrderById(orderId);
        if (orders.isEmpty()) {
            return "redirect:/admin/orders";
        }
        var order = orders.get(0);
        model.addAttribute("order", order);

        // 생성일 포맷
        Object createdAt = report.get("created_at");
        if (createdAt instanceof java.sql.Timestamp ts) {
            model.addAttribute("reportDate", ts.toLocalDateTime().toLocalDate().toString());
        } else if (createdAt != null) {
            model.addAttribute("reportDate", createdAt.toString().substring(0, 10));
        }

        // 종합 점수
        model.addAttribute("purchaseIntentScore", toInt(report.get("overall_purchase_intent_score")));
        model.addAttribute("targetFitScore", toInt(report.get("overall_target_fit_score")));
        model.addAttribute("priceScore", toInt(report.get("overall_price_acceptance_score")));
        model.addAttribute("trustScore", toInt(report.get("overall_trust_score")));
        model.addAttribute("clarityScore", toInt(report.get("overall_detail_page_clarity_score")));

        // 점수 상태 라벨
        model.addAttribute("purchaseStatus", scoreStatus(toInt(report.get("overall_purchase_intent_score"))));
        model.addAttribute("purchaseDesc", scoreDesc("구매 의향", toInt(report.get("overall_purchase_intent_score"))));
        model.addAttribute("fitStatus", scoreStatus(toInt(report.get("overall_target_fit_score"))));
        model.addAttribute("fitDesc", scoreDesc("고객 적합도", toInt(report.get("overall_target_fit_score"))));
        model.addAttribute("priceStatus", scoreStatus(toInt(report.get("overall_price_acceptance_score"))));
        model.addAttribute("priceDesc", scoreDesc("가격 수용도", toInt(report.get("overall_price_acceptance_score"))));
        model.addAttribute("trustStatus", scoreStatus(toInt(report.get("overall_trust_score"))));
        model.addAttribute("trustDesc", scoreDesc("신뢰도", toInt(report.get("overall_trust_score"))));
        model.addAttribute("clarityStatus", scoreStatus(toInt(report.get("overall_detail_page_clarity_score"))));
        model.addAttribute("clarityDesc", scoreDesc("설명 이해도", toInt(report.get("overall_detail_page_clarity_score"))));

        // 최고/최저 점수
        java.util.Map<String, Integer> scoreMap = new java.util.LinkedHashMap<>();
        scoreMap.put("구매 의향", toInt(report.get("overall_purchase_intent_score")));
        scoreMap.put("고객 적합도", toInt(report.get("overall_target_fit_score")));
        scoreMap.put("가격 수용도", toInt(report.get("overall_price_acceptance_score")));
        scoreMap.put("신뢰도", toInt(report.get("overall_trust_score")));
        scoreMap.put("설명 이해도", toInt(report.get("overall_detail_page_clarity_score")));
        String highest = "", lowest = "";
        int hi = -1, lo = 101;
        for (var e : scoreMap.entrySet()) {
            if (e.getValue() > hi) { hi = e.getValue(); highest = e.getKey(); }
            if (e.getValue() < lo) { lo = e.getValue(); lowest = e.getKey(); }
        }
        model.addAttribute("highestScore", highest);
        model.addAttribute("lowestScore", lowest);

        // detail_page_summary (템플릿에 미노출 필드 보완)
        model.addAttribute("detailPageSummary", report.get("detail_page_summary"));

        // 세그먼트 + 감정
        var segments = reportDataService.findSegments(orderId);
        var sentiments = reportDataService.findSentiments(orderId);
        var decisions = reportDataService.findDecisions(orderId);
        for (var s : segments) s.put("selection_group", ko((String) s.get("selection_group"), SEGMENT_KO));
        for (var s : sentiments) s.put("sentiment", ko((String) s.get("sentiment"), SENTIMENT_KO));
        for (var d : decisions) d.put("decision_status", ko((String) d.get("decision_status"), DECISION_KO));
        model.addAttribute("segments", segments);
        model.addAttribute("sentiments", sentiments);
        model.addAttribute("decisions", decisions);

        // 페르소나 반응
        var reactions = reportDataService.findReactions(orderId);
        for (var r : reactions) {
            r.put("selection_group", ko((String) r.get("selection_group"), SEGMENT_KO));
            r.put("sentiment", ko((String) r.get("sentiment"), SENTIMENT_KO));
            r.put("decision_status", ko((String) r.get("decision_status"), DECISION_KO));
        }
        model.addAttribute("reactions", reactions);

        var snapshot = reportDataService.findLatestPageSnapshot(orderId).stream()
                .findFirst().orElse(Map.of());
        var imageAnalyses = reportDataService.findImageAnalyses(orderId);
        var shoppingEvidence = reportDataService.findLatestShoppingEvidence(orderId).stream()
                .findFirst().orElse(Map.of());
        var shoppingProducts = formatShoppingProducts(
                reportDataService.findShoppingComparisonProducts(orderId, 12));
        var evidenceSummary = buildEvidenceSummary(order, snapshot, imageAnalyses, shoppingEvidence, shoppingProducts);
        model.addAttribute("evidenceSummary", evidenceSummary);
        model.addAttribute("evidenceWarnings", buildEvidenceWarnings(report, evidenceSummary));
        model.addAttribute("shoppingProducts", shoppingProducts);

        // JSON 직렬화
        try {
            model.addAttribute("segmentsJson", objectMapper.writeValueAsString(segments));
            model.addAttribute("sentimentsJson", objectMapper.writeValueAsString(sentiments));
            model.addAttribute("decisionsJson", objectMapper.writeValueAsString(decisions));
            model.addAttribute("scoresJson", objectMapper.writeValueAsString(java.util.List.of(
                    toInt(report.get("overall_purchase_intent_score")),
                    toInt(report.get("overall_target_fit_score")),
                    toInt(report.get("overall_price_acceptance_score")),
                    toInt(report.get("overall_trust_score")),
                    toInt(report.get("overall_detail_page_clarity_score"))
            )));
        } catch (Exception e) {
            model.addAttribute("segmentsJson", "[]");
            model.addAttribute("sentimentsJson", "[]");
            model.addAttribute("decisionsJson", "[]");
            model.addAttribute("scoresJson", "[]");
        }

        return "admin/reports/detail";
    }

    /** 개별 가상고객 반응 상세 페이지. 인구통계, 점수, 서술형 반응, 개선 제안. */
    @GetMapping("/{orderId}/personas/{reactionId}")
    public String viewPersona(@PathVariable Long orderId, @PathVariable Long reactionId, Model model) {
        var reactions = reportDataService.findReactionById(reactionId);
        if (reactions.isEmpty()) {
            return "redirect:/admin/reports/" + orderId;
        }
        model.addAttribute("reaction", reactions.get(0));

        var orders = reportDataService.findOrderById(orderId);
        if (orders.isEmpty()) {
            return "redirect:/admin/orders";
        }
        model.addAttribute("order", orders.get(0));

        return "admin/reports/persona-detail";
    }

    @SuppressWarnings("unchecked")
    private List<String> buildEvidenceWarnings(Map<String, Object> report,
                                               Map<String, Object> evidenceSummary) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> url = (Map<String, Object>) evidenceSummary.getOrDefault("url", Map.of());
        Map<String, Object> product = (Map<String, Object>) evidenceSummary.getOrDefault("product", Map.of());
        Map<String, Object> shopping = (Map<String, Object>) evidenceSummary.getOrDefault("shopping", Map.of());
        Map<String, Object> shipping = (Map<String, Object>) evidenceSummary.getOrDefault("shipping", Map.of());

        String reportText = joinText(
                report.get("executive_summary"),
                report.get("price_summary"),
                report.get("trust_summary"),
                report.get("segment_summary"),
                report.get("improvement_summary"),
                report.get("risk_summary"));
        boolean urlFailed = !"성공".equals(str(url.get("statusLabel")));
        String reviewValue = nestedValue(product, "reviewCount", "value");
        if (urlFailed && "미확인".equals(reviewValue)
                && Pattern.compile("후기\\s*(부재|부족|없|적)", Pattern.CASE_INSENSITIVE).matcher(reportText).find()) {
            warnings.add("본문에 후기 부재/부족 표현이 있지만, 현재 URL 크롤링은 성공하지 않았고 리뷰 수는 미확인입니다. 이 표현은 확정 근거로 사용하면 안 됩니다.");
        }

        if ("저가권".equals(str(shopping.get("priceLevelText")))
                && toInt(report.get("overall_price_acceptance_score")) < 60) {
            warnings.add("네이버 쇼핑 가격 분석은 저가권으로 저장되어 있는데 가격 수용도 점수가 낮습니다. 원산지 신뢰 우려가 가격 점수에 섞였는지 재검토해야 합니다.");
        }

        if ("계산 불가".equals(str(shipping.get("actualPriceText")))) {
            warnings.add("배송비가 확인되지 않아 배송비 포함 실구매가 기준의 가격 경쟁력은 아직 확정할 수 없습니다.");
        }

        String storageValue = nestedValue(product, "storage", "value");
        if ("냉동".equals(storageValue) && reportText.contains("반품 불가")) {
            warnings.add("냉동/신선식품의 단순 변심 반품 제한은 일반적인 카테고리 조건입니다. 과도한 핵심 리스크처럼 표현하지 않도록 재생성 시 보정됩니다.");
        }
        return warnings;
    }

    private Map<String, Object> buildEvidenceSummary(Map<String, Object> order,
                                                      Map<String, Object> snapshot,
                                                      List<Map<String, Object>> imageAnalyses,
                                                      Map<String, Object> shoppingEvidence,
                                                      List<Map<String, Object>> shoppingProducts) {
        Map<String, Object> rawMeta = parseJsonMap(snapshot.get("raw_meta_json"));
        String snapshotStatus = str(snapshot.get("snapshot_status")).toUpperCase();
        boolean captured = "CAPTURED".equals(snapshotStatus) || "SUCCESS".equals(snapshotStatus);
        boolean fallback = "FALLBACK".equals(snapshotStatus);
        String urlText = captured
                ? joinText(snapshot.get("visible_text"), snapshot.get("extracted_text_summary"), rawMeta)
                : "";
        String imageText = concatImageText(imageAnalyses);
        String orderText = joinText(order.get("project_name"), order.get("one_line_description"),
                order.get("detail_description"), order.get("price_text"), order.get("shipping_policy_text"));
        String shippingPolicyText = str(order.get("shipping_policy_text"));

        Map<String, Object> url = new LinkedHashMap<>();
        url.put("statusLabel", snapshot.isEmpty()
                ? "미수행"
                : captured ? "성공"
                : "SCREENSHOT_PRIMARY".equals(snapshotStatus) ? "캡처 기반"
                : fallback ? "실패(FALLBACK)"
                : snapshotStatus.isBlank() ? "미확인" : snapshotStatus);
        url.put("statusTone", captured || "SCREENSHOT_PRIMARY".equals(snapshotStatus) ? "ok" : fallback ? "bad" : "warn");
        url.put("failureReason", firstNonBlank(
                str(rawMeta.get("crawlError")),
                str(rawMeta.get("fallbackReason")),
                snapshot.isEmpty() ? "page_snapshot 데이터가 없습니다." : ""));
        url.put("pageUrl", firstNonBlank(str(snapshot.get("page_url")), str(order.get("page_url"))));
        url.put("productTitle", firstNonBlank(str(snapshot.get("product_title")), str(order.get("project_name"))));
        url.put("sourceNote", captured
                ? "URL 본문과 메타 태그에서 직접 추출한 데이터입니다."
                : "SCREENSHOT_PRIMARY".equals(snapshotStatus)
                ? "업로드된 상세페이지 전체 캡처 이미지를 1차 분석 근거로 사용합니다."
                : "URL 본문 직접 추출이 확인되지 않았습니다. 주문 입력값, 이미지 분석, 네이버 쇼핑 보조 근거를 분리해서 봐야 합니다.");

        String priceText = firstNonBlank(str(snapshot.get("price_text")), str(order.get("price_text")));
        long displayPrice = parseMoney(priceText);
        String priceSource = captured && !str(snapshot.get("price_text")).isBlank() ? "URL 추출" : "주문 입력";

        EvidenceValue weight = firstEvidence(
                findWeightText(urlText), "URL 추출",
                findWeightText(imageText), "이미지 분석",
                findWeightText(orderText), "주문 입력");
        long weightGrams = parseWeightGrams(firstNonBlank(weight.value(), orderText));

        EvidenceValue origin = firstEvidence(
                findOriginText(urlText), "URL 추출",
                findOriginText(imageText), "이미지 분석",
                findOriginText(orderText), "주문 입력");
        EvidenceValue storage = firstEvidence(
                findStorageText(urlText), "URL 추출",
                findStorageText(imageText), "이미지 분석",
                findStorageText(orderText), "주문 입력");

        String imageReviewCount = firstNonBlank(
                findFirst(imageText, "(?:리뷰\\s*수|리뷰|후기|상품평)\\s*[:(]?\\s*([0-9,]+)\\s*(?:개|건|\\))?"),
                findFirst(imageText, "([0-9,]+)\\s*(?:개|건)의?\\s*(?:리뷰|후기|상품평)"));
        String imageRatingScore = findFirst(imageText, "(?:평점|별점)\\s*[:]?\\s*([0-9]+(?:\\.[0-9]+)?)");
        EvidenceValue reviewCount = captured ? firstEvidence(
                str(rawMeta.get("reviewCount")),
                "URL 추출",
                findFirst(urlText, "(?:리뷰|후기|상품평)\\s*([0-9,]+)\\s*(?:개|건)?"),
                "URL 추출",
                imageReviewCount,
                "이미지 분석") : firstEvidence(
                imageReviewCount,
                "이미지 분석",
                findFirst(orderText, "(?:리뷰|후기|상품평)\\s*([0-9,]+)\\s*(?:개|건)?"),
                "주문 입력",
                "",
                "");
        EvidenceValue ratingScore = captured ? firstEvidence(
                str(rawMeta.get("ratingScore")),
                "URL 추출",
                str(rawMeta.get("satisfactionScore")),
                "URL 추출",
                firstNonBlank(findFirst(urlText, "(?:평점|별점)\\s*([0-9]+(?:\\.[0-9]+)?)"), imageRatingScore),
                imageRatingScore.isBlank() ? "URL 추출" : "이미지 분석") : firstEvidence(
                imageRatingScore,
                "이미지 분석",
                findFirst(orderText, "(?:평점|별점)\\s*([0-9]+(?:\\.[0-9]+)?)"),
                "주문 입력",
                "",
                "");

        Map<String, Object> shipping = buildShippingEvidence(rawMeta, captured ? urlText : imageText,
                shippingPolicyText,
                displayPrice, weightGrams);

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("displayPrice", valueWithSource(formatWon(displayPrice), priceSource));
        product.put("weight", valueWithSource(valueOrUnknown(weight.value()), valueOrUnknown(weight.source())));
        product.put("displayUnitPrice", valueWithSource(formatUnitPrice(displayPrice, weightGrams),
                displayPrice > 0 && weightGrams > 0 ? priceSource + " + " + weight.source() : "계산 불가"));
        product.put("origin", valueWithSource(valueOrUnknown(origin.value()), valueOrUnknown(origin.source())));
        product.put("storage", valueWithSource(valueOrUnknown(storage.value()), valueOrUnknown(storage.source())));
        product.put("reviewCount", valueWithSource(valueOrUnknown(reviewCount.value()), valueOrUnknown(reviewCount.source())));
        product.put("ratingScore", valueWithSource(valueOrUnknown(ratingScore.value()), valueOrUnknown(ratingScore.source())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("product", product);
        result.put("shipping", shipping);
        result.put("shopping", buildShoppingSummary(shoppingEvidence, shoppingProducts));
        result.put("image", buildImageSummary(imageAnalyses, imageText));
        result.put("sourcePriority", List.of(
                "1순위: 사용자가 직접 입력한 배송비 정책",
                "2순위: 이미지 OCR/시각 분석으로 확인된 상세페이지 정보",
                "3순위: URL에서 직접 추출한 객관 데이터",
                "4순위: 네이버 쇼핑 비교 데이터",
                "5순위: LLM 추론 데이터"));
        result.put("scoreRule", "가격 경쟁력은 원산지 신뢰도와 분리해 판단합니다. 가격은 사용자 입력 배송비 정책을 반영한 실구매가를 우선하고, 조건부 배송이면 조건과 불확실성을 함께 노출합니다.");
        return result;
    }

    private Map<String, Object> buildShoppingSummary(Map<String, Object> shoppingEvidence,
                                                     List<Map<String, Object>> products) {
        Map<String, Object> priceAnalysis = parseJsonMap(shoppingEvidence.get("analysis_price_analysis_json"));
        Map<String, Object> reportContext = parseJsonMap(shoppingEvidence.get("analysis_report_context_json"));

        long basePrice = number(priceAnalysis.get("basePrice"), number(shoppingEvidence.get("base_price"), 0));
        long medianPrice = number(priceAnalysis.get("medianPrice"), 0);
        long q1Price = number(priceAnalysis.get("q1Price"), 0);
        long q3Price = number(priceAnalysis.get("q3Price"), 0);

        Map<String, Object> shopping = new LinkedHashMap<>();
        shopping.put("usedText", shoppingEvidence.isEmpty() ? "미사용" : "사용");
        shopping.put("candidateCount", firstNonBlank(str(shoppingEvidence.get("candidate_count")),
                String.valueOf(products.size())));
        shopping.put("comparisonProductCount", products.size());
        shopping.put("basePriceText", formatWon(basePrice));
        shopping.put("q1PriceText", formatWon(q1Price));
        shopping.put("medianPriceText", formatWon(medianPrice));
        shopping.put("q3PriceText", formatWon(q3Price));
        shopping.put("priceLevelText", priceLevelKo(str(priceAnalysis.get("priceLevel"))));
        shopping.put("pricePositionText", str(priceAnalysis.get("pricePositionPercentile")).isBlank()
                ? "미확인"
                : str(priceAnalysis.get("pricePositionPercentile")) + "백분위");
        shopping.put("dominantCategory", firstNonBlank(
                str(reportContext.get("dominantCategory")),
                str(shoppingEvidence.get("analysis_dominant_category_json"))));
        shopping.put("shippingIncludedText", "미반영 - 현재 네이버 쇼핑 저장 데이터에는 배송비/무료배송/조건부 무료배송이 없습니다.");
        shopping.put("comparisonConditionText", "상품명/카테고리/가격 기반 후보입니다. 원산지, 중량, 냉동 여부, 배송비가 확인되지 않은 후보는 가격 점수에 강하게 반영하면 안 됩니다.");
        shopping.put("limitations", normalizeListText(reportContext.get("dataLimitations")));
        return shopping;
    }

    private Map<String, Object> buildImageSummary(List<Map<String, Object>> imageAnalyses, String imageText) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("count", imageAnalyses.size());
        image.put("originText", valueOrUnknown(findOriginText(imageText)));
        image.put("weightText", valueOrUnknown(findWeightText(imageText)));
        image.put("storageText", valueOrUnknown(findStorageText(imageText)));
        image.put("note", "이미지 출처는 OCR/시각 분석으로 보이는 정보만 의미합니다. URL 상세 본문, 리뷰, 평점, 배송비와는 별도 출처입니다.");
        return image;
    }

    private Map<String, Object> buildShippingEvidence(Map<String, Object> rawMeta,
                                                      String text,
                                                      String shippingPolicyText,
                                                      long displayPrice,
                                                      long weightGrams) {
        boolean hasManualShipping = !str(shippingPolicyText).isBlank();
        String sourceText = hasManualShipping ? shippingPolicyText : text;
        long metaShipping = firstPositive(
                parseMoney(rawMeta.get("product_shipping_amount")),
                parseMoney(rawMeta.get("shippingAmount")),
                parseMoney(rawMeta.get("shippingFee")),
                parseMoney(rawMeta.get("deliveryFee")));
        long textShipping = parseMoney(findFirst(sourceText, "(?:배송비|운송비)\\s*([0-9,.]+\\s*(?:만\\s*)?원?)"));
        long shippingFee = hasManualShipping ? textShipping : firstPositive(metaShipping, textShipping);
        long threshold = firstPositive(
                hasManualShipping ? 0 : parseMoney(rawMeta.get("conditionalFreeShippingMinAmount")),
                parseMoney(findFirst(sourceText, "([0-9,.]+\\s*(?:만\\s*)?원?)\\s*이상\\s*(?:구매\\s*)?(?:시\\s*)?무료배송")));

        String rawType = str(rawMeta.get("deliveryType")).toLowerCase();
        String typeCode = "";
        boolean mentionsFreeShipping = sourceText.contains("무료배송");
        boolean conditionLike = threshold > 0
                || sourceText.contains("이상")
                || sourceText.contains("조건")
                || sourceText.contains("멤버십")
                || sourceText.toLowerCase().contains("membership")
                || sourceText.contains("가입")
                || sourceText.contains("와우");
        boolean explicitCompleteFree = sourceText.contains("완전 무료배송")
                || sourceText.contains("전 상품 무료배송")
                || sourceText.equals("무료배송");
        if (!hasManualShipping && rawType.contains("free")) typeCode = "FREE";
        if (mentionsFreeShipping) typeCode = conditionLike && !explicitCompleteFree ? "CONDITIONAL_FREE" : "FREE";
        if (threshold > 0) typeCode = "CONDITIONAL_FREE";
        if (shippingFee > 0 && typeCode.isBlank()) typeCode = "PAID";

        long actualPrice = 0;
        String actualSource = "계산 불가";
        if (displayPrice > 0) {
            if ("FREE".equals(typeCode)) {
                actualPrice = displayPrice;
                actualSource = "표시가 + 무료배송";
            } else if ("PAID".equals(typeCode) && shippingFee > 0) {
                actualPrice = displayPrice + shippingFee;
                actualSource = "표시가 + 배송비";
            } else if ("CONDITIONAL_FREE".equals(typeCode) && threshold > 0) {
                if (displayPrice >= threshold) {
                    actualPrice = displayPrice;
                    actualSource = "조건부 무료배송 기준 충족";
                } else if (shippingFee > 0) {
                    actualPrice = displayPrice + shippingFee;
                    actualSource = "조건부 무료배송 기준 미충족 + 배송비";
                }
            }
        }

        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("typeText", switch (typeCode) {
            case "FREE" -> "무료배송";
            case "CONDITIONAL_FREE" -> "조건부 무료배송";
            case "PAID" -> "유료배송";
            default -> "미확인";
        });
        shipping.put("amountText", shippingFee > 0 ? formatWon(shippingFee) : "미확인");
        shipping.put("thresholdText", threshold > 0 ? formatWon(threshold) + " 이상 구매 시" : "미확인");
        shipping.put("actualPriceText", actualPrice > 0 ? formatWon(actualPrice) : "계산 불가");
        shipping.put("actualUnitPriceText", actualPrice > 0 ? formatUnitPrice(actualPrice, weightGrams) : "계산 불가");
        shipping.put("policyText", hasManualShipping ? shippingPolicyText : "미입력");
        shipping.put("source", hasManualShipping
                ? "사용자 직접 입력"
                : !"미확인".equals(shipping.get("typeText")) ? "URL/이미지 추출" : "미확인");
        shipping.put("calculationSource", actualSource);
        shipping.put("note", actualPrice > 0
                ? "가격 점수에는 배송비 포함 실구매가 기준을 우선 적용해야 합니다."
                : hasManualShipping && "CONDITIONAL_FREE".equals(typeCode)
                ? "조건부 배송 정책이라 조건 충족 여부를 함께 보아야 합니다."
                : "배송비가 확인되지 않아 실구매가 기준 가격 경쟁력을 확정할 수 없습니다.");
        return shipping;
    }

    private List<Map<String, Object>> formatShoppingProducts(List<Map<String, Object>> products) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : products) {
            String title = firstNonBlank(str(p.get("title_clean")), str(p.get("title_raw")));
            long price = number(p.get("lprice"), 0);
            long weightGrams = parseWeightGrams(title);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", title);
            row.put("mallName", valueOrUnknown(str(p.get("mall_name"))));
            row.put("category", joinCategory(p));
            row.put("origin", valueOrUnknown(findOriginText(title)));
            row.put("storage", valueOrUnknown(findStorageText(title)));
            row.put("weight", valueOrUnknown(findWeightText(title)));
            row.put("priceText", formatWon(price));
            row.put("shippingText", "미수집");
            row.put("actualPriceText", "계산 불가");
            row.put("unitPriceText", formatUnitPrice(price, weightGrams));
            row.put("score", str(p.get("candidate_score")));
            row.put("roles", valueOrUnknown(str(p.get("roles"))));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, String> valueWithSource(String value, String source) {
        return Map.of("value", valueOrUnknown(value), "source", valueOrUnknown(source));
    }

    private EvidenceValue firstEvidence(String value1, String source1,
                                        String value2, String source2,
                                        String value3, String source3) {
        if (!str(value1).isBlank()) return new EvidenceValue(str(value1), source1);
        if (!str(value2).isBlank()) return new EvidenceValue(str(value2), source2);
        if (!str(value3).isBlank()) return new EvidenceValue(str(value3), source3);
        return new EvidenceValue("", "");
    }

    private record EvidenceValue(String value, String source) {}

    private Map<String, Object> parseJsonMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put(str(k), v));
            return result;
        }
        String json = str(value);
        if (json.isBlank() || "null".equalsIgnoreCase(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String concatImageText(List<Map<String, Object>> imageAnalyses) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> image : imageAnalyses) {
            sb.append(' ').append(str(image.get("image_summary")));
            sb.append(' ').append(str(image.get("visible_text")));
            sb.append(' ').append(str(image.get("visual_trust_elements")));
            sb.append(' ').append(str(image.get("visible_claims")));
            sb.append(' ').append(str(image.get("visible_prices")));
            sb.append(' ').append(str(image.get("visible_certifications")));
            sb.append(' ').append(str(image.get("information_gaps")));
        }
        return sb.toString();
    }

    private String joinText(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (Object value : values) {
            if (value instanceof Map<?, ?> m) {
                m.values().forEach(v -> sb.append(' ').append(str(v)));
            } else {
                sb.append(' ').append(str(value));
            }
        }
        return sb.toString();
    }

    private String findWeightText(String text) {
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(kg|KG|Kg|킬로|g|G|그램)")
                .matcher(str(text));
        return matcher.find() ? matcher.group(1) + normalizeWeightUnit(matcher.group(2)) : "";
    }

    private String normalizeWeightUnit(String unit) {
        String lower = unit.toLowerCase();
        if (lower.equals("kg") || "킬로".equals(unit)) return "kg";
        return "g";
    }

    private long parseWeightGrams(String text) {
        Matcher kg = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(kg|KG|Kg|킬로)").matcher(str(text));
        if (kg.find()) {
            return BigDecimal.valueOf(Double.parseDouble(kg.group(1)))
                    .multiply(BigDecimal.valueOf(1000))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        }
        Matcher gram = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(g|G|그램)").matcher(str(text));
        if (gram.find()) {
            return BigDecimal.valueOf(Double.parseDouble(gram.group(1)))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        }
        return 0;
    }

    private String findOriginText(String text) {
        String source = str(text);
        if (source.contains("브라질") || source.toLowerCase().contains("brazil")) return "브라질산";
        if (source.contains("국내산") || source.contains("국산")) return "국내산";
        if (source.contains("미국산")) return "미국산";
        if (source.contains("호주산")) return "호주산";
        if (source.contains("태국산")) return "태국산";
        return "";
    }

    private String findStorageText(String text) {
        String source = str(text).toLowerCase();
        if (source.contains("냉동") || source.contains("frozen")) return "냉동";
        if (source.contains("냉장") || source.contains("refrigerated")) return "냉장";
        if (source.contains("실온")) return "실온";
        return "";
    }

    private String findFirst(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(str(text));
        return matcher.find() ? matcher.group(1) : "";
    }

    private String joinCategory(Map<String, Object> row) {
        return List.of(str(row.get("category1")), str(row.get("category2")), str(row.get("category3")), str(row.get("category4")))
                .stream().filter(s -> !s.isBlank()).reduce((a, b) -> a + ">" + b).orElse("미확인");
    }

    private String normalizeListText(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::str).filter(s -> !s.isBlank()).reduce((a, b) -> a + " / " + b).orElse("미확인");
        }
        String text = str(value);
        return text.isBlank() ? "미확인" : text;
    }

    private String priceLevelKo(String level) {
        return switch (str(level).toUpperCase()) {
            case "LOW" -> "저가권";
            case "MID", "MIDDLE" -> "중간권";
            case "HIGH" -> "고가권";
            default -> "미확인";
        };
    }

    private long parseMoney(Object value) {
        String text = str(value).replace(",", "");
        Matcher manWon = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*만\\s*원").matcher(text);
        if (manWon.find()) {
            return BigDecimal.valueOf(Double.parseDouble(manWon.group(1)))
                    .multiply(BigDecimal.valueOf(10000))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        }
        Matcher cheonWon = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*천\\s*원").matcher(text);
        if (cheonWon.find()) {
            return BigDecimal.valueOf(Double.parseDouble(cheonWon.group(1)))
                    .multiply(BigDecimal.valueOf(1000))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return 0;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long number(Object value, long defaultValue) {
        if (value instanceof Number n) return n.longValue();
        long parsed = parseMoney(value);
        return parsed > 0 ? parsed : defaultValue;
    }

    private long firstPositive(long... values) {
        for (long value : values) {
            if (value > 0) return value;
        }
        return 0;
    }

    private String formatWon(long value) {
        if (value <= 0) return "미확인";
        return String.format("%,d원", value);
    }

    private String formatUnitPrice(long price, long weightGrams) {
        if (price <= 0 || weightGrams <= 0) return "계산 불가";
        long unitPrice = BigDecimal.valueOf(price)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(weightGrams), 0, RoundingMode.HALF_UP)
                .longValue();
        return String.format("100g당 %,d원", unitPrice);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!str(value).isBlank()) return str(value);
        }
        return "";
    }

    private String valueOrUnknown(String value) {
        return str(value).isBlank() ? "미확인" : str(value);
    }

    @SuppressWarnings("unchecked")
    private String nestedValue(Map<String, Object> source, String key, String nestedKey) {
        Object nested = source.get(key);
        if (nested instanceof Map<?, ?> m) {
            return str(((Map<String, Object>) m).get(nestedKey));
        }
        return "";
    }

    private String scoreStatus(int score) {
        if (score >= 75) return "좋음";
        if (score >= 60) return "보통";
        if (score >= 40) return "주의";
        return "보완 필요";
    }

    private String scoreDesc(String label, int score) {
        if (score >= 75) return label + "이 높습니다. 긍정적인 신호입니다.";
        if (score >= 60) return label + "이 보통 수준입니다. 일부 개선 여지가 있습니다.";
        if (score >= 40) return label + "이 낮은 편입니다. 개선이 필요합니다.";
        return label + "이 매우 낮습니다. 빠른 개선이 필요합니다.";
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return (int) Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private String str(Object value) {
        return value != null ? value.toString().trim() : "";
    }
}
