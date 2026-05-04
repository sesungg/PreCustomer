#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
CustomerPreview / 미리고객 - 페르소나 라벨 검수용 간단 웹 페이지

역할:
- DeepSeek pseudo-label 3,000건을 웹에서 하나씩 검수한다.
- DeepSeek 점수를 기본값으로 보여준다.
- 사람이 점수를 수정하고 저장하면 persona_label_review에 HUMAN_CORRECTED 라벨로 저장한다.
- CSV를 직접 편집하지 않고 브라우저에서 검수할 수 있게 한다.

설치:
pip install flask psycopg2-binary

실행:
python3 ./scripts/persona_review_app.py \
  --dbname precustomer \
  --source-batch-id deepseek_3000_v1 \
  --human-batch-id human_review_v1 \
  --reviewer ssg \
  --port 5001

접속:
http://localhost:5001

주의:
- 이 앱은 내부 검수용 단순 도구다.
- 인증/권한 기능은 없다.
- 로컬에서만 실행하는 것을 전제로 한다.
"""

import argparse
import json
from datetime import datetime

import psycopg2
from psycopg2.extras import Json
from flask import Flask, request, redirect, url_for, render_template_string, flash


SCORE_FIELDS = [
    {"key": "digital_affinity_score", "camel": "digitalAffinityScore", "label": "디지털 친화도", "desc": "디지털 서비스나 앱 사용에 익숙한 정도"},
    {"key": "price_sensitivity_score", "camel": "priceSensitivityScore", "label": "가격 민감도", "desc": "가격, 가성비, 생활비에 민감한 정도"},
    {"key": "trust_sensitivity_score", "camel": "trustSensitivityScore", "label": "신뢰 민감도", "desc": "후기, 검증, 인증, 운영자 신뢰를 중요하게 보는 정도"},
    {"key": "convenience_need_score", "camel": "convenienceNeedScore", "label": "편의성 니즈", "desc": "시간 절약, 편의성, 자동화 니즈가 큰 정도"},
    {"key": "quality_sensitivity_score", "camel": "qualitySensitivityScore", "label": "품질 민감도", "desc": "품질, 꼼꼼함, 전문성, 위생, 완성도를 중시하는 정도"},
    {"key": "novelty_acceptance_score", "camel": "noveltyAcceptanceScore", "label": "새로움 수용도", "desc": "새로운 서비스, 상품, 방식에 열려 있는 정도"},
    {"key": "local_affinity_score", "camel": "localAffinityScore", "label": "지역 친화도", "desc": "동네 상권, 단골, 이웃 관계, 지역 커뮤니티, 오프라인 모임에 반응할 가능성"},
    {"key": "family_decision_score", "camel": "familyDecisionScore", "label": "가족 의사결정 영향도", "desc": "가족, 배우자, 자녀, 부모, 손주 관련 의사결정 영향도"},
    {"key": "health_safety_sensitivity_score", "camel": "healthSafetySensitivityScore", "label": "건강/안전 민감도", "desc": "건강, 안전, 위생, 재난, 의료, 식품 안정성에 민감한 정도"},
    {"key": "review_dependency_score", "camel": "reviewDependencyScore", "label": "후기 의존도", "desc": "후기, 평판, 추천, 지인 평가에 의존하는 정도"},
]


BASE_CSS = """
:root {
    --bg: #f6f7fb;
    --card: #ffffff;
    --border: #e6e8ef;
    --text: #202534;
    --muted: #6b7280;
    --primary: #2563eb;
    --danger: #dc2626;
    --ok: #16a34a;
}
* { box-sizing: border-box; }
body {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif;
    background: var(--bg);
    color: var(--text);
}
a { color: var(--primary); text-decoration: none; }
.container {
    max-width: 1180px;
    margin: 0 auto;
    padding: 24px;
}
.header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 16px;
    margin-bottom: 16px;
}
.card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 18px;
    box-shadow: 0 8px 24px rgba(15, 23, 42, 0.04);
}
.title {
    font-size: 24px;
    font-weight: 800;
    margin: 0 0 6px;
}
.subtitle {
    color: var(--muted);
    font-size: 14px;
    margin: 0;
}
.grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
}
.stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 12px;
    margin-bottom: 16px;
}
.stat {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 14px;
}
.stat .num {
    font-size: 24px;
    font-weight: 800;
}
.stat .label {
    color: var(--muted);
    font-size: 13px;
}
.progress {
    height: 12px;
    border-radius: 999px;
    background: #e5e7eb;
    overflow: hidden;
}
.progress > div {
    height: 100%;
    background: var(--primary);
}
.table {
    width: 100%;
    border-collapse: collapse;
    font-size: 14px;
}
.table th, .table td {
    padding: 10px;
    border-bottom: 1px solid var(--border);
    vertical-align: top;
}
.table th {
    text-align: left;
    color: var(--muted);
    font-weight: 700;
    background: #fafafa;
}
.badge {
    display: inline-block;
    padding: 3px 8px;
    border-radius: 999px;
    background: #eef2ff;
    color: #3730a3;
    font-size: 12px;
    font-weight: 700;
}
.btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--border);
    background: #fff;
    color: var(--text);
    border-radius: 10px;
    padding: 9px 13px;
    font-size: 14px;
    font-weight: 700;
    cursor: pointer;
}
.btn.primary { background: var(--primary); color: #fff; border-color: var(--primary); }
.btn.ok { background: var(--ok); color: #fff; border-color: var(--ok); }
.actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
}
.filter {
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
    margin-bottom: 16px;
}
input[type="text"], input[type="number"], textarea, select {
    width: 100%;
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 10px 12px;
    font-size: 14px;
}
textarea { min-height: 82px; }
.profile {
    line-height: 1.65;
    white-space: pre-wrap;
}
.search-text {
    max-height: 520px;
    overflow: auto;
    line-height: 1.7;
    white-space: pre-wrap;
    background: #fafafa;
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 14px;
    font-size: 14px;
}
.score-row {
    display: grid;
    grid-template-columns: 170px 1fr 80px 80px;
    gap: 10px;
    align-items: center;
    padding: 12px 0;
    border-bottom: 1px solid var(--border);
}
.score-row:last-child { border-bottom: none; }
.score-label { font-weight: 800; }
.score-desc {
    display: block;
    margin-top: 2px;
    color: var(--muted);
    font-size: 12px;
    font-weight: 400;
}
.score-current {
    text-align: center;
    font-weight: 800;
    color: var(--muted);
}
input[type="range"] { width: 100%; }
.reason {
    color: var(--muted);
    font-size: 13px;
    margin-top: 6px;
    grid-column: 2 / 5;
}
.flash {
    background: #ecfdf5;
    border: 1px solid #bbf7d0;
    color: #166534;
    border-radius: 12px;
    padding: 12px;
    margin-bottom: 12px;
}
.warn {
    background: #fff7ed;
    border: 1px solid #fed7aa;
    color: #9a3412;
    border-radius: 12px;
    padding: 12px;
    margin-bottom: 12px;
}
.small { font-size: 13px; color: var(--muted); }
@media (max-width: 900px) {
    .grid { grid-template-columns: 1fr; }
    .stats { grid-template-columns: repeat(2, 1fr); }
    .score-row { grid-template-columns: 1fr; }
    .reason { grid-column: auto; }
}
"""


INDEX_TEMPLATE = """
<!doctype html>
<html lang="ko">
<head>
    <meta charset="utf-8">
    <title>페르소나 라벨 검수</title>
    <style>{{ css }}</style>
</head>
<body>
<div class="container">
    <div class="header">
        <div>
            <h1 class="title">페르소나 라벨 검수</h1>
            <p class="subtitle">
                DeepSeek 배치: <b>{{ source_batch_id }}</b> /
                사람 검수 배치: <b>{{ human_batch_id }}</b>
            </p>
        </div>
        <div class="actions">
            <a class="btn" href="{{ url_for('stats') }}">점수 통계</a>
            <a class="btn primary" href="{{ url_for('next_review') }}">다음 검수</a>
        </div>
    </div>

    {% for message in get_flashed_messages() %}
        <div class="flash">{{ message }}</div>
    {% endfor %}

    <div class="stats">
        <div class="stat"><div class="num">{{ total }}</div><div class="label">DeepSeek 라벨</div></div>
        <div class="stat"><div class="num">{{ reviewed }}</div><div class="label">검수 완료</div></div>
        <div class="stat"><div class="num">{{ remaining }}</div><div class="label">남은 검수</div></div>
        <div class="stat"><div class="num">{{ progress }}%</div><div class="label">진행률</div></div>
    </div>

    <div class="card" style="margin-bottom:16px;">
        <div class="progress"><div style="width: {{ progress }}%"></div></div>
    </div>

    <div class="card">
        <form class="filter" method="get" action="/">
            <input type="text" name="q" value="{{ q or '' }}" placeholder="직업, 지역, 요약 검색" style="max-width: 320px;">
            <select name="status" style="max-width: 160px;">
                <option value="unreviewed" {% if status == 'unreviewed' %}selected{% endif %}>미검수</option>
                <option value="reviewed" {% if status == 'reviewed' %}selected{% endif %}>검수완료</option>
                <option value="all" {% if status == 'all' %}selected{% endif %}>전체</option>
            </select>
            <button class="btn" type="submit">검색</button>
            <a class="btn" href="/">초기화</a>
        </form>

        <table class="table">
            <thead>
                <tr>
                    <th>ID</th>
                    <th>기본 정보</th>
                    <th>요약</th>
                    <th>상태</th>
                    <th>검수</th>
                </tr>
            </thead>
            <tbody>
                {% for row in rows %}
                    <tr>
                        <td>{{ row.persona_profile_id }}</td>
                        <td>
                            <b>{{ row.age_group }}</b> / {{ row.gender }} / {{ row.region }}<br>
                            {{ row.occupation or '-' }}<br>
                            <span class="small">{{ row.education_level or '-' }} · {{ row.family_type or '-' }} · {{ row.housing_type or '-' }}</span>
                        </td>
                        <td>{{ row.persona_summary[:180] if row.persona_summary else '' }}</td>
                        <td>
                            {% if row.human_id %}
                                <span class="badge">검수완료</span>
                            {% else %}
                                <span class="badge">미검수</span>
                            {% endif %}
                        </td>
                        <td>
                            <a class="btn primary" href="{{ url_for('review', label_review_id=row.deepseek_label_review_id) }}">열기</a>
                        </td>
                    </tr>
                {% endfor %}
                {% if not rows %}
                    <tr><td colspan="5">표시할 데이터가 없습니다.</td></tr>
                {% endif %}
            </tbody>
        </table>
    </div>
</div>
</body>
</html>
"""


REVIEW_TEMPLATE = """
<!doctype html>
<html lang="ko">
<head>
    <meta charset="utf-8">
    <title>페르소나 검수 #{{ row.persona_profile_id }}</title>
    <style>{{ css }}</style>
    <script>
        function syncRange(key) {
            const range = document.getElementById(key + "_range");
            const input = document.getElementById(key);
            input.value = range.value;
        }
        function syncNumber(key) {
            const range = document.getElementById(key + "_range");
            const input = document.getElementById(key);
            let v = parseInt(input.value || "0", 10);
            if (v < 0) v = 0;
            if (v > 100) v = 100;
            input.value = v;
            range.value = v;
        }
        function resetToDeepSeek() {
            {% for f in score_fields %}
                document.getElementById("{{ f.key }}").value = "{{ row[f.key] }}";
                document.getElementById("{{ f.key }}_range").value = "{{ row[f.key] }}";
            {% endfor %}
        }
    </script>
</head>
<body>
<div class="container">
    <div class="header">
        <div>
            <h1 class="title">페르소나 검수 #{{ row.persona_profile_id }}</h1>
            <p class="subtitle">
                {{ row.age_group }} / {{ row.gender }} / {{ row.region }} /
                {{ row.occupation or '-' }}
            </p>
        </div>
        <div class="actions">
            <a class="btn" href="{{ url_for('index') }}">목록</a>
            <a class="btn primary" href="{{ url_for('next_review') }}">다음 검수</a>
        </div>
    </div>

    {% for message in get_flashed_messages() %}
        <div class="flash">{{ message }}</div>
    {% endfor %}

    {% if human %}
        <div class="warn">
            이미 검수된 페르소나입니다. 저장하면 기존 HUMAN_CORRECTED 라벨을 덮어씁니다.
            검수자: {{ human.reviewer or '-' }}
        </div>
    {% endif %}

    <div class="grid">
        <div class="card">
            <h2 style="margin-top:0;">페르소나 정보</h2>
            <div class="profile">
<b>나이/성별</b>: {{ row.age }}세, {{ row.age_group }}, {{ row.gender }}
<b>지역</b>: {{ row.province or '-' }} {{ row.district or '-' }} / {{ row.region or '-' }}
<b>직업</b>: {{ row.occupation or '-' }}
<b>학력</b>: {{ row.education_level or '-' }}
<b>가족</b>: {{ row.family_type or '-' }}
<b>주거</b>: {{ row.housing_type or '-' }}
            </div>

            <h3>요약</h3>
            <div class="search-text" style="max-height:180px;">{{ row.persona_summary or '' }}</div>

            <h3>상세 설명</h3>
            <div class="search-text">{{ row.search_text or '' }}</div>
        </div>

        <div class="card">
            <form method="post" action="{{ url_for('save_review', label_review_id=row.deepseek_label_review_id) }}">
                <h2 style="margin-top:0;">점수 검수</h2>
                <p class="small">
                    왼쪽 숫자는 DeepSeek 원본 점수입니다. 오른쪽 입력값을 수정하고 저장하세요.
                </p>

                {% for f in score_fields %}
                    {% set human_value = human[f.key] if human else row[f.key] %}
                    <div class="score-row">
                        <div>
                            <span class="score-label">{{ f.label }}</span>
                            <span class="score-desc">{{ f.desc }}</span>
                        </div>
                        <div>
                            <input id="{{ f.key }}_range" type="range" min="0" max="100" value="{{ human_value }}" oninput="syncRange('{{ f.key }}')">
                        </div>
                        <div class="score-current">DS {{ row[f.key] }}</div>
                        <div>
                            <input id="{{ f.key }}" name="{{ f.key }}" type="number" min="0" max="100" value="{{ human_value }}" oninput="syncNumber('{{ f.key }}')">
                        </div>
                        <div class="reason">
                            <b>DeepSeek 근거:</b>
                            {{ reasons.get(f.camel) or reasons.get(f.key) or '-' }}
                        </div>
                    </div>
                {% endfor %}

                <div style="margin-top:18px;">
                    <label><b>검수 메모</b></label>
                    <textarea name="human_memo" placeholder="수정 이유나 애매한 점을 적어도 됩니다.">{{ human_memo or '' }}</textarea>
                </div>

                <div style="margin-top:12px;">
                    <label><b>검수자</b></label>
                    <input type="text" name="reviewer" value="{{ reviewer }}" placeholder="검수자 이름">
                </div>

                <div class="actions" style="margin-top:18px;">
                    <button class="btn ok" type="submit" name="action" value="save">저장</button>
                    <button class="btn primary" type="submit" name="action" value="save_next">저장 후 다음</button>
                    <button class="btn" type="button" onclick="resetToDeepSeek()">DeepSeek 점수로 되돌리기</button>
                </div>
            </form>
        </div>
    </div>
</div>
</body>
</html>
"""


STATS_TEMPLATE = """
<!doctype html>
<html lang="ko">
<head>
    <meta charset="utf-8">
    <title>점수 통계</title>
    <style>{{ css }}</style>
</head>
<body>
<div class="container">
    <div class="header">
        <div>
            <h1 class="title">점수 통계</h1>
            <p class="subtitle">DeepSeek / Human Corrected 비교</p>
        </div>
        <div class="actions">
            <a class="btn" href="{{ url_for('index') }}">목록</a>
            <a class="btn primary" href="{{ url_for('next_review') }}">다음 검수</a>
        </div>
    </div>

    <div class="card">
        <table class="table">
            <thead>
                <tr>
                    <th>점수</th>
                    <th>라벨</th>
                    <th>개수</th>
                    <th>최소</th>
                    <th>평균</th>
                    <th>최대</th>
                </tr>
            </thead>
            <tbody>
            {% for r in rows %}
                <tr>
                    <td>{{ r.score_label }}</td>
                    <td>{{ r.label_source }}</td>
                    <td>{{ r.cnt }}</td>
                    <td>{{ r.min_score }}</td>
                    <td>{{ r.avg_score }}</td>
                    <td>{{ r.max_score }}</td>
                </tr>
            {% endfor %}
            </tbody>
        </table>
    </div>
</div>
</body>
</html>
"""


def dict_row(cursor, row):
    return {desc[0]: row[idx] for idx, desc in enumerate(cursor.description)}


def connect_db(config):
    return psycopg2.connect(
        host=config["host"],
        port=config["port"],
        dbname=config["dbname"],
        user=config["user"],
        password=config["password"],
        connect_timeout=10,
    )


def ensure_human_batch(conn, config):
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO persona_label_batch (
                label_batch_id,
                batch_name,
                sample_size,
                label_source,
                model_name,
                prompt_version,
                input_csv_path,
                output_jsonl_path,
                status
            )
            VALUES (
                %s,
                %s,
                0,
                'HUMAN_CORRECTED',
                NULL,
                'human_review_web_v1',
                NULL,
                NULL,
                'RUNNING'
            )
            ON CONFLICT (label_batch_id) DO UPDATE SET
                status = 'RUNNING',
                updated_at = CURRENT_TIMESTAMP;
            """,
            (config["human_batch_id"], f"Human review for {config['source_batch_id']}"),
        )
    conn.commit()


