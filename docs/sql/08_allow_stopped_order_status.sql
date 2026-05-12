-- Allow report_order.status = STOPPED.
-- Older local databases still have the pre-STOPPED check constraint.

ALTER TABLE report_order
    DROP CONSTRAINT IF EXISTS reaction_report_order_status_check;

ALTER TABLE report_order
    ADD CONSTRAINT reaction_report_order_status_check
    CHECK (status IN ('REQUESTED', 'PAID', 'GENERATING', 'COMPLETED', 'STOPPED', 'FAILED'));
