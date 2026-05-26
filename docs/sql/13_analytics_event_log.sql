-- GA4 companion event log for public-web.
-- Safe to run repeatedly on PostgreSQL.

CREATE TABLE IF NOT EXISTS analytics_event_log (
    id BIGSERIAL PRIMARY KEY,
    event_name VARCHAR(80) NOT NULL,
    event_category VARCHAR(50),
    page_path VARCHAR(500),
    referrer VARCHAR(500),
    element_text VARCHAR(160),
    anonymous_id VARCHAR(80),
    user_account_id BIGINT,
    report_order_id BIGINT,
    metadata_json TEXT,
    user_agent VARCHAR(500),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_analytics_event_user_account
        FOREIGN KEY (user_account_id) REFERENCES user_account(id) ON DELETE SET NULL,
    CONSTRAINT fk_analytics_event_report_order
        FOREIGN KEY (report_order_id) REFERENCES report_order(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_analytics_event_created_at
    ON analytics_event_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_event_name_created
    ON analytics_event_log(event_name, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_event_user_created
    ON analytics_event_log(user_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_event_order_created
    ON analytics_event_log(report_order_id, created_at DESC);

COMMENT ON TABLE analytics_event_log IS 'Boundary: customer_app today; GA4 companion and internal customer-facing event trail.';

GRANT SELECT, INSERT ON analytics_event_log TO precustomer_public_web;
GRANT SELECT, INSERT, UPDATE, DELETE ON analytics_event_log TO precustomer_admin_web;
GRANT SELECT ON analytics_event_log TO precustomer_report_worker;
GRANT USAGE, SELECT ON SEQUENCE analytics_event_log_id_seq TO precustomer_public_web, precustomer_admin_web, precustomer_report_worker;
