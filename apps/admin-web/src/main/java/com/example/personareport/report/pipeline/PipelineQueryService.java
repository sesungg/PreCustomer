package com.example.personareport.report.pipeline;

import com.example.personareport.report.domain.PersonaProfile;
import com.example.personareport.report.pipeline.entity.PageImageAnalysis;
import com.example.personareport.report.pipeline.entity.ProductTargetProfile;
import com.example.personareport.report.pipeline.entity.SelectedPersona;
import com.example.personareport.report.pipeline.repository.PageImageAnalysisRepository;
import com.example.personareport.report.pipeline.repository.PersonaReactionRepository;
import com.example.personareport.report.pipeline.repository.ProductTargetProfileRepository;
import com.example.personareport.report.pipeline.repository.SelectedPersonaRepository;
import com.example.personareport.report.repository.PersonaProfileRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 파이프라인 read-only DB 조회 서비스. @Transactional(readOnly=true)로 실행. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineQueryService {

    private final JdbcTemplate jdbc;
    private final PageImageAnalysisRepository imageAnalysisRepo;
    private final ProductTargetProfileRepository targetProfileRepo;
    private final SelectedPersonaRepository selectedPersonaRepo;
    private final PersonaProfileRepository personaProfileRepo;
    private final PersonaReactionRepository reactionRepo;

    // ── Image Analysis ─────────────────────────────────────────────

    /** 주문의 이미지 분석 결과를 조회. 없으면 빈 리스트 반환. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findImageAnalyses(Long orderId) {
        List<PageImageAnalysis> list = imageAnalysisRepo.findByReportOrderIdOrderByImagePathAscImagePartNoAsc(orderId);
        if (list.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var a : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("imagePath", str(a.getImagePath()));
            m.put("imageRole", str(a.getImageRole()));
            m.put("imagePartNo", a.getImagePartNo());
            m.put("imagePartCount", a.getImagePartCount());
            m.put("analysisVersion", str(a.getAnalysisVersion()));
            m.put("modelName", str(a.getModelName()));
            m.put("modelVersion", str(a.getModelVersion()));
            m.put("imageSummary", str(a.getImageSummary()));
            m.put("visibleText", str(a.getVisibleText()));
            m.put("visualTrustElements", toList(a.getVisualTrustElements()));
            m.put("visualPurchaseDrivers", toList(a.getVisualPurchaseDrivers()));
            m.put("visualPurchaseBarriers", toList(a.getVisualPurchaseBarriers()));
            m.put("visibleClaims", toList(a.getVisibleClaims()));
            m.put("visiblePrices", toList(a.getVisiblePrices()));
            m.put("visibleCertifications", toList(a.getVisibleCertifications()));
            m.put("visibleUsageInstructions", toList(a.getVisibleUsageInstructions()));
            m.put("designFeedback", toList(a.getDesignFeedback()));
            m.put("informationGaps", toList(a.getInformationGaps()));
            m.put("safetyOrComplianceNotes", toList(a.getSafetyOrComplianceNotes()));
            result.add(m);
        }
        return result;
    }

    // ── Target Profile ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<ProductTargetProfile> findLatestTargetProfile(Long orderId, String profileVersion) {
        return targetProfileRepo.findFirstByReportOrderIdAndProfileVersionOrderByUpdatedAtDescIdDesc(
                orderId, profileVersion != null ? profileVersion : "product_target_profile_v1");
    }

    @Transactional(readOnly = true)
    public Optional<ProductTargetProfile> findLatestTargetProfile(Long orderId) {
        return targetProfileRepo.findFirstByReportOrderIdOrderByIdDesc(orderId);
    }

    @Transactional(readOnly = true)
    public boolean hasTargetProfile(Long orderId, String profileVersion) {
        return findLatestTargetProfile(orderId, profileVersion).isPresent();
    }

    @Transactional(readOnly = true)
    public int countSelectedPersonasForLatestTargetProfile(Long orderId, String profileVersion) {
        var profile = findLatestTargetProfile(orderId, profileVersion);
        if (profile.isEmpty()) return 0;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM selected_persona
                WHERE report_order_id = ?
                  AND target_profile_id = ?
                """, Integer.class, orderId, profile.get().getId());
        return count != null ? count : 0;
    }

    @Transactional(readOnly = true)
    public boolean hasCompleteReactions(Long orderId, String responseVersion) {
        Integer selectedCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM selected_persona
                WHERE report_order_id = ?
                """, Integer.class, orderId);
        if (selectedCount == null || selectedCount == 0) return false;

        Integer reactionCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT sp.persona_profile_id)
                FROM selected_persona sp
                JOIN persona_reaction r
                  ON r.report_order_id = sp.report_order_id
                 AND r.persona_profile_id = sp.persona_profile_id
                 AND r.response_version = ?
                WHERE sp.report_order_id = ?
                """, Integer.class, responseVersion, orderId);
        return reactionCount != null && reactionCount >= selectedCount;
    }

    @Transactional(readOnly = true)
    public boolean hasFinalReport(Long orderId, String reportVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM final_report
                WHERE report_order_id = ?
                  AND report_version = ?
                """, Integer.class, orderId, reportVersion);
        return count != null && count > 0;
    }

    // ── Score Model ───────────────────────────────────────────────

    /** 가장 많이 사용된 persona_score_prediction model_version 조회. */
    @Transactional(readOnly = true)
    public String findLatestScoreModelVersion() {
        var list = jdbc.queryForList(
                "SELECT model_version FROM persona_score_prediction " +
                "GROUP BY model_version ORDER BY COUNT(*) DESC, MAX(created_at) DESC LIMIT 1");
        if (list.isEmpty()) throw new RuntimeException("persona_score_prediction에 데이터가 없습니다.");
        return (String) list.get(0).get("model_version");
    }

    // ── Persona Candidates ────────────────────────────────────────

    /**
     * mv_persona_normalized_score에서 selection_weights 기반 weighted rough_score로
     * candidateLimit만큼 후보를 조회한다.
     *
     * <p>개선 사항 (기존 대비):
     * <ul>
     *   <li>persona_profile / persona_source_record 조인 비용 제거 (MV에 역정규화)</li>
     *   <li>원점수 대신 백분위(pct, 0.0~1.0) 기반 rough_score 계산으로 스케일 통일</li>
     *   <li>Java 메모리에서 수행하던 computePercentile() 불필요 (MV에 사전 계산)</li>
     * </ul>
     *
     * <p>MV가 존재하지 않을 경우 {@link #findSelectionCandidatesFallback} 으로 자동 폴백.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findSelectionCandidates(int candidateLimit,
                                                             Map<String, Object> selectionWeights,
                                                             String scoreModelVersion) {
        if (!isMvAvailable()) {
            log.warn("[findSelectionCandidates] mv_persona_normalized_score 미존재 → fallback 쿼리 실행");
            return findSelectionCandidatesFallback(candidateLimit, selectionWeights, scoreModelVersion);
        }

        double wDigital = weight(selectionWeights, "digitalAffinity");
        double wPrice   = weight(selectionWeights, "priceSensitivity");
        double wTrust   = weight(selectionWeights, "trustSensitivity");
        double wConv    = weight(selectionWeights, "convenienceNeed");
        double wQuality = weight(selectionWeights, "qualitySensitivity");
        double wNovelty = weight(selectionWeights, "noveltyAcceptance");
        double wLocal   = weight(selectionWeights, "localAffinity");
        double wFamily  = weight(selectionWeights, "familyDecision");
        double wHealth  = weight(selectionWeights, "healthSafetySensitivity");
        double wReview  = weight(selectionWeights, "reviewDependency");

        // rough_score: 백분위(0.0~1.0) × 가중치 합산 → 0~10 스케일
        // prediction_confidence 보너스는 Java computeRawScore()와 동일하게 * 0.02 (100배 스케일 보정)
        String sql = """
            SELECT
                mv.persona_profile_id,
                mv.age_group,
                mv.gender,
                mv.region,
                mv.province,
                mv.district,
                mv.occupation,
                mv.education_level,
                mv.family_type,
                mv.housing_type,
                mv.professional_persona,
                mv.sports_persona,
                mv.arts_persona,
                mv.travel_persona,
                mv.culinary_persona,
                mv.family_persona,
                mv.hobbies_and_interests,
                mv.skills_and_expertise,
                mv.persona,
                mv.cultural_background,
                mv.career_goals_and_ambitions,
                NULL::TEXT AS personal_values,
                NULL::TEXT AS lifestyle,
                NULL::TEXT AS shopping_persona,
                NULL::TEXT AS media_consumption,
                mv.model_version AS score_model_version,
                mv.digital_affinity_score,
                mv.price_sensitivity_score,
                mv.trust_sensitivity_score,
                mv.convenience_need_score,
                mv.quality_sensitivity_score,
                mv.novelty_acceptance_score,
                mv.local_affinity_score,
                mv.family_decision_score,
                mv.health_safety_sensitivity_score,
                mv.review_dependency_score,
                mv.prediction_confidence,
                -- 백분위 점수 (Java computePercentile 대체: 이미 MV에 계산됨)
                mv.digital_affinity_pct,
                mv.price_sensitivity_pct,
                mv.trust_sensitivity_pct,
                mv.convenience_need_pct,
                mv.quality_sensitivity_pct,
                mv.novelty_acceptance_pct,
                mv.local_affinity_pct,
                mv.family_decision_pct,
                mv.health_safety_pct,
                mv.review_dependency_pct,
                -- rough_score: 백분위 × 가중치 합산 (0~1 스케일)
                (mv.digital_affinity_pct   * ? +
                 mv.price_sensitivity_pct  * ? +
                 mv.trust_sensitivity_pct  * ? +
                 mv.convenience_need_pct   * ? +
                 mv.quality_sensitivity_pct * ? +
                 mv.novelty_acceptance_pct * ? +
                 mv.local_affinity_pct     * ? +
                 mv.family_decision_pct    * ? +
                 mv.health_safety_pct      * ? +
                 mv.review_dependency_pct  * ? +
                 COALESCE(mv.prediction_confidence, 0) * 0.02
                ) AS rough_score
            FROM mv_persona_normalized_score mv
            WHERE mv.model_version = ?
            ORDER BY rough_score DESC
            LIMIT ?
            """;

        return jdbc.queryForList(sql,
                wDigital, wPrice, wTrust, wConv, wQuality,
                wNovelty, wLocal, wFamily, wHealth, wReview,
                scoreModelVersion, candidateLimit);
    }

    /**
     * mv_persona_normalized_score가 없을 때 사용하는 폴백 쿼리.
     * 기존 persona_score_prediction 직접 조회 방식 (원점수 기반 rough_score).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findSelectionCandidatesFallback(int candidateLimit,
                                                                      Map<String, Object> selectionWeights,
                                                                      String scoreModelVersion) {
        double wDigital = weight(selectionWeights, "digitalAffinity");
        double wPrice   = weight(selectionWeights, "priceSensitivity");
        double wTrust   = weight(selectionWeights, "trustSensitivity");
        double wConv    = weight(selectionWeights, "convenienceNeed");
        double wQuality = weight(selectionWeights, "qualitySensitivity");
        double wNovelty = weight(selectionWeights, "noveltyAcceptance");
        double wLocal   = weight(selectionWeights, "localAffinity");
        double wFamily  = weight(selectionWeights, "familyDecision");
        double wHealth  = weight(selectionWeights, "healthSafetySensitivity");
        double wReview  = weight(selectionWeights, "reviewDependency");

        String sql = """
            SELECT
                p.id AS persona_profile_id,
                p.age, p.age_group, p.gender, p.region, p.province, p.district,
                p.occupation, p.education_level, p.family_type, p.housing_type,
                s.professional_persona, s.sports_persona, s.arts_persona, s.travel_persona,
                s.culinary_persona, s.family_persona, s.hobbies_and_interests, s.skills_and_expertise,
                s.persona, s.cultural_background, s.career_goals_and_ambitions,
                NULL::TEXT AS personal_values, NULL::TEXT AS lifestyle,
                NULL::TEXT AS shopping_persona, NULL::TEXT AS media_consumption,
                sp.model_version AS score_model_version,
                sp.digital_affinity_score, sp.price_sensitivity_score,
                sp.trust_sensitivity_score, sp.convenience_need_score,
                sp.quality_sensitivity_score, sp.novelty_acceptance_score,
                sp.local_affinity_score, sp.family_decision_score,
                sp.health_safety_sensitivity_score, sp.review_dependency_score,
                sp.prediction_confidence,
                (sp.digital_affinity_score * ? + sp.price_sensitivity_score * ? +
                 sp.trust_sensitivity_score * ? + sp.convenience_need_score * ? +
                 sp.quality_sensitivity_score * ? + sp.novelty_acceptance_score * ? +
                 sp.local_affinity_score * ? + sp.family_decision_score * ? +
                 sp.health_safety_sensitivity_score * ? + sp.review_dependency_score * ?) AS rough_score
            FROM persona_score_prediction sp
            JOIN persona_profile p ON p.id = sp.persona_profile_id
            LEFT JOIN persona_source_record s ON s.id = p.source_record_id
            WHERE sp.model_version = ?
              AND p.active = TRUE
            ORDER BY rough_score DESC
            LIMIT ?
            """;

        return jdbc.queryForList(sql,
                wDigital, wPrice, wTrust, wConv, wQuality,
                wNovelty, wLocal, wFamily, wHealth, wReview,
                scoreModelVersion, candidateLimit);
    }

    /**
     * mv_persona_normalized_score Materialized View의 존재 여부를 확인한다.
     * 최초 1회만 DB를 조회하고 이후에는 캐시된 값을 반환한다.
     */
    private volatile Boolean mvAvailableCache = null;

    public boolean isMvAvailable() {
        if (mvAvailableCache != null) return mvAvailableCache;
        synchronized (this) {
            if (mvAvailableCache != null) return mvAvailableCache;
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pg_matviews WHERE matviewname = 'mv_persona_normalized_score'",
                        Integer.class);
                mvAvailableCache = count != null && count > 0;
            } catch (Exception e) {
                log.warn("[isMvAvailable] MV 존재 여부 확인 실패: {}", e.getMessage());
                mvAvailableCache = false;
            }
        }
        return mvAvailableCache;
    }

    /** MV 갱신 후 캐시를 초기화한다 (RefreshMvService에서 호출). */
    public void invalidateMvCache() {
        mvAvailableCache = null;
    }

    // ── Next Persona Window ───────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PersonaProfile> findCandidateWindow(String source, long startId, int limit) {
        return personaProfileRepo.findCandidateWindow(source, startId, null, limit);
    }

    @Transactional(readOnly = true)
    public List<PersonaProfile> findBySourceAndActive(String source) {
        return personaProfileRepo.findBySourceAndActiveTrue(source);
    }

    @Transactional(readOnly = true)
    public long findMinIdBySource(String source) {
        return personaProfileRepo.findMinActiveIdBySource(source);
    }

    @Transactional(readOnly = true)
    public long findMaxIdBySource(String source) {
        return personaProfileRepo.findMaxActiveIdBySource(source);
    }

    // ── Selected Personas for Reactions ───────────────────────────

    /**
     * 반응 생성용 selected_persona 목록 조회. persona_profile + persona_source_record JOIN 포함.
     * Python generate_reactions_for_detail_page_image.py fetch_selected_personas() 동일.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findSelectedPersonasWithDetails(Long orderId, String responseVersion,
                                                                      boolean skipExisting, Integer limit) {
        String sql = """
            SELECT
                rsp.id AS selected_persona_id,
                rsp.report_order_id,
                rsp.target_profile_id,
                rsp.persona_profile_id,
                rsp.selection_rank,
                rsp.selection_group,
                rsp.relevance_score,
                rsp.diversity_score,
                rsp.final_score,
                rsp.selection_reason,
                rsp.persona_score_model_version,
                p.age, p.age_group, p.gender, p.region, p.province, p.district,
                p.occupation, p.education_level, p.family_type, p.housing_type,
                s.professional_persona, s.sports_persona, s.arts_persona, s.travel_persona,
                s.culinary_persona, s.family_persona, s.hobbies_and_interests, s.skills_and_expertise,
                s.persona, s.cultural_background, s.career_goals_and_ambitions,
                NULL::TEXT AS personal_values, NULL::TEXT AS lifestyle,
                NULL::TEXT AS shopping_persona, NULL::TEXT AS media_consumption,
                sp.digital_affinity_score, sp.price_sensitivity_score,
                sp.trust_sensitivity_score, sp.convenience_need_score,
                sp.quality_sensitivity_score, sp.novelty_acceptance_score,
                sp.local_affinity_score, sp.family_decision_score,
                sp.health_safety_sensitivity_score, sp.review_dependency_score,
                existing.id AS existing_response_id
            FROM selected_persona rsp
            JOIN persona_profile p ON p.id = rsp.persona_profile_id
            LEFT JOIN persona_source_record s ON s.id = p.source_record_id
            LEFT JOIN LATERAL (
                SELECT sp2.* FROM persona_score_prediction sp2
                WHERE sp2.persona_profile_id = p.id
                ORDER BY sp2.created_at DESC, sp2.id DESC LIMIT 1
            ) sp ON TRUE
            LEFT JOIN persona_reaction existing
              ON existing.report_order_id = rsp.report_order_id
             AND existing.persona_profile_id = rsp.persona_profile_id
             AND existing.response_version = ?
            WHERE rsp.report_order_id = ?
              AND (? = FALSE OR existing.id IS NULL)
            ORDER BY rsp.selection_rank
            LIMIT ?
            """;
        return jdbc.queryForList(sql, responseVersion, orderId, skipExisting, limit != null ? limit : 1000000);
    }

    // ── Reactions for Report ──────────────────────────────────────

    /**
     * 최종 리포트 집계용 persona_reaction 목록 조회.
     * Python generate_final_detail_page_report_image.py fetch_responses() 동일.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findReactionsWithDetails(Long orderId, String responseVersion) {
        String sql = """
            SELECT r.*, p.age, p.age_group, p.gender, p.region, p.province, p.district,
                   p.occupation, p.education_level, p.family_type, p.housing_type
            FROM persona_reaction r
            JOIN persona_profile p ON p.id = r.persona_profile_id
            WHERE r.report_order_id = ?
              AND r.response_version = ?
            ORDER BY r.selection_rank
            """;
        return jdbc.queryForList(sql, orderId, responseVersion);
    }

    // ── Order ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> findOrderById(Long orderId) {
        var list = jdbc.queryForList(
                "SELECT * FROM report_order WHERE id = ?", orderId);
        if (list.isEmpty()) throw new RuntimeException("report_order를 찾을 수 없습니다. order_id=" + orderId);
        return list.get(0);
    }

    // ── Snapshot ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> findLatestSnapshot(Long orderId, Long snapshotId) {
        String sql;
        if (snapshotId != null) {
            sql = "SELECT * FROM page_snapshot WHERE id = " + snapshotId;
        } else {
            sql = "SELECT * FROM page_snapshot WHERE report_order_id = " + orderId +
                  " ORDER BY captured_at DESC, id DESC LIMIT 1";
        }
        var list = jdbc.queryForList(sql);
        return list.isEmpty() ? Collections.emptyMap() : list.get(0);
    }

    @Transactional(readOnly = true)
    public boolean hasPageSnapshot(Long orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_snapshot WHERE report_order_id = ?",
                Integer.class, orderId);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean hasImageAnalysesForPaths(Long orderId, List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty()) return true;
        String placeholders = String.join(",", Collections.nCopies(imagePaths.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(orderId);
        args.addAll(imagePaths);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT image_path)
                FROM page_image_analysis
                WHERE report_order_id = ?
                  AND image_path IN (%s)
                """.formatted(placeholders), Integer.class, args.toArray());
        return count != null && count >= imagePaths.size();
    }

    @Transactional(readOnly = true)
    public boolean hasReportShoppingAnalysis(Long orderId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM shopping_search_group g
                JOIN shopping_market_analysis_snapshot a ON a.search_group_id = g.id
                WHERE g.report_id = ?
                """, Integer.class, orderId);
        return count != null && count > 0;
    }

    // ── Page Snapshot by order ────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findPageSnapshotsByOrderId(Long orderId) {
        return jdbc.queryForList(
                "SELECT * FROM page_snapshot WHERE report_order_id = ? ORDER BY captured_at DESC, id DESC",
                orderId);
    }

    // ── helpers ───────────────────────────────────────────────────

    private double weight(Map<String, Object> weights, String key) {
        Object v = weights != null ? weights.get(key) : null;
        if (v instanceof Number n) return n.doubleValue();
        return 1.0;
    }

    @SuppressWarnings("unchecked")
    private List<Object> toList(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return Collections.emptyList();
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }
}
