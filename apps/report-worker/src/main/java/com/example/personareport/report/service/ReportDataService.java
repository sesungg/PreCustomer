package com.example.personareport.report.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 리포트 데이터 조회 서비스. final_report/persona_reaction/persona_profile 쿼리를 JdbcTemplate으로 직접 실행. */
@Service
@RequiredArgsConstructor
public class ReportDataService {

    private final JdbcTemplate jdbc;

    /** orderId 기준 최신 final_report 1건 조회. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findReportByOrderId(Long orderId) {
        return jdbc.queryForList(
                "SELECT * FROM final_report WHERE report_order_id = ? ORDER BY id DESC LIMIT 1", orderId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findOrderById(Long orderId) {
        return jdbc.queryForList("SELECT * FROM report_order WHERE id = ?", orderId);
    }

    @Transactional(readOnly = true)
    public long countReportByOrderId(Long orderId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM final_report WHERE report_order_id = ?", Long.class, orderId);
        return count != null ? count : 0;
    }

    /** 고객군별 집계 (인원수/평균점수). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findSegments(Long orderId) {
        return jdbc.queryForList("""
                SELECT selection_group,
                       COUNT(*) as cnt,
                       ROUND(AVG(purchase_intent_score), 0) as avg_purchase,
                       ROUND(AVG(target_fit_score), 0) as avg_fit,
                       ROUND(AVG(price_acceptance_score), 0) as avg_price,
                       ROUND(AVG(trust_score), 0) as avg_trust,
                       ROUND(AVG(detail_page_clarity_score), 0) as avg_clarity
                FROM persona_reaction WHERE report_order_id = ?
                GROUP BY selection_group ORDER BY selection_group
                """, orderId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findSentiments(Long orderId) {
        return jdbc.queryForList(
                "SELECT sentiment, COUNT(*) as cnt FROM persona_reaction WHERE report_order_id = ? GROUP BY sentiment ORDER BY cnt DESC",
                orderId);
    }

    /** 페르소나 반응 목록. persona_profile과 JOIN하여 인구통계 정보 포함. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findReactions(Long orderId) {
        return jdbc.queryForList("""
                SELECT r.id, r.segment_label, r.selection_group, r.sentiment, r.decision_status,
                       r.purchase_intent_score, r.target_fit_score, r.price_acceptance_score,
                       r.trust_score, r.detail_page_clarity_score,
                       r.representative_quote, r.likely_reaction, r.first_impression,
                       COALESCE(p.age_group, '미입력') as age_group,
                       COALESCE(p.region, '미입력') as region,
                       COALESCE(p.occupation, '미입력') as occupation,
                       COALESCE(p.gender, '미입력') as gender
                FROM persona_reaction r
                LEFT JOIN persona_profile p ON p.id = r.persona_profile_id
                WHERE r.report_order_id = ?
                ORDER BY r.selection_rank, r.purchase_intent_score DESC
                """, orderId);
    }

    /** 구매 결정 상태 분포 (BUY/CONSIDER/HESITATE/NOT_BUY/UNDECIDED). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findDecisions(Long orderId) {
        return jdbc.queryForList(
                "SELECT decision_status, COUNT(*) as cnt FROM persona_reaction WHERE report_order_id = ? GROUP BY decision_status ORDER BY cnt DESC",
                orderId);
    }

    /** 개별 페르소나 반응 상세. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findReactionById(Long reactionId) {
        return jdbc.queryForList("""
                SELECT r.*, COALESCE(p.age_group, '미입력') as age_group,
                       COALESCE(p.region, '미입력') as region,
                       COALESCE(p.occupation, '미입력') as occupation,
                       COALESCE(p.gender, '미입력') as gender
                FROM persona_reaction r
                LEFT JOIN persona_profile p ON p.id = r.persona_profile_id
                WHERE r.id = ?
                """, reactionId);
    }

    /** URL 크롤링 결과 최신 1건. 성공/실패 상태와 raw_meta_json을 화면 근거로 노출한다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findLatestPageSnapshot(Long orderId) {
        return jdbc.queryForList(
                "SELECT * FROM page_snapshot WHERE report_order_id = ? ORDER BY captured_at DESC, id DESC LIMIT 1",
                orderId);
    }

    /** 이미지 분석 결과. URL 데이터와 섞지 않고 별도 출처로 보여준다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findImageAnalyses(Long orderId) {
        return jdbc.queryForList("""
                SELECT id, image_path, image_role, image_part_no, image_part_count,
                       image_summary, visible_text, visual_trust_elements,
                       visual_purchase_drivers, visual_purchase_barriers,
                       visible_claims, visible_prices, visible_certifications,
                       visible_usage_instructions, information_gaps,
                       safety_or_compliance_notes
                FROM page_image_analysis
                WHERE report_order_id = ?
                ORDER BY image_path ASC, image_part_no ASC, id ASC
                """, orderId);
    }

    /** 네이버 쇼핑 가격 분석 최신 1건. 배송비 미수집 같은 한계도 함께 노출한다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findLatestShoppingEvidence(Long orderId) {
        var groups = jdbc.queryForList(
                "SELECT * FROM shopping_search_group WHERE report_id = ? ORDER BY id DESC LIMIT 1",
                orderId);
        if (groups.isEmpty()) return List.of();

        var evidence = new java.util.LinkedHashMap<String, Object>(groups.get(0));
        var analyses = jdbc.queryForList(
                "SELECT * FROM shopping_market_analysis_snapshot WHERE search_group_id = ? ORDER BY id DESC LIMIT 1",
                evidence.get("id"));
        if (!analyses.isEmpty()) {
            analyses.get(0).forEach((key, value) -> evidence.put("analysis_" + key, value));
        }
        return List.of(evidence);
    }

    /** 네이버 쇼핑 비교 후보 목록. 현재 스키마에는 배송비/리뷰/평점이 없으므로 화면에서 미수집으로 표시한다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findShoppingComparisonProducts(Long orderId, int limit) {
        try {
            return jdbc.queryForList("""
                    WITH latest_group AS (
                        SELECT id
                        FROM shopping_search_group
                        WHERE report_id = ?
                        ORDER BY id DESC
                        LIMIT 1
                    )
                    SELECT cp.id AS candidate_id,
                           cp.candidate_score,
                           cp.category_match_score,
                           cp.title_similarity_score,
                           cp.price_range_score,
                           cp.data_confidence AS candidate_confidence,
                           cp.candidate_reason,
                           ps.title_clean,
                           ps.title_raw,
                           ps.mall_name,
                           ps.lprice,
                           ps.hprice,
                           ps.brand_raw,
                           ps.maker_raw,
                           ps.category1,
                           ps.category2,
                           ps.category3,
                           ps.category4,
                           ps.product_url,
                           ps.image_url,
                           ps.sort_type,
                           ps.original_rank,
                           ps.data_quality_score,
                           ps.data_confidence,
                           STRING_AGG(cpr.role_type, ', ' ORDER BY cpr.role_type) AS roles
                    FROM shopping_candidate_product cp
                    JOIN shopping_product_snapshot ps ON ps.id = cp.product_snapshot_id
                    LEFT JOIN shopping_candidate_product_role cpr ON cpr.candidate_product_id = cp.id
                    WHERE cp.search_group_id = (SELECT id FROM latest_group)
                    GROUP BY cp.id, ps.id
                    ORDER BY cp.candidate_score DESC, ps.lprice ASC NULLS LAST
                    LIMIT ?
                    """, orderId, limit);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
