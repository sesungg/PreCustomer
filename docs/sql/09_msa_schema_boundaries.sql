-- MSA-ready schema boundary markers.
--
-- This migration is intentionally non-destructive. It does not move tables yet.
-- Use it to document the target service ownership before a future schema split.

CREATE SCHEMA IF NOT EXISTS customer_app;
CREATE SCHEMA IF NOT EXISTS admin_app;
CREATE SCHEMA IF NOT EXISTS report_pipeline;
CREATE SCHEMA IF NOT EXISTS persona_data;
CREATE SCHEMA IF NOT EXISTS shopping_data;

COMMENT ON SCHEMA customer_app IS 'Future owner: public-web customer order intake and customer-facing report access tokens.';
COMMENT ON SCHEMA admin_app IS 'Future owner: admin-web operator actions, admin audit logs, and backoffice views.';
COMMENT ON SCHEMA report_pipeline IS 'Future owner: report-worker jobs, pipeline progress, generated report artifacts.';
COMMENT ON SCHEMA persona_data IS 'Future owner: persona source records, profiles, labels, embeddings, and ML scores.';
COMMENT ON SCHEMA shopping_data IS 'Future owner: external shopping search groups, candidates, and price analysis.';

COMMENT ON TABLE report_order IS 'Boundary: customer_app today; future move candidate customer_app.report_order.';
COMMENT ON TABLE report_job IS 'Boundary: report_pipeline today; future move candidate report_pipeline.report_job.';
COMMENT ON TABLE report_job_step IS 'Boundary: report_pipeline today; future move candidate report_pipeline.report_job_step.';
COMMENT ON TABLE pipeline_progress IS 'Boundary: report_pipeline today; future move candidate report_pipeline.pipeline_progress.';
COMMENT ON TABLE persona_profile IS 'Boundary: persona_data today; future move candidate persona_data.persona_profile.';
COMMENT ON TABLE selected_persona IS 'Boundary: report_pipeline today; stores per-order fit, not fixed persona traits.';
COMMENT ON TABLE persona_reaction IS 'Boundary: report_pipeline today; stores generated per-order persona reactions.';
COMMENT ON TABLE final_report IS 'Boundary: report_pipeline today; stores final report output.';
