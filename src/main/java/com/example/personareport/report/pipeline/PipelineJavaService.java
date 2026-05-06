package com.example.personareport.report.pipeline;

import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.report.domain.PersonaProfile;
import com.example.personareport.report.pipeline.entity.FinalReport;
import com.example.personareport.report.pipeline.entity.PersonaReaction;
import com.example.personareport.report.pipeline.entity.ProductTargetProfile;
import com.example.personareport.report.pipeline.entity.SelectedPersona;
import com.example.personareport.report.pipeline.repository.FinalReportRepository;
import com.example.personareport.report.pipeline.repository.PersonaReactionRepository;
import com.example.personareport.report.pipeline.repository.ProductTargetProfileRepository;
import com.example.personareport.report.pipeline.repository.SelectedPersonaRepository;
import com.example.personareport.report.repository.PersonaProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DeepSeek Feign Client 기반 리포트 생성 파이프라인.
 * JPA Repository + QueryDSL 기반으로 모든 DB 접근을 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineJavaService {

    private static final String SOURCE_NEMOTRON = "NEMOTRON";
    private static final String SOURCE_SEED = "SEED";
    private static final String SCORING_VERSION = "NAVER_SHOPPING_CANDIDATE_V1";

    private final DeepSeekFeignClient deepSeek;
    private final ObjectMapper objectMapper;

    private final ProductTargetProfileRepository targetProfileRepo;
    private final SelectedPersonaRepository selectedPersonaRepo;
    private final PersonaReactionRepository reactionRepo;
    private final FinalReportRepository finalReportRepo;
    private final PersonaProfileRepository personaProfileRepo;
    private final ReactionReportOrderRepository orderRepo;

    /**
     * 상품 타겟 프로필 생성. 주문 정보를 DeepSeek으로 분석하여 product_target_profile에 저장.
     */
    @Transactional
    public void generateTargetProfile(Long orderId) {
        var order = orderRepo.findById(orderId).orElseThrow();
        String prompt = String.format("""
            상품명: %s | 한 줄 소개: %s | 설명: %s | 가격: %s | 타겟: %s
            JSON: {"productCategory":"...","productType":"...","targetSummary":"...","coreKeywords":["..."],"purchaseDrivers":["..."],"purchaseBarriers":["..."],"messageAngles":["..."]}
            """, order.getProjectName(), na(order.getOneLineDescription()),
            na(order.getDetailDescription()), na(order.getPriceText()), na(order.getTargetCustomer()));

        String content = callDeepSeek("이커머스 상품 분석 전문가. JSON만 반환.", prompt);
        try {
            var r = objectMapper.readValue(content, Map.class);
            // UPSERT: 기존 레코드 있으면 삭제 후 재생성
            var existing = targetProfileRepo.findFirstByReportOrderIdOrderByIdDesc(orderId);
            existing.ifPresent(p -> targetProfileRepo.delete(p));

            targetProfileRepo.save(ProductTargetProfile.create(orderId,
                    str(r.get("productCategory")), str(r.get("productType")),
                    str(r.get("targetSummary")), toJson(r.get("coreKeywords")),
                    toJson(r.get("purchaseDrivers")), toJson(r.get("purchaseBarriers")),
                    toJson(r.get("messageAngles"))));
        } catch (Exception e) {
            log.error("Target profile parsing failed: {}", e.getMessage(), e);
            throw new RuntimeException("상품 타겟 프로필 생성 실패", e);
        }
    }

    /**
     * 100만 페르소나 풀에서 계층화 샘플링으로 지정된 수만큼 선별.
     */
    @Transactional
    public List<Map<String, Object>> selectPersonas(Long orderId, int count) {
        // NEMOTRON 페르소나 후보 수집
        List<PersonaProfile> pool = new ArrayList<>();
        long minNem = personaProfileRepo.findMinActiveIdBySource(SOURCE_NEMOTRON);
        long maxNem = personaProfileRepo.findMaxActiveIdBySource(SOURCE_NEMOTRON);
        if (minNem > 0 && maxNem > 0) {
            for (int i = 0; i < 5 && pool.size() < count * 5; i++) {
                long start = minNem + (long) (Math.random() * (maxNem - minNem));
                pool.addAll(personaProfileRepo.findCandidateWindow(SOURCE_NEMOTRON, start, null, count * 2));
            }
        }
        if (pool.size() < count * 3) {
            pool.addAll(personaProfileRepo.findBySourceAndActiveTrue(SOURCE_SEED));
        }
        pool = pool.stream().distinct().limit(count * 3).toList();

        // 직업군별 계층화 샘플링
        Map<String, List<PersonaProfile>> byOcc = new LinkedHashMap<>();
        for (var p : pool) {
            byOcc.computeIfAbsent(na(p.getOccupation()), k -> new ArrayList<>()).add(p);
        }
        List<PersonaProfile> selected = new ArrayList<>();
        int perGroup = Math.max(1, count / Math.max(byOcc.size(), 1));
        List<String> keys = new ArrayList<>(byOcc.keySet()); Collections.shuffle(keys);
        for (String occ : keys) {
            List<PersonaProfile> group = byOcc.get(occ); Collections.shuffle(group);
            for (int i = 0; i < Math.min(perGroup, group.size()) && selected.size() < count; i++) {
                selected.add(group.get(i));
            }
        }

        // target_profile_id 조회
        Long targetProfileId = targetProfileRepo.findFirstByReportOrderIdOrderByIdDesc(orderId)
                .map(ProductTargetProfile::getId).orElse(0L);

        // 저장
        selectedPersonaRepo.deleteByReportOrderId(orderId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            var p = selected.get(i);
            var sp = selectedPersonaRepo.save(SelectedPersona.create(orderId, targetProfileId,
                    p.getId(), i + 1, "CORE_TARGET",
                    "직업군 기반 계층화 샘플링", SCORING_VERSION));
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId()); map.put("age_group", p.getAgeGroup());
            map.put("gender", p.getGender()); map.put("region", p.getRegion());
            map.put("occupation", p.getOccupation()); map.put("persona_summary", p.getPersonaSummary());
            map.put("interests", p.getInterests());
            map.put("selected_persona_id", sp.getId());
            result.add(map);
        }
        log.info("Persona selection done: {} for orderId={}", result.size(), orderId);
        return result;
    }

    /**
     * 선별된 페르소나 각각에 대해 DeepSeek로 상세페이지 반응 생성.
     */
    @Transactional
    public void generateReactions(Long orderId, List<Map<String, Object>> personas) {
        var order = orderRepo.findById(orderId).orElseThrow();
        int successCount = 0;
        for (var persona : personas) {
            Long personaProfileId = (Long) persona.get("id");
            Long selectedPersonaId = (Long) persona.getOrDefault("selected_persona_id", 0L);
            String prompt = String.format("""
                가상 고객: 연령:%s 성별:%s 지역:%s 직업:%s 요약:%s 관심사:%s
                상품:%s 소개:%s 설명:%s 가격:%s
                JSON: {"sentiment":"POSITIVE|NEGATIVE|MIXED|NEUTRAL","purchaseIntentScore":0,...}
                """, na((String) persona.get("age_group")), na((String) persona.get("gender")),
                na((String) persona.get("region")), na((String) persona.get("occupation")),
                na((String) persona.get("persona_summary")), na((String) persona.get("interests")),
                order.getProjectName(), na(order.getOneLineDescription()),
                na(order.getDetailDescription()), na(order.getPriceText()));

            String content = callDeepSeek("가상 고객 페르소나. JSON만 반환.", prompt);
            try {
                var r = objectMapper.readValue(content, Map.class);
                reactionRepo.save(PersonaReaction.create(orderId, selectedPersonaId, personaProfileId,
                        personaLabel(persona), "CORE_TARGET",
                        str(r.get("sentiment")), str(r.get("decisionStatus")),
                        toInt(r.get("purchaseIntentScore")), toInt(r.get("targetFitScore")),
                        toInt(r.get("priceAcceptanceScore")), toInt(r.get("trustScore")),
                        toInt(r.get("detailPageClarityScore")),
                        str(r.get("firstImpression")), str(r.get("likelyReaction")),
                        str(r.get("priceReaction")), str(r.get("trustReviewReaction")),
                        str(r.get("detailPageFeedback")), str(r.get("representativeQuote")),
                        toJson(r.get("positivePoints")), toJson(r.get("concerns")),
                        toJson(r.get("purchaseBarriers")), toJson(r.get("recommendedDetailPageFixes"))));
                successCount++;
            } catch (Exception e) {
                log.warn("Reaction generation failed for persona {}: {}", personaProfileId, e.getMessage());
            }
        }
        if (successCount == 0 && !personas.isEmpty()) {
            throw new RuntimeException("모든 페르소나 반응 생성 실패 (0/" + personas.size() + ")");
        }
        log.info("Reactions: {}/{} for orderId={}", successCount, personas.size(), orderId);
    }

    /**
     * 30명 반응을 DeepSeek으로 종합하여 최종 진단/분석/개선제안 생성.
     */
    @Transactional
    public void generateFinalReport(Long orderId) {
        var order = orderRepo.findById(orderId).orElseThrow();
        var reactions = reactionRepo.findByReportOrderId(orderId);
        if (reactions.isEmpty()) {
            throw new RuntimeException("취합할 페르소나 반응이 없습니다.");
        }

        double avgPurchase = reactions.stream().mapToInt(PersonaReaction::getPurchaseIntentScore).average().orElse(0);
        double avgFit = reactions.stream().mapToInt(PersonaReaction::getTargetFitScore).average().orElse(0);
        double avgPrice = reactions.stream().mapToInt(PersonaReaction::getPriceAcceptanceScore).average().orElse(0);
        double avgTrust = reactions.stream().mapToInt(PersonaReaction::getTrustScore).average().orElse(0);
        double avgClarity = reactions.stream().mapToInt(PersonaReaction::getDetailPageClarityScore).average().orElse(0);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(reactions.size(), 10); i++) {
            var r = reactions.get(i);
            sb.append(String.format("%d. %s | 구매:%d 적합:%d 가격:%d 신뢰:%d\n  인상:%s\n",
                    i + 1, r.getSegmentLabel(), r.getPurchaseIntentScore(), r.getTargetFitScore(),
                    r.getPriceAcceptanceScore(), r.getTrustScore(), na(r.getFirstImpression())));
        }

        String prompt = String.format("""
            %d명 가상고객 반응 종합. 상품:%s 가격:%s
            평균: 구매%.1f 적합%.1f 가격%.1f 신뢰%.1f 선명%.1f
            반응: %s
            JSON: {"finalVerdict":"...","executiveSummary":"...","purchaseIntentSummary":"...","priceSummary":"...","trustSummary":"...","targetValidationSummary":"...","segmentSummary":"...","improvementSummary":"...","riskSummary":"...","reportMarkdown":"..."}
            """, reactions.size(), order.getProjectName(), na(order.getPriceText()),
                avgPurchase, avgFit, avgPrice, avgTrust, avgClarity, sb.toString());

        String content = callDeepSeek("전문 이커머스 리포트 분석가. JSON만 반환.", prompt);
        try {
            var r = objectMapper.readValue(content, Map.class);
            // UPSERT
            var existing = finalReportRepo.findFirstByReportOrderIdOrderByIdDesc(orderId);
            existing.ifPresent(f -> finalReportRepo.delete(f));

            finalReportRepo.save(FinalReport.create(orderId, reactions.size(),
                    (int) Math.round(avgPurchase), (int) Math.round(avgFit), (int) Math.round(avgPrice),
                    (int) Math.round(avgTrust), (int) Math.round(avgClarity),
                    str(r.get("finalVerdict")), str(r.get("executiveSummary")),
                    str(r.get("purchaseIntentSummary")), str(r.get("priceSummary")),
                    str(r.get("trustSummary")), str(r.get("targetValidationSummary")),
                    str(r.get("segmentSummary")), str(r.get("improvementSummary")),
                    str(r.get("riskSummary")), str(r.get("reportMarkdown"))));
        } catch (Exception e) {
            log.error("Final report generation failed: {}", e.getMessage(), e);
            throw new RuntimeException("최종 리포트 생성 실패", e);
        }
    }

    // === helpers ===

    private String callDeepSeek(String systemPrompt, String userPrompt) {
        String auth = "Bearer " + System.getenv("DEEPSEEK_API_KEY");
        var req = new DeepSeekFeignClient.DeepSeekRequest(
                "deepseek-v4-flash", List.of(
                        new DeepSeekFeignClient.Message("system", systemPrompt),
                        new DeepSeekFeignClient.Message("user", userPrompt)),
                Map.of("type", "json_object"), 0.4, 6000, false);
        var resp = deepSeek.chatCompletion(auth, req);
        if (resp.choices() == null || resp.choices().isEmpty()) return "{}";
        var msg = resp.choices().get(0).message();
        String content = msg != null ? msg.content() : "{}";
        if (content.startsWith("```")) {
            content = content.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return content.trim();
    }

    private String personaLabel(Map<String, Object> p) {
        return na((String) p.get("age_group")) + " " + na((String) p.get("occupation")) + ", " + na((String) p.get("region"));
    }

    private String na(String s) { return s == null || s.isBlank() ? "미입력" : s.trim(); }
    private String str(Object o) { return o != null ? o.toString() : ""; }
    private String toJson(Object o) { try { return objectMapper.writeValueAsString(o != null ? o : List.of()); } catch (Exception e) { return "[]"; } }
    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }
}