def get_counts(conn, config):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT COUNT(*)
            FROM persona_label_review
            WHERE label_batch_id = %s
              AND label_source = 'DEEPSEEK_PSEUDO'
            """,
            (config["source_batch_id"],),
        )
        total = cur.fetchone()[0]

        cur.execute(
            """
            SELECT COUNT(*)
            FROM persona_label_review h
            WHERE h.label_batch_id = %s
              AND h.label_source = 'HUMAN_CORRECTED'
              AND EXISTS (
                  SELECT 1
                  FROM persona_label_review d
                  WHERE d.label_batch_id = %s
                    AND d.label_source = 'DEEPSEEK_PSEUDO'
                    AND d.persona_profile_id = h.persona_profile_id
              )
            """,
            (config["human_batch_id"], config["source_batch_id"]),
        )
        reviewed = cur.fetchone()[0]

    remaining = max(total - reviewed, 0)
    progress = round(reviewed * 100 / total, 1) if total else 0
    return total, reviewed, remaining, progress


def get_list_rows(conn, config, status="unreviewed", q=None, limit=50):
    where = [
        "d.label_batch_id = %s",
        "d.label_source = 'DEEPSEEK_PSEUDO'",
    ]
    params = [config["human_batch_id"], config["source_batch_id"]]

    if status == "unreviewed":
        where.append("h.id IS NULL")
    elif status == "reviewed":
        where.append("h.id IS NOT NULL")

    if q:
        where.append(
            """
            (
                p.occupation ILIKE %s
                OR p.region ILIKE %s
                OR p.province ILIKE %s
                OR p.district ILIKE %s
                OR p.persona_summary ILIKE %s
                OR p.search_text ILIKE %s
            )
            """
        )
        like = f"%{q}%"
        params.extend([like, like, like, like, like, like])

    sql = f"""
    SELECT
        d.id AS deepseek_label_review_id,
        h.id AS human_id,
        p.id AS persona_profile_id,
        p.age,
        p.age_group,
        p.gender,
        p.region,
        p.province,
        p.district,
        p.occupation,
        p.education_level,
        p.family_type,
        p.housing_type,
        p.persona_summary
    FROM persona_label_review d
    JOIN persona_profile p ON p.id = d.persona_profile_id
    LEFT JOIN persona_label_review h
      ON h.persona_profile_id = d.persona_profile_id
     AND h.label_batch_id = %s
     AND h.label_source = 'HUMAN_CORRECTED'
    WHERE {" AND ".join(where)}
    ORDER BY
        CASE WHEN h.id IS NULL THEN 0 ELSE 1 END,
        d.id
    LIMIT %s
    """
    params.append(limit)

    with conn.cursor() as cur:
        cur.execute(sql, params)
        return [dict_row(cur, r) for r in cur.fetchall()]


def get_review_row(conn, config, label_review_id):
    score_select = ", ".join([f"d.{f['key']}" for f in SCORE_FIELDS])
    sql = f"""
    SELECT
        d.id AS deepseek_label_review_id,
        d.reason_json,
        {score_select},
        p.id AS persona_profile_id,
        p.source_id,
        p.age,
        p.age_group,
        p.gender,
        p.region,
        p.province,
        p.district,
        p.occupation,
        p.education_level,
        p.family_type,
        p.housing_type,
        p.persona_summary,
        p.search_text
    FROM persona_label_review d
    JOIN persona_profile p ON p.id = d.persona_profile_id
    WHERE d.id = %s
      AND d.label_batch_id = %s
      AND d.label_source = 'DEEPSEEK_PSEUDO'
    """
    with conn.cursor() as cur:
        cur.execute(sql, (label_review_id, config["source_batch_id"]))
        row = cur.fetchone()
        return dict_row(cur, row) if row else None


def get_human_row(conn, config, persona_profile_id):
    score_select = ", ".join([f"h.{f['key']}" for f in SCORE_FIELDS])
    sql = f"""
    SELECT
        h.id,
        h.reviewer,
        h.reason_json,
        {score_select}
    FROM persona_label_review h
    WHERE h.persona_profile_id = %s
      AND h.label_batch_id = %s
      AND h.label_source = 'HUMAN_CORRECTED'
    """
    with conn.cursor() as cur:
        cur.execute(sql, (persona_profile_id, config["human_batch_id"]))
        row = cur.fetchone()
        return dict_row(cur, row) if row else None


def get_next_unreviewed_id(conn, config):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT d.id
            FROM persona_label_review d
            LEFT JOIN persona_label_review h
              ON h.persona_profile_id = d.persona_profile_id
             AND h.label_batch_id = %s
             AND h.label_source = 'HUMAN_CORRECTED'
            WHERE d.label_batch_id = %s
              AND d.label_source = 'DEEPSEEK_PSEUDO'
              AND h.id IS NULL
            ORDER BY d.id
            LIMIT 1
            """,
            (config["human_batch_id"], config["source_batch_id"]),
        )
        row = cur.fetchone()
        return row[0] if row else None


