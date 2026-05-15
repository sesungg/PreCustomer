-- Service database roles for the MSA runtime.
--
-- Run as a PostgreSQL superuser or database owner after the base database exists.
-- This script is intentionally compatible with the local Compose defaults in
-- infra/postgres/init/00_msa_roles.sql.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'precustomer_public_web') THEN
        CREATE ROLE precustomer_public_web LOGIN PASSWORD 'public-local';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'precustomer_admin_web') THEN
        CREATE ROLE precustomer_admin_web LOGIN PASSWORD 'admin-local';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'precustomer_report_worker') THEN
        CREATE ROLE precustomer_report_worker LOGIN PASSWORD 'worker-local';
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS customer_app;
CREATE SCHEMA IF NOT EXISTS admin_app;
CREATE SCHEMA IF NOT EXISTS report_pipeline;
CREATE SCHEMA IF NOT EXISTS persona_data;
CREATE SCHEMA IF NOT EXISTS shopping_data;

GRANT CONNECT ON DATABASE precustomer TO precustomer_public_web;
GRANT CONNECT ON DATABASE precustomer TO precustomer_admin_web;
GRANT CONNECT ON DATABASE precustomer TO precustomer_report_worker;

GRANT USAGE ON SCHEMA public, customer_app TO precustomer_public_web;
GRANT USAGE ON SCHEMA public, customer_app, admin_app, report_pipeline, persona_data, shopping_data TO precustomer_admin_web;
GRANT USAGE ON SCHEMA public, report_pipeline, persona_data, shopping_data, customer_app TO precustomer_report_worker;

-- Transitional grants while tables still live in the public schema.
-- Tighten these to per-table grants after the physical schema split.
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO precustomer_public_web;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO precustomer_admin_web;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO precustomer_report_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO precustomer_public_web;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO precustomer_admin_web;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO precustomer_report_worker;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO precustomer_public_web;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO precustomer_admin_web;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO precustomer_report_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO precustomer_public_web;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO precustomer_admin_web;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO precustomer_report_worker;
