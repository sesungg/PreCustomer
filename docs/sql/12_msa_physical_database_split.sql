-- MSA phase 7: physical database split scaffold.
--
-- This script creates separate databases for the public web, admin web, and
-- report worker services. It does not copy data by itself. Apply the service
-- table migrations to each database before pointing a service at it.
--
-- Intended execution surface: psql as a PostgreSQL superuser.

SELECT 'CREATE DATABASE precustomer_public'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'precustomer_public')\gexec

SELECT 'CREATE DATABASE precustomer_admin'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'precustomer_admin')\gexec

SELECT 'CREATE DATABASE precustomer_report'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'precustomer_report')\gexec

GRANT ALL PRIVILEGES ON DATABASE precustomer_public TO precustomer_public_web;
GRANT ALL PRIVILEGES ON DATABASE precustomer_admin TO precustomer_admin_web;
GRANT ALL PRIVILEGES ON DATABASE precustomer_report TO precustomer_report_worker;

\connect precustomer_public
CREATE SCHEMA IF NOT EXISTS customer_app;
CREATE SCHEMA IF NOT EXISTS public_web;
GRANT USAGE, CREATE ON SCHEMA public, customer_app, public_web TO precustomer_public_web;
ALTER ROLE precustomer_public_web IN DATABASE precustomer_public SET search_path = public, customer_app, public_web;

\connect precustomer_admin
CREATE SCHEMA IF NOT EXISTS admin_app;
CREATE SCHEMA IF NOT EXISTS customer_read;
CREATE SCHEMA IF NOT EXISTS report_read;
GRANT USAGE, CREATE ON SCHEMA public, admin_app, customer_read, report_read TO precustomer_admin_web;
ALTER ROLE precustomer_admin_web IN DATABASE precustomer_admin SET search_path = public, admin_app, customer_read, report_read;

\connect precustomer_report
CREATE SCHEMA IF NOT EXISTS report_pipeline;
CREATE SCHEMA IF NOT EXISTS persona_data;
CREATE SCHEMA IF NOT EXISTS shopping_data;
GRANT USAGE, CREATE ON SCHEMA public, report_pipeline, persona_data, shopping_data TO precustomer_report_worker;
ALTER ROLE precustomer_report_worker IN DATABASE precustomer_report SET search_path = public, report_pipeline, persona_data, shopping_data;