def clamp_score(value):
    try:
        v = int(value)
    except Exception:
        v = 50
    return max(0, min(100, v))


def save_human_review(conn, config, row, form):
    scores = {f["key"]: clamp_score(form.get(f["key"])) for f in SCORE_FIELDS}
    reviewer = (form.get("reviewer") or config["reviewer"] or "").strip()
    human_memo = (form.get("human_memo") or "").strip()

    reason_json = {
        "source_label_batch_id": config["source_batch_id"],
        "source_label_review_id": row["deepseek_label_review_id"],
        "human_memo": human_memo,
        "deepseek_reasons": row.get("reason_json") or {},
        "reviewed_at": datetime.now().isoformat(),
    }

    sql = """
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
    VALUES (
        %s, %s, 'HUMAN_CORRECTED',

        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,

        %s,
        %s,
        TRUE,
        NULL,
        NULL
    )
    ON CONFLICT (label_batch_id, persona_profile_id, label_source) DO UPDATE SET
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
        reviewed = TRUE,
        error_message = NULL,
        updated_at = CURRENT_TIMESTAMP;
    """
    with conn.cursor() as cur:
        cur.execute(
            sql,
            (
                row["persona_profile_id"],
                config["human_batch_id"],

                scores["digital_affinity_score"],
                scores["price_sensitivity_score"],
                scores["trust_sensitivity_score"],
                scores["convenience_need_score"],
                scores["quality_sensitivity_score"],
                scores["novelty_acceptance_score"],
                scores["local_affinity_score"],
                scores["family_decision_score"],
                scores["health_safety_sensitivity_score"],
                scores["review_dependency_score"],

                Json(reason_json, dumps=lambda obj: json.dumps(obj, ensure_ascii=False, default=str)),
                reviewer,
            ),
        )
    conn.commit()


