#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
상세페이지 이미지 직접 분석 스크립트

파일명:
  analyze_detail_page_images.py

역할:
  1. report_order 조회
  2. page_snapshot 조회
  3. screenshot_path / important_images에서 이미지 경로 또는 이미지 URL 수집
  4. 이미지 파일을 직접 Vision 모델에 전송
  5. page_image_analysis 테이블에 이미지 분석 JSON 저장

기본 제공자:
  - OpenAI Responses API
  - 로컬 이미지 파일은 base64 data URL로 전송
  - 원격 이미지 URL은 URL 그대로 전송 가능

환경변수:
  export OPENAI_API_KEY="sk-..."

설치:
  pip install psycopg2-binary requests pillow

실행:
  python3 ./scripts/analyze_detail_page_images.py \
    --dbname precustomer \
    --order-id 16 \
    --image-path ./page_snapshots/creatine_detail.jpg

snapshot에 screenshot_path / important_images가 저장되어 있으면 image-path 없이도 가능:
  python3 ./scripts/analyze_detail_page_images.py \
    --dbname precustomer \
    --order-id 16

긴 상세페이지 캡처는 자동 분할:
  python3 ./scripts/analyze_detail_page_images.py \
    --dbname precustomer \
    --order-id 16 \
    --image-path ./page_snapshots/creatine_detail_long.jpg \
    --split-long-image

결과 확인:
  SELECT id, image_path, image_part_no, image_summary
  FROM page_image_analysis
  WHERE report_order_id = 16
  ORDER BY image_path, image_part_no;
