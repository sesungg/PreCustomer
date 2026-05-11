import json
import os
from typing import Any, Dict, Optional

import psycopg2
from psycopg2.extras import RealDictCursor, Json
from flask import Flask, request, redirect, url_for, render_template_string, flash

from config import (
    PSYCOPG2_DATABASE_URL,
    SCORE_COLUMNS,
    HUMAN_VERIFIED_LABEL_SOURCE,
    HUMAN_VERIFIED_SCORE_SOURCE,
    HUMAN_VERIFIED_SCORE_MODEL_VERSION,
)

app = Flask(__name__)
app.secret_key = os.getenv("FLASK_SECRET_KEY", "persona-review-dev-secret")

STATUS_OPTIONS = ["STRONG", "WEAK", "UNKNOWN"]
STATUS_WEIGHT = {"STRONG": 1.0, "WEAK": 0.3, "UNKNOWN": 0.0}

PAGE = """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <title>Persona Label Review</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 0; background: #f7f7f8; color: #202124; }
    header { background: #111827; color: white; padding: 16px 24px; }
    main { max-width: 1280px; margin: 0 auto; padding: 24px; }
    .flash { padding: 12px 16px; background: #ecfdf5; border: 1px solid #10b981; border-radius: 12px; margin-bottom: 16px; }
    .panel { background: white; border-radius: 16px; box-shadow: 0 1px 6px rgba(0,0,0,0.08); padding: 20px; margin-bottom: 20px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
    .meta { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; font-size: 14px; }
    .meta div { background: #f3f4f6; border-radius: 10px; padding: 8px; }
    label { display: block; font-size: 13px; color: #4b5563; margin-bottom: 4px; }
    input, select, textarea { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 10px; padding: 8px; font-size: 14px; background: white; }
    textarea { min-height: 84px; resize: vertical; }
    .score-table { width: 100%; border-collapse: collapse; }
    .score-table th, .score-table td { border-bottom: 1px solid #e5e7eb; padding: 8px; vertical-align: top; }
    .score-table th { background: #f9fafb; text-align: left; font-size: 13px; }
    .score-name { font-weight: 700; font-size: 13px; }
    .reason { font-size: 13px; line-height: 1.45; color: #374151; max-width: 420px; }
    .status-STRONG { color: #047857; font-weight: 700; }
    .status-WEAK { color: #b45309; font-weight: 700; }
    .status-UNKNOWN { color: #b91c1c; font-weight: 700; }
    .btns { display: flex; gap: 10px; align-items: center; margin-top: 18px; }
    button, .btn { display: inline-block; border: 0; border-radius: 10px; padding: 10px 14px; font-weight: 700; cursor: pointer; text-decoration: none; }
    .primary { background: #2563eb; color: white; }
    .secondary { background: #e5e7eb; color: #111827; }
    .danger { background: #ef4444; color: white; }
    pre { white-space: pre-wrap; word-break: break-word; background: #111827; color: #e5e7eb; border-radius: 12px; padding: 14px; max-height: 360px; overflow: auto; }
    .filters { display: grid; grid-template-columns: 2fr 1fr 1fr 1fr auto; gap: 10px; align-items: end; }
  </style>
</head>
<body>
<header>
  <h2>Persona Label Review</h2>
</header>
<main>
  {% with messages = get_flashed_messages() %}
    {% if messages %}
      {% for message in messages %}<div class="flash">{{ message }}</div>{% endfor %}
    {% endif %}
  {% endwith %}

  <section class="panel">
    <form class="filters" method="get" action="/review">
      <div>
        <label>검수할 label_batch_id</label>
        <input name="label_batch_id" value="{{ filters.label_batch_id or '' }}" placeholder="예: pro_recheck_20260508" required />
      </div>
      <div>
        <label>label_source</label>
        <input name="label_source" value="{{ filters.label_source or 'DEEPSEEK_PRO' }}" />
      </div>
      <div>
        <label>score 필터</label>
        <select name="target_score">
          <option value="">전체</option>
          {% for col in score_columns %}
            <option value="{{ col }}" {% if filters.target_score == col %}selected{% endif %}>{{ col }}</option>
          {% endfor %}
        </select>
      </div>
      <div>
        <label>status 필터</label>
        <select name="status">
          <option value="">전체</option>
          {% for st in status_options %}
            <option value="{{ st }}" {% if filters.status == st %}selected{% endif %}>{{ st }}</option>
          {% endfor %}
        </select>
      </div>
      <button class="secondary" type="submit">조회</button>
    </form>
  </section>

  {% if not row %}
    <section class="panel">
      <h3>검수 대상이 없습니다.</h3>
      <p>필터를 바꾸거나 이미 reviewed 처리되었는지 확인하세요.</p>
    </section>
  {% else %}
    <section class="panel">
      <h3>Persona #{{ row.persona_profile_id }}</h3>
      <div class="meta">
        <div><b>review_id</b><br>{{ row.label_review_id }}</div>
        <div><b>batch</b><br>{{ row.label_batch_id }}</div>
        <div><b>source</b><br>{{ row.label_source }}</div>
        <div><b>reviewed</b><br>{{ row.reviewed }}</div>
        <div><b>age/gender</b><br>{{ row.age }} / {{ row.gender }}</div>
        <div><b>region</b><br>{{ row.region }} {{ row.province }}</div>
        <div><b>occupation</b><br>{{ row.occupation }}</div>
        <div><b>family</b><br>{{ row.family_type }}</div>
      </div>
    </section>

    <form method="post" action="/save">
      <input type="hidden" name="label_review_id" value="{{ row.label_review_id }}" />
      <input type="hidden" name="persona_profile_id" value="{{ row.persona_profile_id }}" />
      <input type="hidden" name="source_batch_id" value="{{ row.label_batch_id }}" />
      <input type="hidden" name="next_label_batch_id" value="{{ filters.label_batch_id or '' }}" />
      <input type="hidden" name="next_label_source" value="{{ filters.label_source or 'DEEPSEEK_PRO' }}" />
      <input type="hidden" name="next_target_score" value="{{ filters.target_score or '' }}" />
      <input type="hidden" name="next_status" value="{{ filters.status or '' }}" />

      <section class="panel">
        <h3>점수 검수</h3>
        <table class="score-table">
          <thead>
            <tr>
              <th>score</th>
              <th>AI 점수</th>
              <th>수정 점수</th>
              <th>confidence</th>
              <th>status</th>
              <th>reason</th>
            </tr>
          </thead>
          <tbody>
            {% for col in score_columns %}
              {% set st = reason_json.get('label_status', {}).get(col, 'UNKNOWN') %}
              <tr>
                <td class="score-name">{{ col }}<br><span class="status-{{ st }}">{{ st }}</span></td>
                <td>{{ scores[col] }}</td>
                <td><input type="number" min="0" max="100" name="score__{{ col }}" value="{{ scores[col] }}" /></td>
                <td><input type="number" step="0.01" min="0" max="1" name="confidence__{{ col }}" value="{{ reason_json.get('confidence', {}).get(col, 0) }}" /></td>
                <td>
                  <select name="status__{{ col }}">
                    {% for opt in status_options %}
                      <option value="{{ opt }}" {% if st == opt %}selected{% endif %}>{{ opt }}</option>
                    {% endfor %}
                  </select>
                </td>
                <td class="reason">
                  {{ reason_json.get('reasons', {}).get(col, '') }}
                  <textarea name="reason__{{ col }}">{{ reason_json.get('reasons', {}).get(col, '') }}</textarea>
                </td>
              </tr>
            {% endfor %}
          </tbody>
        </table>
      </section>

      <section class="panel grid">
        <div>
          <h3>페르소나 요약</h3>
          <p><b>summary</b></p>
          <p>{{ row.persona_summary }}</p>
          <p><b>search_text</b></p>
          <p>{{ row.search_text }}</p>
          <p><b>interests</b></p>
          <p>{{ row.interests }}</p>
          <p><b>pain_points</b></p>
          <p>{{ row.pain_points }}</p>
        </div>
        <div>
          <h3>원본 raw_json</h3>
          <pre>{{ raw_json_pretty }}</pre>
        </div>
      </section>

      <section class="panel">
        <h3>검수 메모</h3>
        <label>reviewer</label>
        <input name="reviewer" value="{{ default_reviewer }}" />
        <label>human_batch_id</label>
        <input name="human_batch_id" value="human_review_v1" />
        <label>메모</label>
        <textarea name="review_note" placeholder="수정 이유, 애매한 점, 제외 이유 등을 작성"></textarea>
        <div class="btns">
          <button class="primary" type="submit" name="action" value="save_human">HUMAN_VERIFIED로 저장</button>
          <button class="secondary" type="submit" name="action" value="mark_reviewed_only">원본만 reviewed 처리</button>
          <a class="btn secondary" href="{{ next_url }}">건너뛰기</a>
        </div>
      </section>
    </form>
  {% endif %}
</main>
</body>
</html>
"""


