-- ================================================================================
-- PreCustomerReport: report pipeline performance indexes
-- Purpose: speed up resume checks, final aggregation, and report-scoped shopping reuse.
--
-- Run outside an explicit transaction because CREATE INDEX CONCURRENTLY cannot run
-- inside BEGIN/COMMIT.
-- ================================================================================

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_rpdpr_order_version_rank
    ON persona_reaction (report_order_id, response_version, selection_rank);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ssg_report_candidate_ready
    ON shopping_search_group (report_id)
    WHERE candidate_count > 0;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ptp_order_profile_updated
    ON product_target_profile (report_order_id, profile_version, updated_at DESC, id DESC);

-- ================================================================================
-- Manual review candidates, intentionally not executed here.
-- These are duplicate or near-duplicate legacy indexes found in the local DB.
-- Drop only after checking production pg_stat_user_indexes over a representative window.
-- ================================================================================

-- selected_persona duplicates:
-- DROP INDEX CONCURRENTLY IF EXISTS idx_selected_persona_order_id;
-- DROP INDEX CONCURRENTLY IF EXISTS idx_selected_persona_rank;

-- product_target_profile duplicates:
-- DROP INDEX CONCURRENTLY IF EXISTS idx_reaction_product_target_profile_order_id;
-- DROP INDEX CONCURRENTLY IF EXISTS idx_reaction_product_target_profile_category;
-- DROP INDEX CONCURRENTLY IF EXISTS gin_reaction_product_target_profile_core_keywords;

-- page_snapshot/order and page_image_analysis/order are likely covered by unique
-- prefixes, but keep them until production query stats confirm they are unused:
-- DROP INDEX CONCURRENTLY IF EXISTS idx_reaction_report_page_snapshot_order_id;
-- DROP INDEX CONCURRENTLY IF EXISTS idx_rdpia_order_id;
