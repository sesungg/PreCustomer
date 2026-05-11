import os
from pathlib import Path

# SQLAlchemy URL: pandas/sqlalchemy read용
SQLALCHEMY_DATABASE_URL = os.getenv(
    "DATABASE_URL_SQLALCHEMY",
    "postgresql+psycopg2://postgres:postgres@localhost:5432/precustomer",
)

# psycopg2 URL: insert/update용
PSYCOPG2_DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://postgres:postgres@localhost:5432/precustomer",
)

BASE_DIR = Path(os.getenv("PERSONA_PIPELINE_OUTPUT_DIR", "./persona_pipeline_output"))
SAMPLE_DIR = BASE_DIR / "samples"
DEEPSEEK_OUTPUT_DIR = BASE_DIR / "deepseek_outputs"
IMPORT_LOG_DIR = BASE_DIR / "import_logs"
COMPARE_DIR = BASE_DIR / "compare"
MODEL_DIR = BASE_DIR / "models"
PREDICTION_DIR = BASE_DIR / "predictions"

for directory in [
    BASE_DIR,
    SAMPLE_DIR,
    DEEPSEEK_OUTPUT_DIR,
    IMPORT_LOG_DIR,
    COMPARE_DIR,
    MODEL_DIR,
    PREDICTION_DIR,
]:
    directory.mkdir(parents=True, exist_ok=True)

SCORE_COLUMNS = [
    "digital_affinity_score",
    "price_sensitivity_score",
    "trust_sensitivity_score",
    "convenience_need_score",
    "quality_sensitivity_score",
    "novelty_acceptance_score",
    "local_affinity_score",
    "family_decision_score",
    "health_safety_sensitivity_score",
    "review_dependency_score",
]

OLD_DEEPSEEK_SCORE_SOURCE = os.getenv("OLD_DEEPSEEK_SCORE_SOURCE", "DEEPSEEK_PSEUDO")
OLD_DEEPSEEK_SCORE_MODEL_VERSION = os.getenv(
    "OLD_DEEPSEEK_SCORE_MODEL_VERSION",
    "deepseek_v4_flash_prompt_v1",
)

NEW_DEEPSEEK_LABEL_SOURCE = os.getenv("NEW_DEEPSEEK_LABEL_SOURCE", "DEEPSEEK_PRO")
NEW_DEEPSEEK_SCORE_SOURCE = os.getenv("NEW_DEEPSEEK_SCORE_SOURCE", "DEEPSEEK_PSEUDO")
NEW_DEEPSEEK_SCORE_MODEL_VERSION = os.getenv(
    "NEW_DEEPSEEK_SCORE_MODEL_VERSION",
    "deepseek_v4_pro_prompt_v2",
)

HUMAN_VERIFIED_LABEL_SOURCE = os.getenv("HUMAN_VERIFIED_LABEL_SOURCE", "HUMAN_VERIFIED")
HUMAN_VERIFIED_SCORE_SOURCE = os.getenv("HUMAN_VERIFIED_SCORE_SOURCE", "HUMAN_VERIFIED")
HUMAN_VERIFIED_SCORE_MODEL_VERSION = os.getenv("HUMAN_VERIFIED_SCORE_MODEL_VERSION", "human_v1")

RULE_BASED_SCORE_SOURCE = os.getenv("RULE_BASED_SCORE_SOURCE", "RULE_BASED")
RULE_BASED_SCORE_MODEL_VERSION = os.getenv("RULE_BASED_SCORE_MODEL_VERSION", "rule_v1_2")

RANDOM_SEED = int(os.getenv("RANDOM_SEED", "42"))
