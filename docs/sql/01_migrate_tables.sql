-- ================================================================================
-- PreCustomerReport: 테이블 정리 마이그레이션
-- 목적: 구버전 테이블 → archive 이동, 운영 테이블명 단축
-- 원칙: DROP TABLE 없음, 데이터 보존, 멱등성(idempotent)
-- ================================================================================

BEGIN;

-- ================================================================================
-- STEP 1: archive 스키마 생성
-- ================================================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'archive') THEN
        CREATE SCHEMA archive;
        RAISE NOTICE 'STEP 1: archive 스키마 생성 완료';
    ELSE
        RAISE NOTICE 'STEP 1: archive 스키마 이미 존재함 (통과)';
    END IF;
END $$;

-- ================================================================================
-- STEP 2: 구버전 persona_reaction → archive 이동
-- (reaction_persona_detail_page_response를 persona_reaction으로 리네이밍하기 위한 선행 작업)
-- ================================================================================
DO $$
DECLARE
    v_new_name TEXT;
BEGIN
    -- 구버전 persona_reaction은 reaction_persona_detail_page_response가 아직 리네이밍되기 전에만
    -- public에 존재한다. 둘 다 존재하면 persona_reaction이 구버전이다.
    -- reaction_persona_detail_page_response가 이미 리네이밍된 상태면 persona_reaction은 운영 테이블이므로 건들지 않는다.
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'persona_reaction'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_persona_detail_page_response'
    ) THEN
        -- 둘 다 존재 → persona_reaction은 구버전 → archive로 이동
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'archive' AND table_name = 'persona_reaction'
        ) THEN
            v_new_name := 'persona_reaction_' || to_char(now(), 'YYYYMMDD_HH24MISS');
            RAISE NOTICE 'STEP 2: archive.persona_reaction 이미 존재 → archive.% 로 이동', v_new_name;
            EXECUTE format('ALTER TABLE public.persona_reaction SET SCHEMA archive');
            EXECUTE format('ALTER TABLE archive.persona_reaction RENAME TO %I', v_new_name);
        ELSE
            RAISE NOTICE 'STEP 2: public.persona_reaction(구버전) → archive.persona_reaction 으로 이동';
            ALTER TABLE public.persona_reaction SET SCHEMA archive;
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'persona_reaction'
    ) THEN
        -- persona_reaction만 존재 (reaction_persona_detail_page_response 없음) → 이미 운영 테이블
        RAISE NOTICE 'STEP 2: public.persona_reaction 은 운영 테이블 (건너뜀)';
    ELSE
        RAISE NOTICE 'STEP 2: public.persona_reaction 존재하지 않음 (통과)';
    END IF;
END $$;

-- ================================================================================
-- STEP 3: 운영 테이블 리네이밍 (public에 유지, 이름 단축)
-- ================================================================================

-- 3-1. reaction_report_order → report_order
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_report_order'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'report_order'
        ) THEN
            RAISE NOTICE 'STEP 3-1: public.report_order 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_report_order RENAME TO report_order;
            RAISE NOTICE 'STEP 3-1: reaction_report_order → report_order 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-1: reaction_report_order 존재하지 않음 (통과)';
    END IF;
END $$;

-- 3-2. reaction_report_page_snapshot → page_snapshot
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_report_page_snapshot'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'page_snapshot'
        ) THEN
            RAISE NOTICE 'STEP 3-2: public.page_snapshot 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_report_page_snapshot RENAME TO page_snapshot;
            RAISE NOTICE 'STEP 3-2: reaction_report_page_snapshot → page_snapshot 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-2: reaction_report_page_snapshot 존재하지 않음 (통과)';
    END IF;
END $$;

-- 3-3. reaction_product_target_profile → product_target_profile
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_product_target_profile'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'product_target_profile'
        ) THEN
            RAISE NOTICE 'STEP 3-3: public.product_target_profile 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_product_target_profile RENAME TO product_target_profile;
            RAISE NOTICE 'STEP 3-3: reaction_product_target_profile → product_target_profile 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-3: reaction_product_target_profile 존재하지 않음 (통과)';
    END IF;
END $$;

-- 3-4. reaction_target_profile → target_profile
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_target_profile'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'target_profile'
        ) THEN
            RAISE NOTICE 'STEP 3-4: public.target_profile 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_target_profile RENAME TO target_profile;
            RAISE NOTICE 'STEP 3-4: reaction_target_profile → target_profile 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-4: reaction_target_profile 존재하지 않음 (통과)';
    END IF;
END $$;

-- 3-5. reaction_selected_persona → selected_persona
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_selected_persona'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'selected_persona'
        ) THEN
            RAISE NOTICE 'STEP 3-5: public.selected_persona 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_selected_persona RENAME TO selected_persona;
            RAISE NOTICE 'STEP 3-5: reaction_selected_persona → selected_persona 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-5: reaction_selected_persona 존재하지 않음 (통과)';
    END IF;
END $$;

-- 3-6. reaction_persona_detail_page_response → persona_reaction
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_persona_detail_page_response'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'persona_reaction'
        ) THEN
            RAISE NOTICE 'STEP 3-6: public.persona_reaction 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_persona_detail_page_response RENAME TO persona_reaction;
            RAISE NOTICE 'STEP 3-6: reaction_persona_detail_page_response → persona_reaction 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-6: reaction_persona_detail_page_response 존재하지 않음 (통과)';
    END IF;