def conn():
    return psycopg2.connect(PSYCOPG2_DATABASE_URL)


def as_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except Exception:
            return {}
    return {}


def clamp_score(value: Any) -> int:
    try:
        n = int(round(float(value)))
    except Exception:
        n = 0
    return max(0, min(100, n))


def clamp_float(value: Any) -> float:
    try:
        n = float(value)
    except Exception:
        n = 0.0
    return max(0.0, min(1.0, n))


def load_next_review(label_batch_id: str, label_source: str, target_score: Optional[str], status: Optional[str]) -> Optional[Dict[str, Any]]:
    where = ["lr.label_batch_id = %s", "lr.label_source = %s", "COALESCE(lr.reviewed, false) = false"]
    params = [label_batch_id, label_source]

    if target_score and status:
        where.append("lr.reason_json -> 'label_status' ->> %s = %s")
        params.extend([target_score, status])

    score_select = ", ".join([f"lr.{c}" for c in SCORE_COLUMNS])
    sql = f"""
    SELECT
        lr.id AS label_review_id,
        lr.persona_profile_id,
        lr.label_batch_id,
        lr.label_source,
        lr.reason_json,
        lr.raw_response_json,
        lr.reviewed,
        {score_select},
        p.source_record_id,
        p.source_id,
        p.age,
        p.age_group,
        p.gender,
        p.province,
        p.region,
        p.occupation,
        p.education_level,
        p.family_type,
        p.housing_type,
        p.persona_summary,
        p.search_text,
        p.interests,
        p.pain_points,
        sr.raw_json AS source_raw_json
    FROM persona_label_review lr
    JOIN persona_profile p ON p.id = lr.persona_profile_id
    LEFT JOIN persona_source_record sr ON sr.id = p.source_record_id
    WHERE {' AND '.join(where)}
    ORDER BY
      CASE
        WHEN %s <> '' THEN 0
        ELSE 1
      END,
      lr.id
    LIMIT 1
    """
    params.append(target_score or "")

    with conn() as c:
        with c.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(sql, params)
            row = cur.fetchone()
            return dict(row) if row else None