def create_app(config):
    app = Flask(__name__)
    app.secret_key = config["secret_key"]

    @app.before_request
    def setup():
        conn = connect_db(config)
        try:
            ensure_human_batch(conn, config)
        finally:
            conn.close()

    @app.route("/")
    def index():
        status = request.args.get("status", "unreviewed")
        q = request.args.get("q", "").strip() or None
        conn = connect_db(config)
        try:
            total, reviewed, remaining, progress = get_counts(conn, config)
            rows = get_list_rows(conn, config, status=status, q=q)
        finally:
            conn.close()
        return render_template_string(
            INDEX_TEMPLATE,
            css=BASE_CSS,
            rows=rows,
            total=total,
            reviewed=reviewed,
            remaining=remaining,
            progress=progress,
            source_batch_id=config["source_batch_id"],
            human_batch_id=config["human_batch_id"],
            status=status,
            q=q,
        )

    @app.route("/next")
    def next_review():
        conn = connect_db(config)
        try:
            next_id = get_next_unreviewed_id(conn, config)
        finally:
            conn.close()
        if not next_id:
            flash("검수할 항목이 없습니다.")
            return redirect(url_for("index", status="all"))
        return redirect(url_for("review", label_review_id=next_id))

    @app.route("/review/<int:label_review_id>")
    def review(label_review_id):
        conn = connect_db(config)
        try:
            row = get_review_row(conn, config, label_review_id)
            if not row:
                flash("대상을 찾을 수 없습니다.")
                return redirect(url_for("index"))
            human = get_human_row(conn, config, row["persona_profile_id"])
        finally:
            conn.close()

        reasons = row.get("reason_json") or {}
        human_memo = ""
        if human and isinstance(human.get("reason_json"), dict):
            human_memo = human["reason_json"].get("human_memo", "")

        return render_template_string(
            REVIEW_TEMPLATE,
            css=BASE_CSS,
            row=row,
            human=human,
            human_memo=human_memo,
            reasons=reasons,
            score_fields=SCORE_FIELDS,
            reviewer=config["reviewer"],
        )

    @app.route("/review/<int:label_review_id>/save", methods=["POST"])
    def save_review(label_review_id):
        conn = connect_db(config)
        try:
            row = get_review_row(conn, config, label_review_id)
            if not row:
                flash("대상을 찾을 수 없습니다.")
                return redirect(url_for("index"))
            save_human_review(conn, config, row, request.form)
            action = request.form.get("action")
            flash("검수 결과를 저장했습니다.")
            if action == "save_next":
                next_id = get_next_unreviewed_id(conn, config)
                if next_id:
                    return redirect(url_for("review", label_review_id=next_id))
                return redirect(url_for("index", status="all"))
            return redirect(url_for("review", label_review_id=label_review_id))
        finally:
            conn.close()

    @app.route("/stats")
    def stats():
        sql_parts = []
        params = []
        for f in SCORE_FIELDS:
            label = f["label"]
            key = f["key"]
            sql_parts.append(
                f"""
                SELECT
                    %s AS score_label,
                    'DEEPSEEK_PSEUDO' AS label_source,
                    COUNT(*) AS cnt,
                    MIN({key}) AS min_score,
                    ROUND(AVG({key}), 2) AS avg_score,
                    MAX({key}) AS max_score
                FROM persona_label_review
                WHERE label_batch_id = %s
                  AND label_source = 'DEEPSEEK_PSEUDO'
                """
            )
            params.extend([label, config["source_batch_id"]])
            sql_parts.append(
                f"""
                SELECT
                    %s AS score_label,
                    'HUMAN_CORRECTED' AS label_source,
                    COUNT(*) AS cnt,
                    MIN({key}) AS min_score,
                    ROUND(AVG({key}), 2) AS avg_score,
                    MAX({key}) AS max_score
                FROM persona_label_review
                WHERE label_batch_id = %s
                  AND label_source = 'HUMAN_CORRECTED'
                """
            )
            params.extend([label, config["human_batch_id"]])
        sql = " UNION ALL ".join(sql_parts)
        conn = connect_db(config)
        try:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                rows = [dict_row(cur, r) for r in cur.fetchall()]
        finally:
            conn.close()
        return render_template_string(STATS_TEMPLATE, css=BASE_CSS, rows=rows)

    return app