END $$;

-- 3-7. reaction_detail_page_final_report → final_report
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_detail_page_final_report'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'final_report'
        ) THEN
            RAISE NOTICE 'STEP 3-7: public.final_report 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_detail_page_final_report RENAME TO final_report;
            RAISE NOTICE 'STEP 3-7: reaction_detail_page_final_report → final_report 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-7: reaction_detail_page_final_report 존재하지 않음 (통과)';
    END IF;
END $$;

-- 3-8. reaction_detail_page_image_analysis → page_image_analysis (존재할 경우)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'reaction_detail_page_image_analysis'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'page_image_analysis'
        ) THEN
            RAISE NOTICE 'STEP 3-8: public.page_image_analysis 이미 존재 → 리네이밍 건너뜀';
        ELSE
            ALTER TABLE public.reaction_detail_page_image_analysis RENAME TO page_image_analysis;
            RAISE NOTICE 'STEP 3-8: reaction_detail_page_image_analysis → page_image_analysis 완료';
        END IF;
    ELSE
        RAISE NOTICE 'STEP 3-8: reaction_detail_page_image_analysis 존재하지 않음 (통과)';
    END IF;
END $$;

-- ================================================================================
-- STEP 4: 구버전 테이블 → archive 이동
-- ================================================================================

-- archive 이동을 안전하게 수행하는 함수
DO $$
DECLARE
    r RECORD;
    v_target_name TEXT;
    v_count INT;
BEGIN
    FOR r IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name IN (
              'generated_reaction_report',
              'report_best_target_segments',
              'report_weak_target_segments',
              'report_positive_reactions',
              'report_negative_reactions',
              'report_main_objections',
              'report_trust_issues',
              'report_price_resistance',
              'report_message_problems',
              'report_top_fixes',
              'report_improved_copy_examples',
              'report_generation_progress'
          )
        ORDER BY table_name
    LOOP
        -- 대상 테이블이 archive에 이미 존재하는지 확인
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'archive' AND table_name = r.table_name
        ) THEN
            v_target_name := r.table_name || '_' || to_char(now(), 'YYYYMMDD_HH24MISS');
            RAISE NOTICE 'STEP 4: archive.% 이미 존재 → public.% → archive.% 로 이동',
                r.table_name, r.table_name, v_target_name;
            EXECUTE format('ALTER TABLE public.%I RENAME TO %I', r.table_name, v_target_name);
            EXECUTE format('ALTER TABLE public.%I SET SCHEMA archive', v_target_name);
        ELSE
            RAISE NOTICE 'STEP 4: public.% → archive.% 로 이동', r.table_name, r.table_name;
            EXECUTE format('ALTER TABLE public.%I SET SCHEMA archive', r.table_name);
        END IF;
    END LOOP;
END $$;

-- ================================================================================
-- STEP 5: archive 후보 테이블 확인 (바로 이동하지 않고 NOTICE만)
-- ================================================================================
DO $$
DECLARE
    r RECORD;
BEGIN
    RAISE NOTICE '--- STEP 5: archive 후보 테이블 확인 (이동 안 함) ---';
    FOR r IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name IN ('persona_llm_normalized_profile', 'persona_text_section')
        ORDER BY table_name
    LOOP
        RAISE NOTICE '  후보: public.% (코드 참조 확인 필요, 자동 이동 안 함)', r.table_name;
    END LOOP;
END $$;

COMMIT;

-- ================================================================================
-- STEP 6: 최종 상태 확인
-- ================================================================================
SELECT '=== public 현재 테이블 ===' AS info;
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

SELECT '=== archive 현재 테이블 ===' AS info;
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'archive' AND table_type = 'BASE TABLE'
ORDER BY table_name;

SELECT '=== 남은 FK 관계 (public + archive) ===' AS info;
SELECT
    nsp.nspname AS schema,
    conrelid::regclass AS table_name,
    conname AS fk_name,
    confrelid::regclass AS referenced_table
FROM pg_constraint
JOIN pg_namespace nsp ON nsp.oid = connamespace
WHERE contype = 'f'
  AND nsp.nspname IN ('public', 'archive')
ORDER BY nsp.nspname, conrelid::regclass::text, conname;

-- ================================================================================
-- 리네이밍 매핑 참조 (코드에서 교체 필요)
-- ================================================================================
/*
코드 교체 매핑 (Python 스크립트, Java 엔티티, 설정 파일 등):

  reaction_report_order                 → report_order
  reaction_report_page_snapshot         → page_snapshot
  reaction_product_target_profile       → product_target_profile
  reaction_target_profile               → target_profile
  reaction_selected_persona             → selected_persona
  reaction_persona_detail_page_response → persona_reaction
  reaction_detail_page_final_report     → final_report
  reaction_detail_page_image_analysis   → page_image_analysis

archive 이동 테이블 (public → archive):

  generated_reaction_report
  report_best_target_segments
  report_weak_target_segments
  report_positive_reactions
  report_negative_reactions
  report_main_objections
  report_trust_issues
  report_price_resistance
  report_message_problems
  report_top_fixes
  report_improved_copy_examples
  report_generation_progress
  persona_reaction (구버전, archive.persona_reaction 또는 archive.persona_reaction_YYYYMMDD_HH24MISS)

검토 필요 (코드 참조 여부 확인 후 archive 이동 결정):

  persona_llm_normalized_profile
  persona_text_section
*/