def mark_original_reviewed(c, label_review_id: int, reviewer: str):
    with c.cursor() as cur:
        cur.execute(
            """
            UPDATE persona_label_review
            SET reviewed = true,
                reviewer = %s,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = %s
            """,
            (reviewer, label_review_id),
        )


def upsert_human_label(c, form):
    persona_profile_id = int(form["persona_profile_id"])
    label_review_id = int(form["label_review_id"])
    human_batch_id = form.get("human_batch_id") or "human_review_v1"
    reviewer = form.get("reviewer") or "human"
    review_note = form.get("review_note") or ""

    scores = {col: clamp_score(form.get(f"score__{col}")) for col in SCORE_COLUMNS}
    confidence = {col: clamp_float(form.get(f"confidence__{col}")) for col in SCORE_COLUMNS}
    label_status = {col: form.get(f"status__{col}", "UNKNOWN") for col in SCORE_COLUMNS}
    reasons = {col: form.get(f"reason__{col}", "") for col in SCORE_COLUMNS}
    train_weight = {col: STATUS_WEIGHT.get(label_status[col], 0.0) for col in SCORE_COLUMNS}

    reason_json = {
        "reasons": reasons,
        "confidence": confidence,
        "label_status": label_status,
        "train_weight": train_weight,
        "review_note": review_note,
        "reviewer": reviewer,
        "source_label_review_id": label_review_id,
        "source_batch_id": form.get("source_batch_id"),
    }

    label_sql = """
    INSERT INTO persona_label_review (
        persona_profile_id,
        label_batch_id,
        label_source,
        digital_affinity_score,
        price_sensitivity_score,
        trust_sensitivity_score,
        convenience_need_score,
        quality_sensitivity_score,
        novelty_acceptance_score,
        local_affinity_score,
        family_decision_score,
        health_safety_sensitivity_score,
        review_dependency_score,
        reason_json,
        reviewer,
        reviewed,
        raw_response_json,
        error_message
    )
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, true, %s, NULL)
    ON CONFLICT (label_batch_id, persona_profile_id, label_source)
    DO UPDATE SET
        digital_affinity_score = EXCLUDED.digital_affinity_score,
        price_sensitivity_score = EXCLUDED.price_sensitivity_score,
        trust_sensitivity_score = EXCLUDED.trust_sensitivity_score,
        convenience_need_score = EXCLUDED.convenience_need_score,
        quality_sensitivity_score = EXCLUDED.quality_sensitivity_score,
        novelty_acceptance_score = EXCLUDED.novelty_acceptance_score,
        local_affinity_score = EXCLUDED.local_affinity_score,
        family_decision_score = EXCLUDED.family_decision_score,
        health_safety_sensitivity_score = EXCLUDED.health_safety_sensitivity_score,
        review_dependency_score = EXCLUDED.review_dependency_score,
        reason_json = EXCLUDED.reason_json,
        reviewer = EXCLUDED.reviewer,
        reviewed = true,
        raw_response_json = EXCLUDED.raw_response_json,
        updated_at = CURRENT_TIMESTAMP
    """

    feature_sql = """
    INSERT INTO persona_feature_score (
        persona_profile_id,
        digital_affinity_score,
        price_sensitivity_score,
        trust_sensitivity_score,
        convenience_need_score,
        quality_sensitivity_score,
        novelty_acceptance_score,
        local_affinity_score,
        family_decision_score,
        health_safety_sensitivity_score,
        review_dependency_score,
        score_source,
        score_model_version
    )
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    ON CONFLICT (persona_profile_id, score_source, score_model_version)
    DO UPDATE SET
        digital_affinity_score = EXCLUDED.digital_affinity_score,
        price_sensitivity_score = EXCLUDED.price_sensitivity_score,
        trust_sensitivity_score = EXCLUDED.trust_sensitivity_score,
        convenience_need_score = EXCLUDED.convenience_need_score,
        quality_sensitivity_score = EXCLUDED.quality_sensitivity_score,
        novelty_acceptance_score = EXCLUDED.novelty_acceptance_score,
        local_affinity_score = EXCLUDED.local_affinity_score,
        family_decision_score = EXCLUDED.family_decision_score,
        health_safety_sensitivity_score = EXCLUDED.health_safety_sensitivity_score,
        review_dependency_score = EXCLUDED.review_dependency_score,
        updated_at = CURRENT_TIMESTAMP
    """

    values = [scores[col] for col in SCORE_COLUMNS]

    with c.cursor() as cur:
        cur.execute(
            label_sql,
            (
                persona_profile_id,
                human_batch_id,
                HUMAN_VERIFIED_LABEL_SOURCE,
                *values,
                Json(reason_json),
                reviewer,
                Json({"source_label_review_id": label_review_id, "manual_review": True}),
            ),
        )
        cur.execute(
            feature_sql,
            (
                persona_profile_id,
                *values,
                HUMAN_VERIFIED_SCORE_SOURCE,
                HUMAN_VERIFIED_SCORE_MODEL_VERSION,
            ),
        )

    mark_original_reviewed(c, label_review_id, reviewer)


