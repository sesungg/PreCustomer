-- User account and report delivery tables for public-web.
-- Safe to run repeatedly on PostgreSQL.

CREATE TABLE IF NOT EXISTS user_account (
    id BIGSERIAL PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    terms_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    privacy_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    marketing_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    terms_accepted_at TIMESTAMP,
    privacy_accepted_at TIMESTAMP,
    marketing_accepted_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS terms_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS privacy_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS marketing_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS privacy_accepted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS marketing_accepted_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS report_delivery_request (
    id BIGSERIAL PRIMARY KEY,
    report_order_id BIGINT NOT NULL UNIQUE,
    user_account_id BIGINT,
    email VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_report_delivery_order
        FOREIGN KEY (report_order_id) REFERENCES report_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_delivery_user_account
        FOREIGN KEY (user_account_id) REFERENCES user_account(id) ON DELETE SET NULL
);

ALTER TABLE report_order
    ADD COLUMN IF NOT EXISTS customer_account_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_report_order_customer_account'
    ) THEN
        ALTER TABLE report_order
            ADD CONSTRAINT fk_report_order_customer_account
            FOREIGN KEY (customer_account_id) REFERENCES user_account(id) ON DELETE SET NULL;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_user_account_email ON user_account(email);
CREATE INDEX IF NOT EXISTS idx_report_delivery_status ON report_delivery_request(status);
CREATE INDEX IF NOT EXISTS idx_report_delivery_email ON report_delivery_request(email);

GRANT SELECT, INSERT, UPDATE ON user_account, report_delivery_request TO precustomer_public_web;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_account, report_delivery_request TO precustomer_admin_web;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_account, report_delivery_request TO precustomer_report_worker;
GRANT USAGE, SELECT ON SEQUENCE user_account_id_seq TO precustomer_public_web, precustomer_admin_web, precustomer_report_worker;
GRANT USAGE, SELECT ON SEQUENCE report_delivery_request_id_seq TO precustomer_public_web, precustomer_admin_web, precustomer_report_worker;
