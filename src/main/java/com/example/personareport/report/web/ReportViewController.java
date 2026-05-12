package com.example.personareport.report.web;

import com.example.personareport.report.service.ReportDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        model.addAttribute("order", orders.get(0));

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
}
