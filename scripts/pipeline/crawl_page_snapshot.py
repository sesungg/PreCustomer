#!/usr/bin/env python3
"""URL 크롤링 → page_snapshot 생성. 기존 snapshot이 있으면 스킵."""
import argparse
import json
import re
import sys
import traceback
from datetime import datetime

import psycopg2
import psycopg2.extras
import requests
from bs4 import BeautifulSoup

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)
MAX_VISIBLE_TEXT = 50000


def main():
    args = parse_args()
    conn = psycopg2.connect(
        host=args.host, port=args.port, dbname=args.dbname,
        user=args.user, password=args.password,
    )
    conn.autocommit = True

    try:
        run(conn, args)
    finally:
        conn.close()


def run(conn, args):
    order = fetch_order(conn, args.order_id)
    if not order:
        print(f"[SKIP] order_id={args.order_id} not found", file=sys.stderr)
        sys.exit(1)

    page_url = (order.get("page_url") or "").strip()
    if not page_url:
        save_fallback_snapshot(conn, args.order_id, order, "NO_PAGE_URL", "주문에 상세페이지 URL이 없습니다.")
        return

    # 기존 snapshot 있으면 스킵
    existing = fetch_existing(conn, args.order_id)
    if existing and not args.force:
        print(f"[SKIP] existing page_snapshot id={existing['id']} for order_id={args.order_id}")
        return

    print(f"[CRAWL] {page_url}")
    try:
        resp = requests.get(page_url, headers={"User-Agent": args.user_agent}, timeout=args.timeout)
        resp.raise_for_status()
    except Exception as e:
        print(f"[FAIL] HTTP error: {e}", file=sys.stderr)
        if not args.force:
            save_fallback_snapshot(conn, args.order_id, order, "HTTP_ERROR", str(e))
            return
        resp = None

    soup = BeautifulSoup(resp.text, "html.parser") if resp else None

    page_title = _text(soup.title) if soup else None
    product_title = _meta(soup, "og:title") or page_title
    price_text = _meta(soup, "product:price:amount") or _meta(soup, "og:price:amount")
    source_site = _extract_domain(page_url)
    visible_text = _body_text(soup)[:MAX_VISIBLE_TEXT] if soup else None
    extracted_text_summary = visible_text[:2000] if visible_text else None
    image_urls = _extract_images(soup, resp.text if resp else None)
    raw_meta = _extract_metadata(soup, resp.text if resp else None)

    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO page_snapshot (
                report_order_id, page_url, source_site, snapshot_status,
                page_title, product_title, price_text,
                visible_text, extracted_text_summary, image_urls,
                raw_meta_json, captured_at
            ) VALUES (%s,%s,%s,%s, %s,%s,%s, %s,%s,%s, %s::jsonb, %s)
            ON CONFLICT (report_order_id) DO UPDATE SET
                page_url = EXCLUDED.page_url,
                source_site = EXCLUDED.source_site,
                page_title = EXCLUDED.page_title,
                product_title = EXCLUDED.product_title,
                price_text = EXCLUDED.price_text,
                visible_text = EXCLUDED.visible_text,
                extracted_text_summary = EXCLUDED.extracted_text_summary,
                image_urls = EXCLUDED.image_urls,
                raw_meta_json = EXCLUDED.raw_meta_json,
                captured_at = EXCLUDED.captured_at
            """,
            (
                args.order_id, page_url, source_site, "CAPTURED",
                page_title, product_title, price_text,
                visible_text, extracted_text_summary, psycopg2.extras.Json(image_urls),
                psycopg2.extras.Json(raw_meta) if raw_meta else None,
                datetime.now(),
            ),
        )
        ri = raw_meta.get("reviewCount") if raw_meta else None
        rs = raw_meta.get("satisfactionScore") if raw_meta else None
        extra = ""
        if ri:
            extra += f", reviews={ri}"
        if rs:
            extra += f", rating={rs}"
        print(f"[OK] page_snapshot created for order_id={args.order_id}, images={len(image_urls)}{extra}")


def save_fallback_snapshot(conn, order_id, order, reason, error_message):
    """크롤링이 막혀도 이후 이미지/주문 정보 기반 분석을 진행할 수 있도록 최소 snapshot을 저장한다."""
    page_url = (order.get("page_url") or "").strip() or f"manual://report-order/{order_id}"
    project_name = (order.get("project_name") or "").strip()
    price_text = order.get("price_text")
    visible_text_parts = [
        f"상품명: {project_name}" if project_name else None,
        f"한 줄 설명: {order.get('one_line_description')}" if order.get("one_line_description") else None,
        f"상세 설명: {order.get('detail_description')}" if order.get("detail_description") else None,
        f"가격: {price_text}" if price_text else None,
        f"타겟 고객: {order.get('target_customer')}" if order.get("target_customer") else None,
        f"핵심 질문: {order.get('main_question')}" if order.get("main_question") else None,
        f"원본 URL: {page_url}" if page_url else None,
    ]
    visible_text = "\n".join(part for part in visible_text_parts if part)
    raw_meta = {
        "fallbackSource": "report_order",
        "fallbackReason": reason,
        "crawlError": error_message,
    }
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO page_snapshot (
                report_order_id, page_url, source_site, snapshot_status,
                page_title, product_title, price_text,
                visible_text, extracted_text_summary, image_urls,
                raw_meta_json, captured_at
            ) VALUES (%s,%s,%s,%s, %s,%s,%s, %s,%s,%s, %s::jsonb, %s)
            ON CONFLICT (report_order_id) DO UPDATE SET
                page_url = EXCLUDED.page_url,
                source_site = EXCLUDED.source_site,
                snapshot_status = EXCLUDED.snapshot_status,
                page_title = EXCLUDED.page_title,
                product_title = EXCLUDED.product_title,
                price_text = EXCLUDED.price_text,
                visible_text = EXCLUDED.visible_text,
                extracted_text_summary = EXCLUDED.extracted_text_summary,
                image_urls = EXCLUDED.image_urls,
                raw_meta_json = EXCLUDED.raw_meta_json,
                captured_at = EXCLUDED.captured_at
            """,
            (
                order_id, page_url, _extract_domain(page_url) or "manual", "FALLBACK",
                project_name, project_name, price_text,
                visible_text, visible_text[:2000] if visible_text else None, psycopg2.extras.Json([]),
                psycopg2.extras.Json(raw_meta), datetime.now(),
            ),
        )
    print(f"[FALLBACK] page_snapshot saved for order_id={order_id}, reason={reason}")


