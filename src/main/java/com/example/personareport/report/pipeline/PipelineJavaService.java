package com.example.personareport.report.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DeepSeek Feign Client 기반 리포트 생성 파이프라인.
 * Python 스크립트(generate, select, generate_final)를 Java로 마이그레이션.
 * ML 관련 코드(점수 예측/학습/라벨링)는 Python 그대로 유지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineJavaService {

    private final DeepSeekFeignClient deepSeek;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * 상품 타겟 프로필 생성.
     * 주문 정보를 DeepSeek으로 분석하여 product_target_profile에 저장.
     */
    public void generateTargetProfile(Long orderId) {
        var order = jdbc.queryForMap("SELECT * FROM report_order WHERE id = ?", orderId);
        String auth = "Bearer " + System.getenv("DEEPSEEK_API_KEY");
        String systemPrompt = """
            너는 한국어로 작성하는 이커머스 상품 분석 전문가다.
            상세페이지 정보를 분석하여 상품 타겟 프로필을 JSON으로 반환한다.
            """;
        String userPrompt = String.format("""
            다음 상품 정보를 분석해 주세요:
            상품명: %s
            한 줄 소개: %s
            상세 설명: %s
            가격: %s
            주요 타겟 고객: %s

            JSON 형식으로 반환:
            {"productCategory":"...","productType":"...","targetSummary":"...","coreKeywords":["..."],"purchaseDrivers":["..."],"purchaseBarriers":["..."],"messageAngles":["..."]}
            """,
                order.get("project_name"), na(order.get("one_line_description")), na(order.get("detail_description")),
                na(order.get("price_text")), na(order.get("target_customer")));

        String content = callDeepSeek(systemPrompt, userPrompt);
        try {
            Map<String, Object> result = objectMapper.readValue(content, Map.class);
            jdbc.update("""
                INSERT INTO product_target_profile (report_order_id, product_category, product_type, product_name, target_summary, core_keywords, purchase_drivers, purchase_barriers, message_angles, profile_version, model_name, model_version)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,'product_target_profile_v1','deepseek-v4-flash','v1')
                ON CONFLICT (report_order_id, profile_version) DO UPDATE SET
                target_summary=EXCLUDED.target_summary, core_keywords=EXCLUDED.core_keywords,
                purchase_drivers=EXCLUDED.purchase_drivers, purchase_barriers=EXCLUDED.purchase_barriers
                """, orderId,
                str(result.get("productCategory")), str(result.get("productType")),
                na(order.get("project_name")), str(result.get("targetSummary")),
                toJson(result.get("coreKeywords")), toJson(result.get("purchaseDrivers")),
                toJson(result.get("purchaseBarriers")), toJson(result.get("messageAngles")));
        } catch (Exception e) {
            log.warn("Target profile parsing failed: {}", e.getMessage());
        }
    }

    /**
     * 100만 페르소나 풀에서 계층화 샘플링으로 지정된 수만큼 선별.
     * NEMOTRON 소스 우선, 부족 시 SEED로 보충. 직업군별 비례 할당.
     */
    public List<Map<String, Object>> selectPersonas(Long orderId, int count) {
        // NEMOTRON 우선, SEED 보충
        List<Map<String, Object>> pool = new ArrayList<>();
        Long minNem = jdbc.queryForObject("SELECT COALESCE(MIN(id),0) FROM persona_profile WHERE source='NEMOTRON' AND active=true", Long.class);
        Long maxNem = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM persona_profile WHERE source='NEMOTRON' AND active=true", Long.class);
        if (minNem > 0 && maxNem > 0) {
            for (int i = 0; i < 5 && pool.size() < count * 5; i++) {
                long start = minNem + (long) (Math.random() * (maxNem - minNem));
                pool.addAll(jdbc.queryForList("SELECT * FROM persona_profile WHERE source='NEMOTRON' AND active=true AND id >= ? ORDER BY id LIMIT ?", start, count * 2));
            }
        }
        if (pool.size() < count * 3) {
            pool.addAll(jdbc.queryForList("SELECT * FROM persona_profile WHERE source='SEED' AND active=true LIMIT ?", count * 2 - pool.size()));
        }
        pool = pool.stream().distinct().limit(count * 3).toList();

        // Stratified sampling by occupation
        Map<String, List<Map<String, Object>>> byOcc = new LinkedHashMap<>();
        for (var p : pool) {
            byOcc.computeIfAbsent(na((String) p.get("occupation")), k -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        int perGroup = Math.max(1, count / Math.max(byOcc.size(), 1));
        List<String> keys = new ArrayList<>(byOcc.keySet());
        Collections.shuffle(keys);
        for (String occ : keys) {
            List<Map<String, Object>> group = byOcc.get(occ);
            Collections.shuffle(group);
            for (int i = 0; i < Math.min(perGroup, group.size()) && selected.size() < count; i++) {
                selected.add(group.get(i));
            }
        }

        // 이전 단계에서 생성된 product_target_profile ID 조회
        Long targetProfileId = jdbc.queryForObject(
                "SELECT id FROM product_target_profile WHERE report_order_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, orderId);
        if (targetProfileId == null) targetProfileId = 0L;

        // Save to selected_persona
        jdbc.update("DELETE FROM selected_persona WHERE report_order_id = ?", orderId);
        for (int i = 0; i < selected.size(); i++) {
            var p = selected.get(i);
            Long personaId = (Long) p.get("id");
            jdbc.update("""
                INSERT INTO selected_persona (report_order_id, target_profile_id, persona_profile_id,
                selection_rank, selection_group, relevance_score, diversity_score, final_score,
                selection_reason, persona_score_model_version)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, orderId, targetProfileId, personaId, i + 1, "CORE_TARGET", 70.0, 60.0, 65.0,
                "직업군 기반 계층화 샘플링", "NAVER_SHOPPING_CANDIDATE_V1");
        }
        log.info("Persona selection done: {} personas for orderId={}", selected.size(), orderId);
        return selected;
    }

    /** 선별된 페르소나 각각에 대해 DeepSeek로 상세페이지 반응 생성 (구매의향/신뢰도/가격수용도 등). 결과는 persona_reaction에 저장. */
    public void generateReactions(Long orderId, List<Map<String, Object>> personas) {
        var order = jdbc.queryForMap("SELECT * FROM report_order WHERE id = ?", orderId);
        String auth = "Bearer " + System.getenv("DEEPSEEK_API_KEY");

        for (int i = 0; i < personas.size(); i++) {
            var persona = personas.get(i);
            String prompt = String.format("""
                당신은 다음 가상 고객의 관점에서 상품 상세페이지를 평가합니다.

                가상 고객 정보:
                연령: %s, 성별: %s, 지역: %s, 직업: %s
                요약: %s
                관심사: %s

                상품 정보:
                상품명: %s
                소개: %s
                설명: %s
                가격: %s

                다음 JSON 형식으로만 응답하세요:
                {"sentiment":"POSITIVE|NEGATIVE|MIXED|NEUTRAL","purchaseIntentScore":0,"targetFitScore":0,"priceAcceptanceScore":0,"trustScore":0,"detailPageClarityScore":0,"decisionStatus":"...","firstImpression":"...","likelyReaction":"...","priceReaction":"...","trustReviewReaction":"...","detailPageFeedback":"...","representativeQuote":"...","positivePoints":[...],"concerns":[...],"purchaseBarriers":[...],"recommendedDetailPageFixes":[...]}
                """,
                na(persona.get("age_group")), na(persona.get("gender")), na(persona.get("region")),
                na(persona.get("occupation")), na(persona.get("persona_summary")), na(persona.get("interests")),
                order.get("project_name"), na(order.get("one_line_description")),
                na(order.get("detail_description")), na(order.get("price_text")));

            String content = callDeepSeek("당신은 가상 고객 페르소나다. 한국어로 응답하고 JSON만 반환한다.", prompt);
            try {
                Map<String, Object> r = objectMapper.readValue(content, Map.class);
                jdbc.update("""
                    INSERT INTO persona_reaction (report_order_id, persona_profile_id, segment_label, selection_group, sentiment, decision_status,
                    purchase_intent_score, target_fit_score, price_acceptance_score, trust_score, detail_page_clarity_score,
                    first_impression, likely_reaction, price_reaction, trust_review_reaction, detail_page_feedback,
                    representative_quote, positive_points, concerns, purchase_barriers, recommended_detail_page_fixes,
                    response_version, model_name, model_version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,'detail_page_reaction_v1','deepseek-v4-flash','v1')
                    """, orderId, persona.get("id"), personaLabel(persona), "CORE_TARGET",
                    str(r.get("sentiment")), str(r.get("decisionStatus")),
                    toInt(r.get("purchaseIntentScore")), toInt(r.get("targetFitScore")),
                    toInt(r.get("priceAcceptanceScore")), toInt(r.get("trustScore")),
                    toInt(r.get("detailPageClarityScore")),
                    str(r.get("firstImpression")), str(r.get("likelyReaction")),
                    str(r.get("priceReaction")), str(r.get("trustReviewReaction")),
                    str(r.get("detailPageFeedback")), str(r.get("representativeQuote")),
                    toJson(r.get("positivePoints")), toJson(r.get("concerns")),
                    toJson(r.get("purchaseBarriers")), toJson(r.get("recommendedDetailPageFixes")));
            } catch (Exception e) {
                log.warn("Reaction generation failed for persona {}: {}", persona.get("id"), e.getMessage());
            }
        }
        log.info("Reactions generated for {} personas, orderId={}", personas.size(), orderId);
    }

    /** 30명 반응을 DeepSeek으로 종합하여 최종 진단/분석/개선제안 생성. final_report에 저장. */
    public void generateFinalReport(Long orderId) {
        var order = jdbc.queryForMap("SELECT * FROM report_order WHERE id = ?", orderId);
        List<Map<String, Object>> reactions = jdbc.queryForList("""
            SELECT * FROM persona_reaction WHERE report_order_id = ?
            """, orderId);

        if (reactions.isEmpty()) {
            log.warn("No reactions to synthesize for orderId={}", orderId);
            return;
        }

        // Compute aggregate scores
        double avgPurchase = reactions.stream().mapToInt(r -> toInt(r.get("purchase_intent_score"))).average().orElse(0);
        double avgFit = reactions.stream().mapToInt(r -> toInt(r.get("target_fit_score"))).average().orElse(0);
        double avgPrice = reactions.stream().mapToInt(r -> toInt(r.get("price_acceptance_score"))).average().orElse(0);
        double avgTrust = reactions.stream().mapToInt(r -> toInt(r.get("trust_score"))).average().orElse(0);
        double avgClarity = reactions.stream().mapToInt(r -> toInt(r.get("detail_page_clarity_score"))).average().orElse(0);

        // Build prompt from reactions
        StringBuilder reactionText = new StringBuilder();
        for (int i = 0; i < Math.min(reactions.size(), 10); i++) {
            var r = reactions.get(i);
            reactionText.append(String.format("%d. %s | 구매의향:%s 적합:%s 가격:%s 신뢰:%s\n  인상:%s\n  반응:%s\n",
                i + 1, r.get("segment_label"), r.get("purchase_intent_score"), r.get("target_fit_score"),
                r.get("price_acceptance_score"), r.get("trust_score"),
                na((String) r.get("first_impression")), na((String) r.get("likely_reaction"))));
        }

        String prompt = String.format("""
            %d명 가상고객 반응을 종합한 최종 리포트를 JSON으로 작성하세요.
            상품: %s | 가격: %s

            평균 점수: 구매의향 %.1f, 고객적합 %.1f, 가격수용 %.1f, 신뢰 %.1f, 선명도 %.1f

            주요 반응:
            %s

            JSON 형식:
            {"finalVerdict":"...","executiveSummary":"...","purchaseIntentSummary":"...","priceSummary":"...","trustSummary":"...","targetValidationSummary":"...","segmentSummary":"...","improvementSummary":"...","riskSummary":"...","reportMarkdown":"..."}
            """,
            reactions.size(), order.get("project_name"), na(order.get("price_text")),
            avgPurchase, avgFit, avgPrice, avgTrust, avgClarity, reactionText.toString());

        String content = callDeepSeek("당신은 전문 이커머스 리포트 분석가다. 한국어로 응답하고 JSON만 반환한다.", prompt);
        try {
            Map<String, Object> r = objectMapper.readValue(content, Map.class);
            jdbc.update("""
                INSERT INTO final_report (report_order_id, overall_purchase_intent_score, overall_target_fit_score,
                overall_price_acceptance_score, overall_trust_score, overall_detail_page_clarity_score,
                final_verdict, executive_summary, purchase_intent_summary, price_summary, trust_summary,
                target_validation_summary, segment_summary, improvement_summary, risk_summary,
                report_markdown, response_count, report_version, response_version, model_name, model_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'detail_page_report_v1','detail_page_reaction_v1','deepseek-v4-flash','v1')
                ON CONFLICT (report_order_id, profile_version) DO UPDATE SET
                overall_purchase_intent_score=EXCLUDED.overall_purchase_intent_score,
                final_verdict=EXCLUDED.final_verdict, executive_summary=EXCLUDED.executive_summary,
                report_markdown=EXCLUDED.report_markdown
                """, orderId,
                (int) Math.round(avgPurchase), (int) Math.round(avgFit), (int) Math.round(avgPrice),
                (int) Math.round(avgTrust), (int) Math.round(avgClarity),
                str(r.get("finalVerdict")), str(r.get("executiveSummary")),
                str(r.get("purchaseIntentSummary")), str(r.get("priceSummary")),
                str(r.get("trustSummary")), str(r.get("targetValidationSummary")),
                str(r.get("segmentSummary")), str(r.get("improvementSummary")),
                str(r.get("riskSummary")), str(r.get("reportMarkdown")), reactions.size());
        } catch (Exception e) {
            log.warn("Final report generation failed: {}", e.getMessage());
        }
    }

    // === helpers ===

    private String callDeepSeek(String systemPrompt, String userPrompt) {
        String auth = "Bearer " + System.getenv("DEEPSEEK_API_KEY");
        var req = new DeepSeekFeignClient.DeepSeekRequest(
                "deepseek-v4-flash",
                List.of(
                        new DeepSeekFeignClient.Message("system", systemPrompt),
                        new DeepSeekFeignClient.Message("user", userPrompt)
                ),
                Map.of("type", "json_object"),
                0.4, 6000, false
        );
        var resp = deepSeek.chatCompletion(auth, req);
        if (resp.choices() == null || resp.choices().isEmpty()) return "{}";
        var msg = resp.choices().get(0).message();
        String content = msg != null ? msg.content() : "{}";
        // strip fences
        if (content.startsWith("```")) {
            content = content.replaceFirst("```(?:json)?\\s*", "");
            content = content.replaceFirst("\\s*```$", "");
        }
        return content.trim();
    }

    private String personaLabel(Map<String, Object> p) {
        return na((String) p.get("age_group")) + " " + na((String) p.get("occupation")) + ", " + na((String) p.get("region"));
    }

    private String na(String s) { return s == null || s.isBlank() ? "미입력" : s.trim(); }
    private String na(Object o) { return o != null ? na(o.toString()) : "미입력"; }
    private String str(Object o) { return o != null ? o.toString() : ""; }
    private String toJson(Object o) { try { return objectMapper.writeValueAsString(o != null ? o : List.of()); } catch (Exception e) { return "[]"; } }
    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }
}
