-- PreCustomerReport: report_order 테이블에 image_paths 컬럼 추가
-- 멱등성 보장 (IF NOT EXISTS 패턴)

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'report_order'
          AND column_name = 'image_paths'
    ) THEN
        ALTER TABLE public.report_order ADD COLUMN image_paths TEXT;
        RAISE NOTICE 'image_paths 컬럼 추가 완료';
    ELSE
        RAISE NOTICE 'image_paths 컬럼 이미 존재함 (통과)';
    END IF;
END $$;
