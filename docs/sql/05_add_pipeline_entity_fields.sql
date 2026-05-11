-- ================================================================================
-- PreCustomerReport: 파이프라인 엔티티 신규 필드 마이그레이션
-- 목적: ProductTargetProfile / PersonaReaction / FinalReport 신규 컬럼 추가
-- 원칙: DROP TABLE 없음, 데이터 보존, 멱등성(idempotent)
-- ================================================================================

BEGIN;

-- ================================================================================
-- Product Target Profile
-- ================================================================================
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS product_name TEXT;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS exclusion_keywords JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS audience_hypotheses JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS comparison_audiences JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS selection_weights JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS demographic_priors JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS sampling_strategy JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS report_focus_points JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS confidence NUMERIC(5,4);
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS raw_profile JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS page_snapshot_id BIGINT;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE product_target_profile ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- ================================================================================
-- Selected Persona
-- ================================================================================
-- NOTE:
-- selected_persona.target_profile_id is a legacy column name, but the current image
-- pipeline stores product_target_profile.id in it. Older migrated databases can
-- still have a foreign key to target_profile(id), which rejects valid selections.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.confrelid
        WHERE c.conrelid = 'selected_persona'::regclass
          AND c.conname = 'fk_reaction_selected_persona_target_profile'
          AND t.relname = 'target_profile'
    ) THEN
        ALTER TABLE selected_persona
            DROP CONSTRAINT fk_reaction_selected_persona_target_profile;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'selected_persona'::regclass
          AND conname = 'fk_selected_persona_product_target_profile'
    ) THEN
        UPDATE selected_persona sp
        SET target_profile_id = NULL
        WHERE target_profile_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM product_target_profile ptp
              WHERE ptp.id = sp.target_profile_id
          );

        ALTER TABLE selected_persona
            ADD CONSTRAINT fk_selected_persona_product_target_profile
            FOREIGN KEY (target_profile_id)
            REFERENCES product_target_profile(id)
            ON DELETE CASCADE;
    END IF;
END $$;

-- ================================================================================
-- Persona Reaction
-- ================================================================================
ALTER TABLE persona_reaction ADD COLUMN IF NOT EXISTS product_target_profile_id BIGINT;
ALTER TABLE persona_reaction ADD COLUMN IF NOT EXISTS selection_rank INTEGER;
ALTER TABLE persona_reaction ADD COLUMN IF NOT EXISTS missing_information JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE persona_reaction ADD COLUMN IF NOT EXISTS persuasion_messages JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE persona_reaction ADD COLUMN IF NOT EXISTS raw_response JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE persona_reaction ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE persona_reaction ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- UNIQUE constraint for ON CONFLICT (report_order_id, persona_profile_id, response_version)
-- constraint가 없으면 추가 (중복 방지를 위해)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_rpdpr_order_persona_version'
          AND conrelid = 'persona_reaction'::regclass
    ) THEN
        -- 먼저 중복 데이터 정리
        DELETE FROM persona_reaction
        WHERE ctid NOT IN (
            SELECT min(ctid)
            FROM persona_reaction
            GROUP BY report_order_id, persona_profile_id, COALESCE(response_version, '')
        );
        ALTER TABLE persona_reaction
            ADD CONSTRAINT uk_rpdpr_order_persona_version
            UNIQUE (report_order_id, persona_profile_id, response_version);
    END IF;
END $$;

-- ================================================================================
-- Final Report
-- ================================================================================
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS product_target_profile_id BIGINT;
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS page_snapshot_id BIGINT;
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS detail_page_summary TEXT;
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS report_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS aggregate_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS raw_response JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE final_report ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- UNIQUE constraint for ON CONFLICT (report_order_id, report_version)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_rdpfr_order_version'
          AND conrelid = 'final_report'::regclass
    ) THEN
        DELETE FROM final_report
        WHERE ctid NOT IN (
            SELECT min(ctid)
            FROM final_report
            GROUP BY report_order_id, COALESCE(report_version, '')
        );
        ALTER TABLE final_report
            ADD CONSTRAINT uk_rdpfr_order_version
            UNIQUE (report_order_id, report_version);
    END IF;
END $$;

COMMIT;

-- 확인
SELECT '=== 확인: product_target_profile 컬럼 ===' AS info;
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'product_target_profile'
ORDER BY ordinal_position;

SELECT '=== 확인: persona_reaction 컬럼 ===' AS info;
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'persona_reaction'
ORDER BY ordinal_position;

SELECT '=== 확인: final_report 컬럼 ===' AS info;
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'final_report'
ORDER BY ordinal_position;
