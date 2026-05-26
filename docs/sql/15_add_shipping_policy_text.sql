-- 배송비 정책을 사용자가 직접 입력하도록 report_order에 저장한다.
ALTER TABLE report_order
    ADD COLUMN IF NOT EXISTS shipping_policy_text TEXT;

COMMENT ON COLUMN report_order.shipping_policy_text IS
    '사용자가 직접 입력한 배송비 정책. 예: 완전 무료배송, 15000원 이상 무료배송, 쿠팡와우 멤버십 무료배송, 배송비 3000원.';