"""

import argparse
import base64
import json
import mimetypes
import os
import re
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import psycopg2
import requests
from psycopg2.extras import RealDictCursor, Json

try:
    from PIL import Image
except Exception:
    Image = None


DEFAULT_PROVIDER = "openai"
DEFAULT_BASE_URL = "https://api.openai.com/v1"
DEFAULT_MODEL_NAME = "openai"
DEFAULT_MODEL_VERSION = "gpt-4.1-mini"
DEFAULT_ANALYSIS_VERSION = "detail_page_image_analysis_v1"


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)


def safe_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def as_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            if isinstance(parsed, dict):
                return parsed
        except Exception:
            pass
    return {}


def as_list(value: Any) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, str):
        value = value.strip()
        if not value:
            return []
        try:
            parsed = json.loads(value)
            if isinstance(parsed, list):
                return parsed
        except Exception:
            pass
        return [value]
    return [value]


def compact_text(value: Any, max_len: int = 12000) -> str:
    text = safe_text(value)
    text = re.sub(r"\r\n|\r", "\n", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) <= max_len:
        return text
    return text[: int(max_len * 0.7)] + "\n\n...[중간 생략]...\n\n" + text[-int(max_len * 0.3):]


def connect_db(args):
    return psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.dbname,
        user=args.user,
        password=args.password,
        connect_timeout=10,
    )


def ensure_table(conn) -> None:
    ddl = """
    CREATE TABLE IF NOT EXISTS page_image_analysis (
        id BIGSERIAL PRIMARY KEY,

        report_order_id BIGINT NOT NULL,
        page_snapshot_id BIGINT,

        image_path TEXT NOT NULL,
        image_role VARCHAR(50) NOT NULL DEFAULT 'DETAIL_PAGE',
        image_part_no INTEGER NOT NULL DEFAULT 1,
        image_part_count INTEGER NOT NULL DEFAULT 1,

        analysis_version VARCHAR(100) NOT NULL DEFAULT 'detail_page_image_analysis_v1',
        model_name VARCHAR(100) NOT NULL,
        model_version VARCHAR(100) NOT NULL,

        image_summary TEXT,
        visible_text TEXT,

        visual_trust_elements JSONB NOT NULL DEFAULT '[]'::jsonb,
        visual_purchase_drivers JSONB NOT NULL DEFAULT '[]'::jsonb,
        visual_purchase_barriers JSONB NOT NULL DEFAULT '[]'::jsonb,
        visible_claims JSONB NOT NULL DEFAULT '[]'::jsonb,
        visible_prices JSONB NOT NULL DEFAULT '[]'::jsonb,
        visible_certifications JSONB NOT NULL DEFAULT '[]'::jsonb,
        visible_usage_instructions JSONB NOT NULL DEFAULT '[]'::jsonb,
        design_feedback JSONB NOT NULL DEFAULT '[]'::jsonb,
        information_gaps JSONB NOT NULL DEFAULT '[]'::jsonb,
        safety_or_compliance_notes JSONB NOT NULL DEFAULT '[]'::jsonb,

        raw_analysis JSONB NOT NULL DEFAULT '{}'::jsonb,

        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        CONSTRAINT fk_rdpia_order
            FOREIGN KEY (report_order_id)
            REFERENCES report_order(id)
            ON DELETE CASCADE,

        CONSTRAINT fk_rdpia_snapshot
            FOREIGN KEY (page_snapshot_id)
            REFERENCES page_snapshot(id)
            ON DELETE SET NULL,

        CONSTRAINT uk_rdpia_snapshot_image_part_version
            UNIQUE (report_order_id, image_path, image_part_no, analysis_version)
    );

    CREATE INDEX IF NOT EXISTS idx_rdpia_order_id
        ON page_image_analysis(report_order_id);

    CREATE INDEX IF NOT EXISTS idx_rdpia_snapshot_id
        ON page_image_analysis(page_snapshot_id);

    CREATE INDEX IF NOT EXISTS idx_rdpia_image_path
        ON page_image_analysis(image_path);

    COMMENT ON TABLE page_image_analysis IS '상세페이지 이미지 자체를 Vision 모델로 직접 분석한 결과';
    COMMENT ON COLUMN page_image_analysis.image_path IS '분석한 원본 이미지 경로 또는 URL';
    COMMENT ON COLUMN page_image_analysis.image_part_no IS '긴 상세페이지를 분할한 경우의 파트 번호';
    COMMENT ON COLUMN page_image_analysis.image_summary IS '이미지 전체 요약';
    COMMENT ON COLUMN page_image_analysis.visible_text IS '이미지에서 식별된 주요 텍스트/OCR 요약';
    COMMENT ON COLUMN page_image_analysis.visual_trust_elements IS '이미지에서 보이는 신뢰 요소';
    COMMENT ON COLUMN page_image_analysis.visual_purchase_drivers IS '이미지에서 보이는 구매 촉진 요소';
    COMMENT ON COLUMN page_image_analysis.visual_purchase_barriers IS '이미지에서 보이는 구매 저항 요소';
    COMMENT ON COLUMN page_image_analysis.visible_claims IS '이미지에서 보이는 주장/혜택/강조 문구';
    COMMENT ON COLUMN page_image_analysis.visible_prices IS '이미지에서 보이는 가격/할인/용량 정보';
    COMMENT ON COLUMN page_image_analysis.visible_certifications IS '이미지에서 보이는 인증/검사/정품 관련 정보';
    COMMENT ON COLUMN page_image_analysis.visible_usage_instructions IS '이미지에서 보이는 사용법/섭취법';
    COMMENT ON COLUMN page_image_analysis.design_feedback IS '디자인/가독성/정보 배치 피드백';
    COMMENT ON COLUMN page_image_analysis.information_gaps IS '이미지 기준 부족해 보이는 정보';
    COMMENT ON COLUMN page_image_analysis.safety_or_compliance_notes IS '건강/효능/안전/광고 표현 관련 주의사항';
    """
    with conn.cursor() as cur:
        cur.execute(ddl)
    conn.commit()


def fetch_order(conn, order_id: int) -> Dict[str, Any]:
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute("SELECT * FROM report_order WHERE id = %s", (order_id,))
        row = cur.fetchone()
    if not row:
        raise RuntimeError(f"report_order를 찾을 수 없습니다. order_id={order_id}")
    return dict(row)


def fetch_snapshot(conn, order_id: int, snapshot_id: Optional[int]) -> Optional[Dict[str, Any]]:
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        if snapshot_id:
            cur.execute("SELECT * FROM page_snapshot WHERE id = %s", (snapshot_id,))
        else:
            cur.execute(
                """
                SELECT *
                FROM page_snapshot
                WHERE report_order_id = %s
                ORDER BY captured_at DESC, id DESC
                LIMIT 1
                """,
                (order_id,),
            )
        row = cur.fetchone()
    return dict(row) if row else None


def guess_mime(path_or_url: str) -> str:
    mime, _ = mimetypes.guess_type(path_or_url)
    if mime:
        return mime
    lower = path_or_url.lower()
    if lower.endswith(".png"):
        return "image/png"
    if lower.endswith(".webp"):
        return "image/webp"
    if lower.endswith(".gif"):
        return "image/gif"
    return "image/jpeg"


def is_url(value: str) -> bool:
    return value.startswith("http://") or value.startswith("https://")


def image_to_data_url(image_path: str) -> str:
    mime = guess_mime(image_path)
    with open(image_path, "rb") as f:
        encoded = base64.b64encode(f.read()).decode("utf-8")
    return f"data:{mime};base64,{encoded}"


def resolve_image_path(path_value: str, base_dir: Optional[str]) -> str:
    value = safe_text(path_value)
    if not value:
        return value
    if is_url(value):
        return value
    p = Path(value).expanduser()
    if p.exists():
        return str(p)
    if base_dir:
        p2 = Path(base_dir).expanduser() / value
        if p2.exists():
            return str(p2)
    return value


def collect_image_candidates(args, snapshot: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    items: List[Dict[str, Any]] = []

    for p in args.image_path or []:
        items.append({
            "path": resolve_image_path(p, args.image_base_dir),
            "role": "DETAIL_PAGE",
            "source": "cli",
        })

    if snapshot and not args.ignore_snapshot_images:
        for key in ["screenshot_path", "image_path", "main_image_path", "full_screenshot_path"]:
            p = safe_text(snapshot.get(key))
            if p:
                items.append({
                    "path": resolve_image_path(p, args.image_base_dir),
                    "role": "DETAIL_PAGE",
                    "source": f"snapshot.{key}",
                })

        for obj in as_list(snapshot.get("important_images")):
            if isinstance(obj, dict):
                p = safe_text(
                    obj.get("path")
                    or obj.get("image_path")
                    or obj.get("url")
                    or obj.get("imageUrl")
                    or obj.get("src")
                )
                role = safe_text(obj.get("role") or obj.get("type") or "DETAIL_PAGE")
            else:
                p = safe_text(obj)
                role = "DETAIL_PAGE"

            if p:
                items.append({
                    "path": resolve_image_path(p, args.image_base_dir),
                    "role": role or "DETAIL_PAGE",
                    "source": "snapshot.important_images",
                })

    seen = set()
    result = []
    for item in items:
        key = item["path"]
        if key and key not in seen:
            seen.add(key)
            result.append(item)

    return result


def split_long_image(image_path: str, args) -> List[Tuple[str, int, int]]:
    """
    반환: [(path_to_send, part_no, part_count)]
    URL은 분할하지 않는다.
    """
    if is_url(image_path):
        return [(image_path, 1, 1)]

    p = Path(image_path)
    if not p.exists():
        raise FileNotFoundError(f"이미지 파일을 찾을 수 없습니다: {image_path}")

    if not args.split_long_image:
        return [(str(p), 1, 1)]

    if Image is None:
        log("[WARN] Pillow가 없어 긴 이미지 분할을 건너뜁니다. pip install pillow 권장")
        return [(str(p), 1, 1)]

    img = Image.open(p)
    width, height = img.size

    if height <= args.max_chunk_height:
        return [(str(p), 1, 1)]

    out_dir = Path(args.chunk_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    overlap = max(0, args.chunk_overlap)
    step = max(1, args.max_chunk_height - overlap)

    chunks = []
    y = 0
    part_no = 1

    while y < height:
        y2 = min(height, y + args.max_chunk_height)
        crop = img.crop((0, y, width, y2))

        suffix = p.suffix.lower()
        if suffix not in [".jpg", ".jpeg", ".png", ".webp"]:
            suffix = ".jpg"

        out_path = out_dir / f"{p.stem}_part_{part_no:02d}{suffix}"

        if suffix in [".jpg", ".jpeg"]:
            crop = crop.convert("RGB")
            crop.save(out_path, quality=args.jpeg_quality)
        else:
            crop.save(out_path)

        chunks.append(str(out_path))

        if y2 >= height:
            break

        y += step
        part_no += 1

    part_count = len(chunks)
    return [(path, idx + 1, part_count) for idx, path in enumerate(chunks)]


def build_prompt(order: Dict[str, Any], snapshot: Optional[Dict[str, Any]], image_item: Dict[str, Any], part_no: int, part_count: int) -> str:
    product_name = safe_text(snapshot.get("product_title")) if snapshot else safe_text(order.get("project_name"))
    price_text = safe_text(snapshot.get("price_text")) if snapshot else safe_text(order.get("price_text"))

    context = {
        "orderId": order.get("id"),
        "projectName": safe_text(order.get("project_name")),
        "productName": product_name,
        "priceText": price_text,
        "targetCustomer": compact_text(order.get("target_customer"), 1500),
        "mainQuestion": compact_text(order.get("main_question"), 1500),
        "detailDescription": compact_text(order.get("detail_description"), 1500),
        "imageSource": image_item.get("source"),
        "imageRole": image_item.get("role"),
        "imagePartNo": part_no,
        "imagePartCount": part_count,
    }

    return f"""
