-- Signup terms consent fields for existing PostgreSQL databases.
-- Safe to run repeatedly.

ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS terms_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS privacy_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS marketing_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS privacy_accepted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS marketing_accepted_at TIMESTAMP;

COMMENT ON COLUMN user_account.terms_accepted IS 'Required service terms consent captured at signup.';
COMMENT ON COLUMN user_account.privacy_accepted IS 'Required privacy collection/use consent captured at signup.';
COMMENT ON COLUMN user_account.marketing_accepted IS 'Optional marketing email consent captured at signup.';
