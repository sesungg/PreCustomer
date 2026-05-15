package com.example.personareport.report.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * mv_persona_normalized_score Materialized View 갱신 서비스.
 *
 * <p>ML 파이프라인(predict_persona_scores.py)이 persona_score_prediction 테이블에
 * 새로운 예측 점수를 적재한 직후에 이 서비스를 호출하여 MV를 최신 상태로 유지한다.
 *
 * <h3>갱신 전략</h3>
 * <ul>
 *   <li>{@code REFRESH MATERIALIZED VIEW CONCURRENTLY}: 갱신 중에도 SELECT 블로킹 없음.
 *       단, Unique Index(idx_mv_persona_score_pk)가 반드시 존재해야 한다.</li>
 *   <li>갱신 완료 후 {@link PipelineQueryService#invalidateMvCache()}를 호출하여
 *       MV 존재 여부 캐시를 초기화한다.</li>
 * </ul>
 *
 * <h3>호출 시점</h3>
 * <ol>
 *   <li>ML 배치 적재 완료 후 외부 API 또는 스케줄러에서 직접 호출</li>
 *   <li>관리자 화면에서 수동 갱신 버튼 클릭 시 {@code /admin/mv/refresh} 엔드포인트 경유</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaNormalizedScoreMvService {

    private static final String MV_NAME = "mv_persona_normalized_score";

    private final JdbcTemplate jdbc;
    private final PipelineQueryService queryService;

    // ── 갱신 ──────────────────────────────────────────────────────────────────

    /**
     * MV를 CONCURRENTLY 방식으로 갱신한다.
     *
     * <p>MV가 존재하지 않으면 먼저 생성을 시도한다.
     * 갱신 완료 후 MV 존재 여부 캐시를 초기화하여 다음 조회 시 최신 상태를 반영한다.
     *
     * @return 갱신 후 MV의 행 수
     */
    public long refresh() {
        if (!isMvPresent()) {
            log.warn("[PersonaNormalizedScoreMvService] MV 미존재 → 생성 시도");
            createMv();
        }

        log.info("[PersonaNormalizedScoreMvService] REFRESH MATERIALIZED VIEW CONCURRENTLY 시작");
        long start = System.currentTimeMillis();

        // CONCURRENTLY는 트랜잭션 외부에서 실행해야 함 (JdbcTemplate은 기본 auto-commit)
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + MV_NAME);

        long elapsed = System.currentTimeMillis() - start;
        long rowCount = countRows();
        log.info("[PersonaNormalizedScoreMvService] 갱신 완료: rows={}, elapsed={}ms", rowCount, elapsed);

        // MV 존재 여부 캐시 초기화
        queryService.invalidateMvCache();

        return rowCount;
    }

    /**
     * MV를 전체 재생성한다 (DROP → CREATE).
     *
     * <p>스키마 변경이나 인덱스 재구성이 필요할 때 사용한다.
     * 재생성 중에는 SELECT가 블로킹될 수 있으므로 유지보수 시간대에 실행을 권장한다.
     *
     * @return 재생성 후 MV의 행 수
     */
    public long recreate() {
        log.info("[PersonaNormalizedScoreMvService] MV 전체 재생성 시작");
        long start = System.currentTimeMillis();

        dropMvIfExists();
        createMv();

        long elapsed = System.currentTimeMillis() - start;
        long rowCount = countRows();
        log.info("[PersonaNormalizedScoreMvService] 재생성 완료: rows={}, elapsed={}ms", rowCount, elapsed);

        queryService.invalidateMvCache();
        return rowCount;
    }

    // ── 상태 조회 ──────────────────────────────────────────────────────────────

    /**
     * MV의 현재 행 수를 반환한다. MV가 없으면 -1을 반환한다.
     */
    public long countRows() {
        if (!isMvPresent()) return -1L;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + MV_NAME, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * MV의 model_version별 통계를 반환한다.
     */
    public java.util.List<java.util.Map<String, Object>> getStats() {
        if (!isMvPresent()) return java.util.List.of();
        return jdbc.queryForList("""
                SELECT
                    model_version,
                    COUNT(*) AS persona_count,
                    ROUND(AVG(digital_affinity_score)::numeric, 2) AS avg_digital,
                    ROUND(AVG(price_sensitivity_score)::numeric, 2) AS avg_price,
                    ROUND(AVG(trust_sensitivity_score)::numeric, 2) AS avg_trust,
                    MIN(digital_affinity_pct) AS min_digital_pct,
                    MAX(digital_affinity_pct) AS max_digital_pct
                FROM mv_persona_normalized_score
                GROUP BY model_version
                ORDER BY model_version
                """);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────────

    private boolean isMvPresent() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_matviews WHERE matviewname = ?",
                Integer.class, MV_NAME);
        return count != null && count > 0;
    }

    private void dropMvIfExists() {
        log.info("[PersonaNormalizedScoreMvService] DROP MATERIALIZED VIEW IF EXISTS {}", MV_NAME);
        jdbc.execute("DROP MATERIALIZED VIEW IF EXISTS " + MV_NAME);
    }

    private void createMv() {
        log.info("[PersonaNormalizedScoreMvService] CREATE MATERIALIZED VIEW {}", MV_NAME);
        jdbc.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS mv_persona_normalized_score AS
                SELECT
                    sp.persona_profile_id,
                    sp.model_version,
                    sp.digital_affinity_score,
                    sp.price_sensitivity_score,
                    sp.trust_sensitivity_score,
                    sp.convenience_need_score,
                    sp.quality_sensitivity_score,
                    sp.novelty_acceptance_score,
                    sp.local_affinity_score,
                    sp.family_decision_score,
                    sp.health_safety_sensitivity_score,
                    sp.review_dependency_score,
                    sp.prediction_confidence,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.digital_affinity_score)          AS digital_affinity_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.price_sensitivity_score)         AS price_sensitivity_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.trust_sensitivity_score)         AS trust_sensitivity_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.convenience_need_score)          AS convenience_need_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.quality_sensitivity_score)       AS quality_sensitivity_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.novelty_acceptance_score)        AS novelty_acceptance_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.local_affinity_score)            AS local_affinity_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.family_decision_score)           AS family_decision_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.health_safety_sensitivity_score) AS health_safety_pct,
                    PERCENT_RANK() OVER (PARTITION BY sp.model_version ORDER BY sp.review_dependency_score)         AS review_dependency_pct,
                    p.age_group,
                    p.gender,
                    p.region,
                    p.province,
                    p.district,
                    p.occupation,
                    p.education_level,
                    p.family_type,
                    p.housing_type,
                    s.professional_persona,
                    s.sports_persona,
                    s.arts_persona,
                    s.travel_persona,
                    s.culinary_persona,
                    s.family_persona,
                    s.hobbies_and_interests,
                    s.skills_and_expertise,
                    s.persona,
                    s.cultural_background,
                    s.career_goals_and_ambitions,
                    to_tsvector('simple',
                        coalesce(p.occupation, '') || ' ' ||
                        coalesce(p.education_level, '') || ' ' ||
                        coalesce(p.family_type, '') || ' ' ||
                        coalesce(p.housing_type, '') || ' ' ||
                        coalesce(s.professional_persona, '') || ' ' ||
                        coalesce(s.sports_persona, '') || ' ' ||
                        coalesce(s.culinary_persona, '') || ' ' ||
                        coalesce(s.hobbies_and_interests, '') || ' ' ||
                        coalesce(s.skills_and_expertise, '') || ' ' ||
                        coalesce(s.travel_persona, '')
                    ) AS search_vector
                FROM persona_score_prediction sp
                JOIN persona_profile p ON p.id = sp.persona_profile_id
                LEFT JOIN persona_source_record s ON s.id = p.source_record_id
                WHERE p.active = TRUE
                """);

        // CONCURRENTLY 갱신을 위한 필수 Unique Index
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_persona_score_pk
                    ON mv_persona_normalized_score (persona_profile_id, model_version)
                """);

        // model_version 필터링 인덱스
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_mv_persona_score_model_version
                    ON mv_persona_normalized_score (model_version)
                """);

        // 키워드 검색용 GIN 인덱스
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_mv_persona_score_search_vector
                    ON mv_persona_normalized_score USING GIN (search_vector)
                """);

        log.info("[PersonaNormalizedScoreMvService] MV 및 인덱스 생성 완료");
    }
}