@app.get("/")
def index():
    return redirect(url_for("review"))


@app.get("/review")
def review():
    label_batch_id = request.args.get("label_batch_id", "")
    label_source = request.args.get("label_source", "DEEPSEEK_PRO")
    target_score = request.args.get("target_score", "")
    status = request.args.get("status", "")

    row = None
    reason_json = {}
    scores = {}
    raw_json_pretty = ""

    if label_batch_id:
        row = load_next_review(label_batch_id, label_source, target_score or None, status or None)
        if row:
            reason_json = as_dict(row.get("reason_json"))
            scores = {col: row.get(col, 0) for col in SCORE_COLUMNS}
            raw_json_pretty = json.dumps(as_dict(row.get("source_raw_json")), ensure_ascii=False, indent=2)

    next_url = url_for(
        "review",
        label_batch_id=label_batch_id,
        label_source=label_source,
        target_score=target_score,
        status=status,
    )

    return render_template_string(
        PAGE,
        row=row,
        filters={
            "label_batch_id": label_batch_id,
            "label_source": label_source,
            "target_score": target_score,
            "status": status,
        },
        score_columns=SCORE_COLUMNS,
        status_options=STATUS_OPTIONS,
        reason_json=reason_json,
        scores=scores,
        raw_json_pretty=raw_json_pretty,
        default_reviewer=os.getenv("DEFAULT_REVIEWER", "ssg"),
        next_url=next_url,
    )


@app.post("/save")
def save():
    action = request.form.get("action")
    label_review_id = int(request.form["label_review_id"])
    reviewer = request.form.get("reviewer") or "human"

    with conn() as c:
        c.autocommit = False
        try:
            if action == "mark_reviewed_only":
                mark_original_reviewed(c, label_review_id, reviewer)
                flash("원본 라벨을 reviewed 처리했습니다.")
            else:
                upsert_human_label(c, request.form)
                flash("HUMAN_VERIFIED 라벨과 feature_score를 저장했습니다.")
            c.commit()
        except Exception:
            c.rollback()
            raise

    return redirect(url_for(
        "review",
        label_batch_id=request.form.get("next_label_batch_id"),
        label_source=request.form.get("next_label_source"),
        target_score=request.form.get("next_target_score"),
        status=request.form.get("next_status"),
    ))


if __name__ == "__main__":
    app.run(host=os.getenv("REVIEW_HOST", "127.0.0.1"), port=int(os.getenv("REVIEW_PORT", "5001")), debug=True)