def fetch_order(conn, order_id):
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("SELECT * FROM report_order WHERE id=%s", (order_id,))
        return cur.fetchone()


def fetch_existing(conn, order_id):
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("SELECT * FROM page_snapshot WHERE report_order_id=%s ORDER BY captured_at DESC LIMIT 1", (order_id,))
        return cur.fetchone()


def _text(el):
    return el.get_text(strip=True) if el else None


def _meta(soup, prop):
    if not soup:
        return None
    tag = soup.find("meta", attrs={"property": prop}) or soup.find("meta", attrs={"name": prop})
    return tag.get("content", "").strip() if tag else None


def _body_text(soup):
    if not soup:
        return None
    for el in soup.select("script,style,noscript,iframe"):
        el.decompose()
    return soup.body.get_text("\n", strip=True) if soup.body else None


def _extract_domain(url):
    m = re.match(r"https?://([^/]+)", url)
    return m.group(1) if m else None


def _extract_images(soup, html_text=None):
    urls = []
    seen = set()

    def add(url):
        if not url:
            return
        if url.startswith("//"):
            url = "https:" + url
        if not url.startswith("http"):
            return
        low = url.lower()
        if any(s in low for s in ["icon", "logo", "btn", "banner", "pixel", "tracking"]):
            return
        if url not in seen:
            seen.add(url)
            urls.append(url)

    # OG image
    add(_meta(soup, "og:image"))

    # HTML img tags
    if soup:
        for img in soup.select("img[src]"):
            src = img.get("data-src") or img.get("src") or ""
            add(src)
            if len(urls) >= 50:
                break

    # JS embedded JSON: imageUrl patterns (Musinsa, 11st, Naver, etc.)
    if html_text and len(urls) < 50:
        for m in re.finditer(r'"imageUrl"\s*:\s*"([^"]+)"', html_text):
            add(_resolve_js_image(m.group(1)))
            if len(urls) >= 50:
                break
        for m in re.finditer(r'"(?:thumbnailImageUrl|ogImageUrl|mainImageUrl)"\s*:\s*"([^"]+)"', html_text):
            add(_resolve_js_image(m.group(1)))
            if len(urls) >= 50:
                break

    return urls[:50]


def _resolve_js_image(path):
    """Musinsa/11st use relative paths in JSON → resolve to full URL"""
    if path.startswith("http"):
        return path
    if path.startswith("//"):
        return "https:" + path
    if path.startswith("/"):
        return "https://image.musinsa.com" + path
    return path


def _extract_metadata(soup, html_text):
    """Extract review count, rating, delivery info, etc. from page"""
    meta = {}

    if html_text:
        # Musinsa __MSS__ JSON
        m = re.search(r'window\.__MSS__\.product\.state\s*=\s*(\{.*?\});\s*\n', html_text, re.DOTALL)
        if m:
            try:
                data = json.loads(m.group(1))
                gr = data.get("goodsReview") or {}
                if gr.get("totalCount"):
                    meta["reviewCount"] = gr["totalCount"]
                if gr.get("satisfactionScore"):
                    meta["satisfactionScore"] = float(gr["satisfactionScore"])
                if data.get("deliveryType"):
                    meta["deliveryType"] = data["deliveryType"]
            except Exception:
                pass

    # OG/product meta from HTML
    if soup:
        for prop in ["product:price:amount", "product:original_price:amount",
                     "product:sale_price:amount", "product:shipping:amount"]:
            val = _meta(soup, prop)
            if val:
                meta[prop.replace(":", "_")] = val

    return meta if meta else None


def parse_args():
    p = argparse.ArgumentParser(description="Crawl URL and create page_snapshot")
    p.add_argument("--order-id", type=int, required=True)
    p.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    p.add_argument("--timeout", type=int, default=15)
    p.add_argument("--force", action="store_true", help="Overwrite existing snapshot")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=5432)
    p.add_argument("--dbname", default="precustomer")
    p.add_argument("--user", default="postgres")
    p.add_argument("--password", default="postgres")
    return p.parse_args()


if __name__ == "__main__":
    main()
