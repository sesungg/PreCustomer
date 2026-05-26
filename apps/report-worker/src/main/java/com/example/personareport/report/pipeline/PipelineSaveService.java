package com.example.personareport.report.pipeline;

import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.report.pipeline.entity.FinalReport;
import com.example.personareport.report.pipeline.entity.PersonaReaction;
import com.example.personareport.report.pipeline.entity.ProductTargetProfile;
import com.example.personareport.report.pipeline.entity.SelectedPersona;
import com.example.personareport.report.pipeline.repository.FinalReportRepository;
import com.example.personareport.report.pipeline.repository.PersonaReactionRepository;
import com.example.personareport.report.pipeline.repository.ProductTargetProfileRepository;
import com.example.personareport.report.pipeline.repository.SelectedPersonaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 파이프라인 write DB 서비스. 모든 @Transactional 메서드는 짧은 트랜잭션으로 설계. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineSaveService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ProductTargetProfileRepository targetProfileRepo;
    private final SelectedPersonaRepository selectedPersonaRepo;
    private final PersonaReactionRepository reactionRepo;
    private final FinalReportRepository finalReportRepo;
    private final ReactionReportOrderRepository orderRepo;

    // ── Product Target Profile ────────────────────────────────────

    /** product_target_profile UPSERT (report_order_id + profile_version 기준). */
    @Transactional
    public ProductTargetProfile upsertTargetProfile(Long orderId, Long pageSnapshotId,
                                                     String profileVersion, String modelName, String modelVersion,
                                                     Map<String, Object> profile) {
        var existing = targetProfileRepo
                .findFirstByReportOrderIdAndProfileVersionOrderByUpdatedAtDescIdDesc(orderId, profileVersion);
        ProductTargetProfile entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new ProductTargetProfile();
        }

        // 리플렉션 대신 필드별 setter 직접 호출 (JPA field injection)
        try {
            var idField = ProductTargetProfile.class.getDeclaredField("reportOrderId");
            idField.setAccessible(true); idField.set(entity, orderId);
            var psField = ProductTargetProfile.class.getDeclaredField("pageSnapshotId");
            psField.setAccessible(true); psField.set(entity, pageSnapshotId);
            var pvField = ProductTargetProfile.class.getDeclaredField("profileVersion");
            pvField.setAccessible(true); pvField.set(entity, profileVersion);
            var mnField = ProductTargetProfile.class.getDeclaredField("modelName");
            mnField.setAccessible(true); mnField.set(entity, modelName);
            var mvField = ProductTargetProfile.class.getDeclaredField("modelVersion");
            mvField.setAccessible(true); mvField.set(entity, modelVersion);

            setField(entity, "productCategory", str(profile.get("productCategory")));
            setField(entity, "productType", str(profile.get("productType")));
            setField(entity, "productName", str(profile.get("productName")));
            setField(entity, "targetSummary", str(profile.get("targetSummary")));
            setField(entity, "coreKeywords", toJson(profile.get("coreKeywords")));
            setField(entity, "exclusionKeywords", toJson(profile.get("exclusionKeywords")));
            setField(entity, "purchaseDrivers", toJson(profile.get("purchaseDrivers")));
            setField(entity, "purchaseBarriers", toJson(profile.get("purchaseBarriers")));
            setField(entity, "audienceHypotheses", toJson(profile.get("audienceHypotheses")));
            setField(entity, "comparisonAudiences", toJson(profile.get("comparisonAudiences")));
            setField(entity, "selectionWeights", toJson(profile.get("selectionWeights")));
            setField(entity, "demographicPriors", toJson(profile.get("demographicPriors")));
            setField(entity, "samplingStrategy", toJson(profile.get("samplingStrategy")));
            setField(entity, "messageAngles", toJson(profile.get("messageAngles")));
            setField(entity, "reportFocusPoints", toJson(profile.get("reportFocusPoints")));
            setField(entity, "rawProfile", toJson(profile));
        } catch (Exception e) {
            throw new RuntimeException("ProductTargetProfile 필드 설정 실패", e);
        }

        return targetProfileRepo.save(entity);
    }

    // ── Selected Persona ──────────────────────────────────────────

    /** 기존 selected_persona 삭제. */
    @Transactional
    public void resetSelectedPersonas(Long orderId) {
        selectedPersonaRepo.deleteByReportOrderId(orderId);
    }

    /**
     * 상세페이지 전체 캡처 이미지가 있는 주문은 URL 크롤링 없이,
     * 업로드 이미지 분석 결과를 묶어 줄 기준 snapshot만 생성한다.
     */
    @Transactional
    public void upsertScreenshotPrimarySnapshot(Long orderId, List<Path> imagePaths) {
        Map<String, Object> order = jdbc.queryForList("SELECT * FROM report_order WHERE id = ?", orderId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("report_order를 찾을 수 없습니다. order_id=" + orderId));

        String pageUrl = nonBlank(str(order.get("page_url")), "manual://report-order/" + orderId);
        String projectName = str(order.get("project_name"));
        String priceText = str(order.get("price_text"));
        String shippingPolicyText = str(order.get("shipping_policy_text"));
        List<String> uploadedImages = normalizeImagePaths(order, imagePaths);
        List<Map<String, Object>> importantImages = uploadedImages.stream()
                .map(path -> {
                    Map<String, Object> image = new LinkedHashMap<String, Object>();
                    image.put("path", path);
                    image.put("role", "DETAIL_PAGE_SCREENSHOT");
                    image.put("source", "report_order.image_paths");
                    return image;
                })
                .toList();

        String visibleText = joinNonBlank(Arrays.asList(
                projectName != null ? "상품명: " + projectName : null,
                str(order.get("one_line_description")) != null ? "한 줄 설명: " + str(order.get("one_line_description")) : null,
                str(order.get("detail_description")) != null ? "상세 설명: " + str(order.get("detail_description")) : null,
                priceText != null ? "가격: " + priceText : null,
                shippingPolicyText != null ? "배송비 정책: " + shippingPolicyText : null,
                str(order.get("target_customer")) != null ? "타겟 고객: " + str(order.get("target_customer")) : null,
                str(order.get("main_question")) != null ? "핵심 질문: " + str(order.get("main_question")) : null,
                "업로드 캡처 이미지 수: " + uploadedImages.size(),
                "원본 URL: " + pageUrl
        ));
        Map<String, Object> rawMeta = new LinkedHashMap<>();
        rawMeta.put("fallbackSource", "report_order");
        rawMeta.put("fallbackReason", "SCREENSHOT_PRIMARY");
        rawMeta.put("analysisMode", "SCREENSHOT_PRIMARY");
        rawMeta.put("uploadedImageCount", uploadedImages.size());
        rawMeta.put("uploadedImages", uploadedImages);

        jdbc.update("""
                INSERT INTO page_snapshot (
                    report_order_id, page_url, source_site, snapshot_status,
                    page_title, product_title, price_text,
                    visible_text, extracted_text_summary, image_urls, important_images,
                    raw_meta_json, captured_at
                ) VALUES (?,?,?,?, ?,?,?, ?,?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CURRENT_TIMESTAMP)
                ON CONFLICT (report_order_id) DO UPDATE SET
                    page_url = EXCLUDED.page_url,
                    source_site = EXCLUDED.source_site,
                    snapshot_status = EXCLUDED.snapshot_status,
                    page_title = EXCLUDED.page_title,
                    product_title = EXCLUDED.product_title,
                    price_text = EXCLUDED.price_text,
                    visible_text = EXCLUDED.visible_text,
                    extracted_text_summary = EXCLUDED.extracted_text_summary,
                    image_urls = EXCLUDED.image_urls,
                    important_images = EXCLUDED.important_images,
                    raw_meta_json = EXCLUDED.raw_meta_json,
                    captured_at = EXCLUDED.captured_at
                """,
                orderId, pageUrl, nonBlank(extractDomain(pageUrl), "manual"), "SCREENSHOT_PRIMARY",
                projectName, projectName, priceText,
                visibleText, truncate(visibleText, 2000),
                toJson(List.of()), toJson(importantImages), toJson(rawMeta));
    }

    /** selected_persona 배치 저장. target_profile_id 컬럼에는 product_target_profile.id를 저장한다. */
    @Transactional
    public void saveSelectedPersonas(Long orderId, Long productTargetProfileId,
                                     List<Map<String, Object>> selected, String scoreModelVersion) {
        if (selected.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (var row : selected) {
            batch.add(new Object[]{
                    orderId, productTargetProfileId,
                    longVal(row.get("persona_profile_id")),
                    intVal(row.get("_selection_rank")),
                    str(row.get("_selection_group")),
                    doubleVal(row.get("_raw_score")),
                    doubleVal(row.get("_diversity_score")),
                    doubleVal(row.get("_final_score")),
                    str(row.get("_selection_reason")),
                    scoreModelVersion
            });
        }
        jdbc.batchUpdate("""
            INSERT INTO selected_persona
                (report_order_id, target_profile_id, persona_profile_id,
                 selection_rank, selection_group, relevance_score, diversity_score,
                 final_score, selection_reason, persona_score_model_version)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (report_order_id, persona_profile_id) DO UPDATE SET
                target_profile_id=EXCLUDED.target_profile_id,
                selection_rank=EXCLUDED.selection_rank,
                selection_group=EXCLUDED.selection_group,
                relevance_score=EXCLUDED.relevance_score,
                diversity_score=EXCLUDED.diversity_score,
                final_score=EXCLUDED.final_score,
                selection_reason=EXCLUDED.selection_reason,
                persona_score_model_version=EXCLUDED.persona_score_model_version
            """, batch);
    }

    // ── Persona Reaction ──────────────────────────────────────────

    /** response_version 기준 기존 반응 삭제. */
    @Transactional
    public void deleteReactionsByVersion(Long orderId, String responseVersion) {
        reactionRepo.deleteByReportOrderIdAndResponseVersion(orderId, responseVersion);
    }

    /** persona_reaction 배치 UPSERT. */
    @Transactional
    public void upsertReactions(Long orderId, Long productTargetProfileId,
                                 String responseVersion, String modelName, String modelVersion,
                                 List<Map<String, Object>> results) {
        if (results.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (var r : results) {
            batch.add(new Object[]{
                    orderId,
                    longVal(r.get("reactionSelectedPersonaId")),
                    longVal(r.get("personaProfileId")),
                    productTargetProfileId,
                    responseVersion, modelName, modelVersion,
                    str(r.get("selectionGroup")),
                    intVal(r.get("selectionRank")),
                    intVal(r.get("purchaseIntentScore")),
                    intVal(r.get("targetFitScore")),
                    intVal(r.get("priceAcceptanceScore")),
                    intVal(r.get("trustScore")),
                    intVal(r.get("detailPageClarityScore")),
                    str(r.get("sentiment")),
                    str(r.get("decisionStatus")),
                    str(r.get("firstImpression")),
                    str(r.get("likelyReaction")),
                    str(r.get("priceReaction")),
                    str(r.get("trustReviewReaction")),
                    str(r.get("detailPageFeedback")),
                    str(r.get("segmentLabel")),
                    str(r.get("representativeQuote")),
                    toJson(r.get("positivePoints")),
                    toJson(r.get("concerns")),
                    toJson(r.get("missingInformation")),
                    toJson(r.get("purchaseBarriers")),
                    toJson(r.get("persuasionMessages")),
                    toJson(r.get("recommendedDetailPageFixes")),
                    toJson(r.get("raw")),
            });
        }
        jdbc.batchUpdate("""
            INSERT INTO persona_reaction
                (report_order_id, selected_persona_id, persona_profile_id,
                 product_target_profile_id,
                 response_version, model_name, model_version,
                 selection_group, selection_rank,
                 purchase_intent_score, target_fit_score, price_acceptance_score,
                 trust_score, detail_page_clarity_score,
                 sentiment, decision_status,
                 first_impression, likely_reaction, price_reaction,
                 trust_review_reaction, detail_page_feedback,
                 segment_label, representative_quote,
                 positive_points, concerns, missing_information,
                 purchase_barriers, persuasion_messages,
                 recommended_detail_page_fixes, raw_response)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb)
            ON CONFLICT (report_order_id, persona_profile_id, response_version) DO UPDATE SET
                selected_persona_id=EXCLUDED.selected_persona_id,
                product_target_profile_id=EXCLUDED.product_target_profile_id,
                model_name=EXCLUDED.model_name,
                model_version=EXCLUDED.model_version,
                selection_group=EXCLUDED.selection_group,
                selection_rank=EXCLUDED.selection_rank,
                purchase_intent_score=EXCLUDED.purchase_intent_score,
                target_fit_score=EXCLUDED.target_fit_score,
                price_acceptance_score=EXCLUDED.price_acceptance_score,
                trust_score=EXCLUDED.trust_score,
                detail_page_clarity_score=EXCLUDED.detail_page_clarity_score,
                sentiment=EXCLUDED.sentiment,
                decision_status=EXCLUDED.decision_status,
                first_impression=EXCLUDED.first_impression,
                likely_reaction=EXCLUDED.likely_reaction,
                price_reaction=EXCLUDED.price_reaction,
                trust_review_reaction=EXCLUDED.trust_review_reaction,
                detail_page_feedback=EXCLUDED.detail_page_feedback,
                segment_label=EXCLUDED.segment_label,
                representative_quote=EXCLUDED.representative_quote,
                positive_points=EXCLUDED.positive_points,
                concerns=EXCLUDED.concerns,
                missing_information=EXCLUDED.missing_information,
                purchase_barriers=EXCLUDED.purchase_barriers,
                persuasion_messages=EXCLUDED.persuasion_messages,
                recommended_detail_page_fixes=EXCLUDED.recommended_detail_page_fixes,
                raw_response=EXCLUDED.raw_response
            """, batch);
    }

    // ── Final Report ──────────────────────────────────────────────

    /** final_report UPSERT (report_order_id + report_version 기준). */
    @Transactional
    public FinalReport upsertFinalReport(Long orderId, Long productTargetProfileId, Long pageSnapshotId,
                                          String reportVersion, String responseVersion,
                                          String modelName, String modelVersion,
                                          Map<String, Object> report,
                                          Map<String, Object> aggregate,
                                          Map<String, Object> rawReport) {
        var existing = finalReportRepo.findByReportOrderIdAndReportVersion(orderId, reportVersion);
        FinalReport entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new FinalReport();
        }

        try {
            var roField = FinalReport.class.getDeclaredField("reportOrderId");
            roField.setAccessible(true); roField.set(entity, orderId);
            var ptField = FinalReport.class.getDeclaredField("productTargetProfileId");
            ptField.setAccessible(true); ptField.set(entity, productTargetProfileId);
            var psField = FinalReport.class.getDeclaredField("pageSnapshotId");
            psField.setAccessible(true); psField.set(entity, pageSnapshotId);
            var rvField = FinalReport.class.getDeclaredField("reportVersion");
            rvField.setAccessible(true); rvField.set(entity, reportVersion);
            var rspField = FinalReport.class.getDeclaredField("responseVersion");
            rspField.setAccessible(true); rspField.set(entity, responseVersion);
            var mnField = FinalReport.class.getDeclaredField("modelName");
            mnField.setAccessible(true); mnField.set(entity, modelName);
            var mvField = FinalReport.class.getDeclaredField("modelVersion");
            mvField.setAccessible(true); mvField.set(entity, modelVersion);

            Map<String, Object> overall = safeMap(aggregate, "overall");
            Map<String, Object> avgScores = safeMap(overall, "avgScores");

            setField(entity, "responseCount", intVal(overall.get("responseCount")));
            setField(entity, "overallPurchaseIntentScore", intVal(avgScores.get("purchaseIntent")));
            setField(entity, "overallTargetFitScore", intVal(avgScores.get("targetFit")));
            setField(entity, "overallPriceAcceptanceScore", intVal(avgScores.get("priceAcceptance")));
            setField(entity, "overallTrustScore", intVal(avgScores.get("trust")));
            setField(entity, "overallDetailPageClarityScore", intVal(avgScores.get("detailPageClarity")));

            setField(entity, "finalVerdict", str(report.get("finalVerdict")));
            setField(entity, "executiveSummary", str(report.get("executiveSummary")));
            setField(entity, "targetValidationSummary", str(report.get("targetValidationSummary")));
            setField(entity, "purchaseIntentSummary", str(report.get("purchaseIntentSummary")));
            setField(entity, "priceSummary", str(report.get("priceSummary")));
            setField(entity, "trustSummary", str(report.get("trustSummary")));
            setField(entity, "detailPageSummary", str(report.get("detailPageSummary")));
            setField(entity, "segmentSummary", str(report.get("segmentSummary")));
            setField(entity, "improvementSummary", str(report.get("improvementSummary")));
            setField(entity, "riskSummary", str(report.get("riskSummary")));
            setField(entity, "reportMarkdown", str(report.get("reportMarkdown")));
            setField(entity, "reportJson", toJson(report));
            setField(entity, "aggregateJson", toJson(aggregate));
            setField(entity, "rawResponse", toJson(rawReport));
        } catch (Exception e) {
            throw new RuntimeException("FinalReport 필드 설정 실패", e);
        }

        return finalReportRepo.save(entity);
    }

    // ── Order Status ──────────────────────────────────────────────

    @Transactional
    public void markCompleted(Long orderId) {
        orderRepo.findById(orderId).ifPresent(ReactionReportOrder::markCompleted);
    }

    @Transactional
    public void markFailed(Long orderId) {
        orderRepo.findById(orderId).ifPresent(ReactionReportOrder::markFailed);
    }

    /**
     * 주문 단위 리포트 산출물만 삭제한다.
     * persona_profile/persona_feature_score/persona_label_* 등 페르소나 원본/라벨링 데이터는 건드리지 않는다.
     */
    @Transactional
    public void clearReportArtifacts(Long orderId) {
        if (orderId == null) return;

        jdbc.update("DELETE FROM final_report WHERE report_order_id = ?", orderId);
        jdbc.update("DELETE FROM persona_reaction WHERE report_order_id = ?", orderId);
        jdbc.update("DELETE FROM selected_persona WHERE report_order_id = ?", orderId);
        jdbc.update("DELETE FROM page_image_analysis WHERE report_order_id = ?", orderId);
        jdbc.update("DELETE FROM product_target_profile WHERE report_order_id = ?", orderId);
        clearShoppingArtifacts(orderId);
        jdbc.update("DELETE FROM page_snapshot WHERE report_order_id = ?", orderId);
        jdbc.update("DELETE FROM pipeline_progress WHERE order_id = ?", orderId);
    }

    private void clearShoppingArtifacts(Long orderId) {
        String groupFilter = "SELECT id FROM shopping_search_group WHERE report_id = ?";
        jdbc.update("""
            DELETE FROM shopping_candidate_product_role
            WHERE candidate_product_id IN (
                SELECT id FROM shopping_candidate_product
                WHERE report_id = ?
                   OR search_group_id IN (SELECT id FROM shopping_search_group WHERE report_id = ?)
            )
            """, orderId, orderId);
        jdbc.update("""
            DELETE FROM shopping_candidate_product
            WHERE report_id = ?
               OR search_group_id IN (SELECT id FROM shopping_search_group WHERE report_id = ?)
            """, orderId, orderId);
        jdbc.update("DELETE FROM shopping_market_analysis_snapshot WHERE search_group_id IN (" + groupFilter + ")", orderId);
        jdbc.update("DELETE FROM shopping_product_dedup_log WHERE search_group_id IN (" + groupFilter + ")", orderId);
        jdbc.update("DELETE FROM shopping_product_snapshot WHERE search_group_id IN (" + groupFilter + ")", orderId);
        jdbc.update("DELETE FROM shopping_search_snapshot WHERE search_group_id IN (" + groupFilter + ")", orderId);
        jdbc.update("DELETE FROM shopping_query_variant WHERE search_group_id IN (" + groupFilter + ")", orderId);
        jdbc.update("DELETE FROM shopping_search_group WHERE report_id = ?", orderId);
    }

    // ── helpers ───────────────────────────────────────────────────

    private void setField(Object entity, String fieldName, Object value) throws Exception {
        var field = entity.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(entity, value);
    }

    private String str(Object o) { return o != null ? o.toString() : null; }
    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String joinNonBlank(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private List<String> normalizeImagePaths(Map<String, Object> order, List<Path> imagePaths) {
        if (imagePaths != null && !imagePaths.isEmpty()) {
            return imagePaths.stream()
                    .map(path -> path.toAbsolutePath().toString())
                    .toList();
        }
        String raw = str(order.get("image_paths"));
        if (raw == null || raw.isBlank()) return List.of();
        return raw.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String extractDomain(String url) {
        if (url == null) return null;
        var matcher = java.util.regex.Pattern.compile("https?://([^/]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Long longVal(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) try { return Long.parseLong(o.toString()); } catch (Exception ignored) {}
        return null;
    }
    private Integer intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) try { return Integer.parseInt(o.toString().replaceAll("[^0-9\\-]", "")); } catch (Exception ignored) {}
        return null;
    }
    private Double doubleVal(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) try { return Double.parseDouble(o.toString().replaceAll("[^0-9.\\-]", "")); } catch (Exception ignored) {}
        return null;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Map<String, Object> parent, String key) {
        if (parent == null) return Map.of();
        Object v = parent.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return Map.of();
    }
    private String toJson(Object o) {
        if (o == null) return null;
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}