def parse_args():
    parser = argparse.ArgumentParser(description="CustomerPreview 페르소나 라벨 검수 웹 페이지")
    parser.add_argument("--source-batch-id", required=True, help="DeepSeek pseudo-label batch id")
    parser.add_argument("--human-batch-id", required=True, help="사람 검수 결과를 저장할 batch id")
    parser.add_argument("--reviewer", default="ssg", help="기본 검수자 이름")

    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="precustomer")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")

    parser.add_argument("--web-host", default="127.0.0.1")
    parser.add_argument("--web-port", type=int, default=5001)
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--secret-key", default="local-review-secret-key")
    return parser.parse_args()


def main():
    args = parse_args()
    config = {
        "source_batch_id": args.source_batch_id,
        "human_batch_id": args.human_batch_id,
        "reviewer": args.reviewer,
        "host": args.host,
        "port": args.port,
        "dbname": args.dbname,
        "user": args.user,
        "password": args.password,
        "secret_key": args.secret_key,
    }
    app = create_app(config)
    print("=" * 80)
    print("CustomerPreview 페르소나 검수 웹 페이지")
    print("=" * 80)
    print(f"DB: {args.host}:{args.port}/{args.dbname}")
    print(f"DeepSeek batch: {args.source_batch_id}")
    print(f"Human batch: {args.human_batch_id}")
    print(f"URL: http://{args.web_host}:{args.web_port}")
    print("=" * 80)
    app.run(host=args.web_host, port=args.web_port, debug=args.debug)


if __name__ == "__main__":
    main()
