-- ================================================================================
-- PreCustomerReport: Normalized Score Materialized View
-- 목적: persona_score_prediction의 10개 성향 원점수를 model_version 파티션 기준으로
--       백분위(PERCENT_RANK) 정규화하고, 키워드 검색용 tsvector를 사전 계산하여
--       페르소나 선별(selectPersonas) 성능을 개선한다.
--
-- 효과:
--   - Java 메모리에서 수행하던 Percentile 계산을 DB 레벨로 이관
--   - Full Text Search(GIN 인덱스)로 키워드 매칭 속도 향상
--   - 후보 조회 시 persona_profile / persona_source_record 조인 비용 제거
--
-- 갱신 전략:
--   - ML 파이프라인(predict_persona_scores.py) 완료 직후 CONCURRENTLY 갱신
--   - 갱신 중에도 SELECT 블로킹 없음 (Unique Index 필수)
--
-- 실행 방법:
--   psql -f 07_create_normalized_score_mv.sql
--   (CREATE MATERIALIZED VIEW는 트랜잭션 외부에서 실행해야 함)
-- ================================================================================

-- ── Materialized View 생성 ────────────────────────────────────────────────────

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_persona_normalized_score AS
SELECT
    sp.persona_profile_id,
    sp.model_version,

    -- ── 원점수 (Raw Scores, 0~100) ──────────────────────────────────────────
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

    -- ── 백분위 점수 (Percentile Rank: 0.0 ~ 1.0, model_version 파티션 기준) ──
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

    -- ── 인구통계 (역정규화: 조인 비용 제거) ────────────────────────────────────
    p.age_group,
    p.gender,
    p.region,
    p.province,
    p.district,
    p.occupation,
    p.education_level,
    p.family_type,
    p.housing_type,

    -- ── 페르소나 텍스트 (역정규화: 조인 비용 제거) ─────────────────────────────
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

    -- ── 키워드 검색용 통합 tsvector (GIN 인덱스 대상) ──────────────────────────
    -- Java keywordScore()와 동일한 10개 필드를 합산
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
WHERE p.active = TRUE;

-- ── 인덱스 생성 ───────────────────────────────────────────────────────────────

-- CONCURRENTLY 갱신을 위한 필수 Unique Index
CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_persona_score_pk
    ON mv_persona_normalized_score (persona_profile_id, model_version);

-- 모델 버전 필터링 (findSelectionCandidates WHERE model_version = ?)
CREATE INDEX IF NOT EXISTS idx_mv_persona_score_model_version
    ON mv_persona_normalized_score (model_version);

-- 키워드 검색용 GIN 인덱스
CREATE INDEX IF NOT EXISTS idx_mv_persona_score_search_vector
    ON mv_persona_normalized_score USING GIN (search_vector);

-- ── 확인 쿼리 ─────────────────────────────────────────────────────────────────

SELECT '=== mv_persona_normalized_score 생성 완료 ===' AS info;
SELECT
    model_version,
    COUNT(*) AS persona_count,
    ROUND(AVG(digital_affinity_score)::numeric, 2) AS avg_digital,
    ROUND(AVG(price_sensitivity_score)::numeric, 2) AS avg_price
FROM mv_persona_normalized_score
GROUP BY model_version
ORDER BY model_version;