너는 한국 이커머스 상세페이지 이미지를 직접 분석하는 Vision 분석가다.

분석 대상:
{json.dumps(context, ensure_ascii=False, indent=2)}

목표:
- 이미지에 실제로 보이는 정보만 분석한다.
- 상품 상세페이지 리포트에 사용할 수 있도록, 시각 요소/텍스트/OCR/신뢰 요소/구매 장벽/개선점을 구조화한다.
- 건강보조식품, 식품, 의료, 안전 관련 상품에서는 효능이나 안전성을 단정하지 않는다.
- 이미지에 보이지 않는 인증, 검사, 임상, 환불, 정품 보증을 사실처럼 만들지 않는다.
- 이미지가 긴 상세페이지의 일부라면 현재 파트에서 보이는 내용만 작성한다.

출력 규칙:
- JSON 객체 하나만 반환한다.
- 마크다운 코드블록 금지.
- 모든 배열은 문자열 배열로 작성한다.
- 모르는 내용은 빈 배열 또는 빈 문자열로 둔다.

출력 템플릿:
{{
  "imageSummary": "이미지 전체 요약",
  "visibleText": "이미지에서 읽히는 주요 텍스트/OCR 요약",
  "visualTrustElements": [],
  "visualPurchaseDrivers": [],
  "visualPurchaseBarriers": [],
  "visibleClaims": [],
  "visiblePrices": [],
  "visibleCertifications": [],
  "visibleUsageInstructions": [],
  "designFeedback": [],
  "informationGaps": [],
  "safetyOrComplianceNotes": [],
  "recommendedUseInReport": "이 이미지 분석 결과를 최종 리포트에서 어떻게 써야 하는지"
}}
""".strip()


def extract_output_text(data: Dict[str, Any]) -> str:
    if isinstance(data.get("output_text"), str):
        return data["output_text"]

    texts = []
    for item in data.get("output", []) or []:
        if isinstance(item, dict) and item.get("type") == "message":
            for c in item.get("content", []) or []:
                if isinstance(c, dict):
                    if c.get("type") in ["output_text", "text"]:
                        texts.append(safe_text(c.get("text")))
        elif isinstance(item, dict):
            for c in item.get("content", []) or []:
                if isinstance(c, dict) and c.get("text"):
                    texts.append(safe_text(c.get("text")))

    return "\n".join([t for t in texts if t])


def parse_json_content(content: str) -> Dict[str, Any]:
    content = safe_text(content)
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?", "", content).strip()
        content = re.sub(r"```$", "", content).strip()
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        start = content.find("{")
        end = content.rfind("}")
        if start >= 0 and end > start:
            return json.loads(content[start:end + 1])
        raise


def normalize_analysis(raw: Dict[str, Any]) -> Dict[str, Any]:
    def arr(key: str) -> List[str]:
        result = []
        for x in as_list(raw.get(key)):
            if isinstance(x, dict):
                txt = safe_text(x.get("text") or x.get("label") or x.get("description") or json.dumps(x, ensure_ascii=False))
            else:
                txt = safe_text(x)
            if txt:
                result.append(txt)
        return result

    return {
        "imageSummary": safe_text(raw.get("imageSummary")),
        "visibleText": safe_text(raw.get("visibleText")),
        "visualTrustElements": arr("visualTrustElements"),
        "visualPurchaseDrivers": arr("visualPurchaseDrivers"),
        "visualPurchaseBarriers": arr("visualPurchaseBarriers"),
        "visibleClaims": arr("visibleClaims"),
        "visiblePrices": arr("visiblePrices"),
        "visibleCertifications": arr("visibleCertifications"),
        "visibleUsageInstructions": arr("visibleUsageInstructions"),
        "designFeedback": arr("designFeedback"),
        "informationGaps": arr("informationGaps"),
        "safetyOrComplianceNotes": arr("safetyOrComplianceNotes"),
        "recommendedUseInReport": safe_text(raw.get("recommendedUseInReport")),
        "raw": raw,
    }


def call_openai_vision(args, order: Dict[str, Any], snapshot: Optional[Dict[str, Any]], image_item: Dict[str, Any], image_to_send: str, part_no: int, part_count: int) -> Dict[str, Any]:
    api_key = args.api_key or os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY 환경변수 또는 --api-key가 필요합니다.")

    prompt = build_prompt(order, snapshot, image_item, part_no, part_count)

    if is_url(image_to_send):
        image_url = image_to_send
    else:
        image_url = image_to_data_url(image_to_send)

    body = {
        "model": args.model_version,
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": prompt},
                    {
                        "type": "input_image",
                        "image_url": image_url,
                        "detail": args.image_detail,
                    },
                ],
            }
        ],
        "max_output_tokens": args.max_tokens,
    }

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    url = args.base_url.rstrip("/") + "/responses"

    last_error = None
    for attempt in range(1, args.max_retries + 1):
        try:
            resp = requests.post(url, headers=headers, json=body, timeout=args.timeout)
            if resp.status_code >= 400:
                raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:1500]}")

            data = resp.json()
            output_text = extract_output_text(data)
            if not output_text:
                raise RuntimeError(f"응답 텍스트를 찾지 못했습니다: {json.dumps(data, ensure_ascii=False)[:1000]}")

            parsed = parse_json_content(output_text)
            parsed["_apiUsage"] = data.get("usage", {})
            parsed["_responseId"] = data.get("id")
            return normalize_analysis(parsed)
        except Exception as e:
            last_error = e
            if attempt < args.max_retries:
                time.sleep(args.retry_sleep * attempt)

    raise RuntimeError(f"Vision 호출 실패: {last_error}")


def call_gemini_vision(args, order: Dict[str, Any], snapshot: Optional[Dict[str, Any]], image_item: Dict[str, Any], image_to_send: str, part_no: int, part_count: int) -> Dict[str, Any]:
    api_key = args.api_key or os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError("GEMINI_API_KEY 환경변수 또는 --api-key가 필요합니다.")

    prompt = build_prompt(order, snapshot, image_item, part_no, part_count)

    # Gemini needs base64, not data URL
    if is_url(image_to_send):
        resp = requests.get(image_to_send, timeout=30)
        resp.raise_for_status()
        image_bytes = resp.content
        mime_type = resp.headers.get("Content-Type", "image/jpeg")
    else:
        with open(image_to_send, "rb") as f:
            image_bytes = f.read()
        mime_type = mimetypes.guess_type(image_to_send)[0] or "image/jpeg"
        if mime_type == "image/webp":
            mime_type = "image/webp"
        elif mime_type not in ("image/jpeg", "image/png", "image/webp"):
            mime_type = "image/jpeg"

    image_b64 = base64.b64encode(image_bytes).decode("utf-8")

    body = {
        "contents": [{
            "parts": [
                {"text": prompt},
                {"inlineData": {"mimeType": mime_type, "data": image_b64}}
            ]
        }],
        "generationConfig": {
            "maxOutputTokens": args.max_tokens,
        },
    }

    url = f"https://generativelanguage.googleapis.com/v1beta/models/{args.model_version}:generateContent"
    headers = {"Content-Type": "application/json"}

    last_error = None
    for attempt in range(1, args.max_retries + 1):
        try:
            resp = requests.post(url, params={"key": api_key}, headers=headers, json=body, timeout=args.timeout)
            if resp.status_code >= 400:
                raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:1500]}")

            data = resp.json()
            candidates = data.get("candidates", [])
            if not candidates:
                raise RuntimeError(f"Gemini 응답에 candidates가 없습니다: {json.dumps(data, ensure_ascii=False)[:1000]}")

            parts = candidates[0].get("content", {}).get("parts", [])
            output_text = ""
            for part in parts:
                if "text" in part:
                    output_text += part["text"]

            if not output_text:
                raise RuntimeError(f"Gemini 응답 텍스트를 찾지 못했습니다: {json.dumps(data, ensure_ascii=False)[:1000]}")

            parsed = parse_json_content(output_text)
            return normalize_analysis(parsed)
        except Exception as e:
            last_error = e
            if attempt < args.max_retries:
                time.sleep(args.retry_sleep * attempt)

    raise RuntimeError(f"Gemini Vision 호출 실패: {last_error}")


def image_to_base64(image_path: str) -> Tuple[str, str]:
    """이미지 파일을 base64로 인코딩. (data_url, mime_type) 반환"""
    mime_type = mimetypes.guess_type(image_path)[0] or "image/jpeg"
    with open(image_path, "rb") as f:
        image_bytes = f.read()
    b64 = base64.b64encode(image_bytes).decode("utf-8")
    return f"data:{mime_type};base64,{b64}", mime_type


def upsert_analysis(
    conn,
    args,
    order_id: int,
    snapshot_id: Optional[int],
    image_path: str,
    image_role: str,
    part_no: int,
    part_count: int,
    analysis: Dict[str, Any],
) -> int:
    sql = """
    INSERT INTO page_image_analysis (
        report_order_id,
        page_snapshot_id,
        image_path,
        image_role,
        image_part_no,
        image_part_count,
        analysis_version,
        model_name,
        model_version,
        image_summary,
        visible_text,
        visual_trust_elements,
        visual_purchase_drivers,
        visual_purchase_barriers,
        visible_claims,
        visible_prices,
        visible_certifications,
        visible_usage_instructions,
        design_feedback,
        information_gaps,
        safety_or_compliance_notes,
        raw_analysis
    )
    VALUES (
        %s, %s, %s, %s, %s, %s,
        %s, %s, %s,
        %s, %s,
        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
    )
    ON CONFLICT (report_order_id, image_path, image_part_no, analysis_version) DO UPDATE SET
        page_snapshot_id = EXCLUDED.page_snapshot_id,
        image_role = EXCLUDED.image_role,
        image_part_count = EXCLUDED.image_part_count,
        model_name = EXCLUDED.model_name,
        model_version = EXCLUDED.model_version,
        image_summary = EXCLUDED.image_summary,
        visible_text = EXCLUDED.visible_text,
        visual_trust_elements = EXCLUDED.visual_trust_elements,
        visual_purchase_drivers = EXCLUDED.visual_purchase_drivers,
        visual_purchase_barriers = EXCLUDED.visual_purchase_barriers,
        visible_claims = EXCLUDED.visible_claims,
        visible_prices = EXCLUDED.visible_prices,
        visible_certifications = EXCLUDED.visible_certifications,
        visible_usage_instructions = EXCLUDED.visible_usage_instructions,
        design_feedback = EXCLUDED.design_feedback,
        information_gaps = EXCLUDED.information_gaps,
        safety_or_compliance_notes = EXCLUDED.safety_or_compliance_notes,
        raw_analysis = EXCLUDED.raw_analysis,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id
    """

    values = (
        order_id,
        snapshot_id,
        image_path,
        image_role,
        part_no,
        part_count,
        args.analysis_version,
        args.model_name,
        args.model_version,
        analysis.get("imageSummary"),
        analysis.get("visibleText"),
        Json(analysis.get("visualTrustElements", [])),
        Json(analysis.get("visualPurchaseDrivers", [])),
        Json(analysis.get("visualPurchaseBarriers", [])),
        Json(analysis.get("visibleClaims", [])),
        Json(analysis.get("visiblePrices", [])),
        Json(analysis.get("visibleCertifications", [])),
        Json(analysis.get("visibleUsageInstructions", [])),
        Json(analysis.get("designFeedback", [])),
        Json(analysis.get("informationGaps", [])),
        Json(analysis.get("safetyOrComplianceNotes", [])),
        Json(analysis.get("raw", analysis)),
    )

    with conn.cursor() as cur:
        cur.execute(sql, values)
        return int(cur.fetchone()[0])


def run(conn, args) -> None:
    ensure_table(conn)

    order = fetch_order(conn, args.order_id)
    snapshot = fetch_snapshot(conn, args.order_id, args.snapshot_id)
    snapshot_id = int(snapshot["id"]) if snapshot else None

    images = collect_image_candidates(args, snapshot)

    if not images:
        raise RuntimeError("분석할 이미지가 없습니다. --image-path를 지정하거나 snapshot에 screenshot_path/important_images를 저장하세요.")

    if args.limit:
        images = images[: args.limit]

    log(f"[ORDER] id={order['id']}, project={order.get('project_name')}")
    log(f"[SNAPSHOT] id={snapshot_id}")
    log(f"[IMAGES] count={len(images)}")

    saved_count = 0

    for idx, item in enumerate(images, start=1):
        original_path = item["path"]
        image_role = safe_text(item.get("role")) or "DETAIL_PAGE"

        chunks = split_long_image(original_path, args)

        for chunk_path, part_no, part_count in chunks:
            log(f"[VISION] image={idx}/{len(images)}, part={part_no}/{part_count}, path={chunk_path}")

            if args.provider == "gemini":
                analysis = call_gemini_vision(args, order, snapshot, item, chunk_path, part_no, part_count)
            elif args.provider == "openai":
                analysis = call_openai_vision(args, order, snapshot, item, chunk_path, part_no, part_count)
            else:
                raise RuntimeError(f"지원하지 않는 provider={args.provider}입니다. openai 또는 gemini를 사용하세요.")

            print()
            print("=" * 100)
            print(f"IMAGE ANALYSIS image={idx}, part={part_no}/{part_count}")
            print("=" * 100)
            print(json.dumps(analysis, ensure_ascii=False, indent=2)[:6000])
            print("=" * 100)

            if args.dry_run:
                continue

            # 저장 image_path는 원본 경로를 유지한다. part_no로 구분.
            analysis_id = upsert_analysis(
                conn=conn,
                args=args,
                order_id=int(order["id"]),
                snapshot_id=snapshot_id,
                image_path=original_path,
                image_role=image_role,
                part_no=part_no,
                part_count=part_count,
                analysis=analysis,
            )
            conn.commit()
            saved_count += 1
            log(f"[SAVED] id={analysis_id}")

            if args.sleep > 0:
                time.sleep(args.sleep)

    if args.dry_run:
        conn.rollback()
        log("[DRY_RUN] 저장하지 않고 종료")
    else:
        log(f"[DONE] saved={saved_count}")


def parse_args():
    parser = argparse.ArgumentParser(description="상세페이지 이미지 직접 Vision 분석")

    parser.add_argument("--order-id", type=int, required=True)
    parser.add_argument("--snapshot-id", type=int, default=None)
    parser.add_argument("--image-path", action="append", default=None, help="분석할 로컬 이미지 경로 또는 이미지 URL. 여러 번 지정 가능")
    parser.add_argument("--image-base-dir", default=None, help="snapshot에 상대경로가 들어있는 경우 기준 디렉토리")
    parser.add_argument("--ignore-snapshot-images", action="store_true")
    parser.add_argument("--limit", type=int, default=None)

    parser.add_argument("--provider", default=DEFAULT_PROVIDER)
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--model-name", default=DEFAULT_MODEL_NAME)
    parser.add_argument("--model-version", default=DEFAULT_MODEL_VERSION)
    parser.add_argument("--analysis-version", default=DEFAULT_ANALYSIS_VERSION)

    parser.add_argument("--image-detail", choices=["low", "high", "auto"], default="high")
    parser.add_argument("--max-tokens", type=int, default=4000)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--max-retries", type=int, default=3)
    parser.add_argument("--retry-sleep", type=float, default=2.0)
    parser.add_argument("--sleep", type=float, default=0.2)
    parser.add_argument("--dry-run", action="store_true")

    parser.add_argument("--split-long-image", action="store_true", default=True)
    parser.add_argument("--no-split-long-image", dest="split_long_image", action="store_false")
    parser.add_argument("--max-chunk-height", type=int, default=1800)
    parser.add_argument("--chunk-overlap", type=int, default=160)
    parser.add_argument("--chunk-dir", default="./page_snapshots/_image_chunks")
    parser.add_argument("--jpeg-quality", type=int, default=92)

    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--dbname", default="precustomer")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="postgres")

    return parser.parse_args()


def main():
    args = parse_args()
    started_at = datetime.now()

    log("[START] analyze_detail_page_images")
    log(f"[CONFIG] db={args.host}:{args.port}/{args.dbname}, order_id={args.order_id}")
    log(f"[CONFIG] provider={args.provider}, model={args.model_version}, detail={args.image_detail}")

    conn = connect_db(args)
    try:
        run(conn, args)
    except KeyboardInterrupt:
        conn.rollback()
        log("[INTERRUPT] 사용자 중단")
        raise
    except Exception as e:
        conn.rollback()
        log("[ERROR] 실패")
        log(str(e))
        raise
    finally:
        conn.close()
        log(f"[END] elapsed={datetime.now() - started_at}")


if __name__ == "__main__":
    main()
