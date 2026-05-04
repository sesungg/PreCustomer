package com.example.personareport.report.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportDataService {

    private final JdbcTemplate jdbc;

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
}
