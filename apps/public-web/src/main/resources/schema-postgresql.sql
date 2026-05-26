-- Local PostgreSQL bootstrap for public-web owned customer auth tables.
-- Keep this idempotent; full production migrations remain under docs/sql.

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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
@@

ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS terms_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS privacy_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS marketing_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS privacy_accepted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS marketing_accepted_at TIMESTAMP;
@@

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_account_email ON user_account(email);
@@

ALTER TABLE report_order
    ADD COLUMN IF NOT EXISTS shipping_policy_text TEXT;
@@

ALTER TABLE report_order
    ADD COLUMN IF NOT EXISTS customer_account_id BIGINT;
@@

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
@@

CREATE TABLE IF NOT EXISTS report_delivery_request (
    id BIGSERIAL PRIMARY KEY,
    report_order_id BIGINT NOT NULL UNIQUE,
    user_account_id BIGINT,
    email VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_delivery_order
        FOREIGN KEY (report_order_id) REFERENCES report_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_delivery_user_account
        FOREIGN KEY (user_account_id) REFERENCES user_account(id) ON DELETE SET NULL
);
@@

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_report_delivery_order'
    ) THEN
        ALTER TABLE report_delivery_request
            ADD CONSTRAINT fk_report_delivery_order
            FOREIGN KEY (report_order_id) REFERENCES report_order(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_report_delivery_user_account'
    ) THEN
        ALTER TABLE report_delivery_request
            ADD CONSTRAINT fk_report_delivery_user_account
            FOREIGN KEY (user_account_id) REFERENCES user_account(id) ON DELETE SET NULL;
    END IF;
END
$$;
@@

CREATE INDEX IF NOT EXISTS idx_report_delivery_status ON report_delivery_request(status);
@@
CREATE INDEX IF NOT EXISTS idx_report_delivery_email ON report_delivery_request(email);
@@

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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_analytics_event_user_account
        FOREIGN KEY (user_account_id) REFERENCES user_account(id) ON DELETE SET NULL,
    CONSTRAINT fk_analytics_event_report_order
        FOREIGN KEY (report_order_id) REFERENCES report_order(id) ON DELETE SET NULL
);
@@

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_analytics_event_user_account'
    ) THEN
        ALTER TABLE analytics_event_log
            ADD CONSTRAINT fk_analytics_event_user_account
            FOREIGN KEY (user_account_id) REFERENCES user_account(id) ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_analytics_event_report_order'
    ) THEN
        ALTER TABLE analytics_event_log
            ADD CONSTRAINT fk_analytics_event_report_order
            FOREIGN KEY (report_order_id) REFERENCES report_order(id) ON DELETE SET NULL;
    END IF;
END
$$;
@@

CREATE INDEX IF NOT EXISTS idx_analytics_event_created_at
    ON analytics_event_log(created_at DESC);
@@
CREATE INDEX IF NOT EXISTS idx_analytics_event_name_created
    ON analytics_event_log(event_name, created_at DESC);
@@
CREATE INDEX IF NOT EXISTS idx_analytics_event_user_created
    ON analytics_event_log(user_account_id, created_at DESC);
@@
CREATE INDEX IF NOT EXISTS idx_analytics_event_order_created
    ON analytics_event_log(report_order_id, created_at DESC);
@@
