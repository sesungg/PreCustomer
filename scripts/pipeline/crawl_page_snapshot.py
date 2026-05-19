#!/usr/bin/env python3
"""URL 크롤링 → page_snapshot 생성. 기존 snapshot이 있으면 스킵."""
import argparse
import json
import re
import sys
import traceback
import time
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
        fetched = fetch_page_html(page_url, args)
    except Exception as e:
        print(f"[FAIL] HTTP error: {e}", file=sys.stderr)
        if not args.force:
            save_fallback_snapshot(conn, args.order_id, order, "HTTP_ERROR", str(e))
            return
        fetched = {"html": "", "final_url": page_url, "status_code": None, "method": "FAILED"}

    html_text = fetched.get("html") or ""
    soup = BeautifulSoup(html_text, "html.parser") if html_text else None

    page_title = _text(soup.title) if soup else None
    product_title = _meta(soup, "og:title") or page_title
    price_text = _meta(soup, "product:price:amount") or _meta(soup, "og:price:amount")
    source_site = _extract_domain(fetched.get("final_url") or page_url)
    visible_text = _body_text(soup)[:MAX_VISIBLE_TEXT] if soup else None
    extracted_text_summary = visible_text[:2000] if visible_text else None
    image_urls = _extract_images(soup, html_text)
    raw_meta = _extract_metadata(soup, html_text) or {}
    raw_meta["crawlMethod"] = fetched.get("method")
    raw_meta["httpStatus"] = fetched.get("status_code")
    raw_meta["finalUrl"] = fetched.get("final_url") or page_url

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
                psycopg2.extras.Json(raw_meta),
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


def fetch_page_html(page_url, args):
    """Fetch with requests first, then use a real browser for SmartStore-style 403/429 blocks."""
    headers = {
        "User-Agent": args.user_agent,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer": "https://shopping.naver.com/",
    }
    try:
        resp = requests.get(page_url, headers=headers, timeout=args.timeout)
        resp.raise_for_status()
        return {
            "html": resp.text,
            "final_url": resp.url,
            "status_code": resp.status_code,
            "method": "REQUESTS",
        }
    except requests.HTTPError as e:
        status = e.response.status_code if e.response is not None else None
        if args.browser_fallback and status in (403, 429):
            print(f"[BROWSER_FALLBACK] requests status={status}, trying Playwright")
            return fetch_page_html_with_browser(page_url, args, status)
        raise


def fetch_page_html_with_browser(page_url, args, previous_status=None):
    try:
        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
        from playwright.sync_api import sync_playwright
    except Exception as e:
        raise RuntimeError(f"Playwright를 사용할 수 없습니다: {e}") from e

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        try:
            context = browser.new_context(
                user_agent=args.user_agent,
                locale="ko-KR",
                timezone_id="Asia/Seoul",
                viewport={"width": 1365, "height": 1200},
                extra_http_headers={
                    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer": "https://shopping.naver.com/",
                },
            )
            page = context.new_page()
            response = page.goto(page_url, wait_until="domcontentloaded", timeout=args.browser_timeout * 1000)
            try:
                page.wait_for_load_state("networkidle", timeout=min(args.browser_timeout * 1000, 12000))
            except PlaywrightTimeoutError:
                pass
            time.sleep(min(args.browser_settle_seconds, 5))
            status = response.status if response is not None else None
            html = page.content()
            final_url = page.url
        finally:
            browser.close()

    if status is not None and status >= 400:
        raise RuntimeError(
            f"Playwright fallback도 HTTP {status}로 실패했습니다"
            + (f" (requests={previous_status})" if previous_status else "")
        )
    if "Too Many Requests" in html or "429" in html[:2000]:
        raise RuntimeError(
            "Playwright fallback 응답도 429 차단 페이지로 보입니다"
            + (f" (requests={previous_status})" if previous_status else "")
        )
    return {
        "html": html,
        "final_url": final_url,
        "status_code": status,
        "method": "PLAYWRIGHT",
    }


def save_fallback_snapshot(conn, order_id, order, reason, error_message, snapshot_status="FALLBACK"):
    """크롤링이 막혀도 이후 이미지/주문 정보 기반 분석을 진행할 수 있도록 최소 snapshot을 저장한다."""
    page_url = (order.get("page_url") or "").strip() or f"manual://report-order/{order_id}"
    project_name = (order.get("project_name") or "").strip()
    price_text = order.get("price_text")
    uploaded_images = _order_image_paths(order)
    important_images = [
        {"path": image_path, "role": "DETAIL_PAGE_SCREENSHOT", "source": "report_order.image_paths"}
        for image_path in uploaded_images
    ]
    visible_text_parts = [
        f"상품명: {project_name}" if project_name else None,
        f"한 줄 설명: {order.get('one_line_description')}" if order.get("one_line_description") else None,
        f"상세 설명: {order.get('detail_description')}" if order.get("detail_description") else None,
        f"가격: {price_text}" if price_text else None,
        f"타겟 고객: {order.get('target_customer')}" if order.get("target_customer") else None,
        f"핵심 질문: {order.get('main_question')}" if order.get("main_question") else None,
        f"업로드 캡처 이미지 수: {len(uploaded_images)}" if uploaded_images else None,
        f"원본 URL: {page_url}" if page_url else None,
    ]
    visible_text = "\n".join(part for part in visible_text_parts if part)
    raw_meta = {
        "fallbackSource": "report_order",
        "fallbackReason": reason,
        "crawlError": error_message,
        "analysisMode": "URL_FALLBACK",
        "uploadedImageCount": len(uploaded_images),
        "uploadedImages": uploaded_images,
    }
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO page_snapshot (
                report_order_id, page_url, source_site, snapshot_status,
                page_title, product_title, price_text,
                visible_text, extracted_text_summary, image_urls, important_images,
                raw_meta_json, captured_at
            ) VALUES (%s,%s,%s,%s, %s,%s,%s, %s,%s,%s, %s, %s::jsonb, %s)
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
                important_images = EXCLUDED.important_images,
                raw_meta_json = EXCLUDED.raw_meta_json,
                captured_at = EXCLUDED.captured_at
            """,
            (
                order_id, page_url, _extract_domain(page_url) or "manual", snapshot_status,
                project_name, project_name, price_text,
                visible_text, visible_text[:2000] if visible_text else None,
                psycopg2.extras.Json([]), psycopg2.extras.Json(important_images),
                psycopg2.extras.Json(raw_meta), datetime.now(),
            ),
        )
    print(f"[{snapshot_status}] page_snapshot saved for order_id={order_id}, reason={reason}, images={len(uploaded_images)}")


def fetch_order(conn, order_id):
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("SELECT * FROM report_order WHERE id=%s", (order_id,))
        return cur.fetchone()


def fetch_existing(conn, order_id):
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("SELECT * FROM page_snapshot WHERE report_order_id=%s ORDER BY captured_at DESC LIMIT 1", (order_id,))
        return cur.fetchone()


def _order_image_paths(order):
    image_paths = order.get("image_paths") if order else None
    if not image_paths:
        return []
    return [p.strip() for p in str(image_paths).splitlines() if p.strip()]


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

    text_source = ""
    if soup:
        try:
            text_source = _body_text(soup) or ""
        except Exception:
            text_source = ""
    combined = "\n".join(part for part in [html_text or "", text_source] if part)

    review_count = _first_number(combined, [
        r'"(?:reviewCount|totalReviewCount|reviewAmount|reviewCnt)"\s*:\s*"?([0-9,]+)"?',
        r'(?:리뷰|후기)\s*([0-9,]+)\s*(?:개|건)?',
        r'([0-9,]+)\s*(?:개|건)의?\s*(?:리뷰|후기)',
    ])
    if review_count and "reviewCount" not in meta:
        meta["reviewCount"] = review_count

    rating_score = _first_number(combined, [
        r'"(?:ratingScore|averageReviewScore|reviewScore|starScore|satisfactionScore)"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)"?',
        r'(?:평점|별점)\s*([0-9]+(?:\.[0-9]+)?)',
    ])
    if rating_score and "ratingScore" not in meta and "satisfactionScore" not in meta:
        meta["ratingScore"] = rating_score

    shipping_amount = _first_number(combined, [
        r'"(?:deliveryFee|shippingFee|baseFee|deliveryPrice)"\s*:\s*"?([0-9,]+)"?',
        r'(?:배송비|운송비)\s*([0-9,]+)\s*원',
    ])
    if shipping_amount and "product_shipping_amount" not in meta:
        meta["shippingFee"] = shipping_amount

    free_threshold = _first_number(combined, [
        r'"(?:conditionalFreeShippingMinAmount|freeShippingThreshold|freeDeliveryBaseAmount)"\s*:\s*"?([0-9,]+)"?',
        r'([0-9,]+)\s*원\s*이상\s*(?:구매\s*)?무료배송',
    ])
    if free_threshold:
        meta["conditionalFreeShippingMinAmount"] = free_threshold

    origin = _extract_origin(combined)
    if origin:
        meta["origin"] = origin
    weight = _extract_weight(combined)
    if weight:
        meta["weight"] = weight
    storage = _extract_storage(combined)
    if storage:
        meta["storageMethod"] = storage
    if "무료배송" in combined and "deliveryType" not in meta:
        meta["deliveryType"] = "FREE_SHIPPING"

    return meta if meta else None


def _first_number(text, patterns):
    for pattern in patterns:
        m = re.search(pattern, text or "", re.IGNORECASE)
        if m:
            return m.group(1).replace(",", "")
    return None


def _extract_origin(text):
    source = text or ""
    if re.search(r"브라질\s*산|BRAZIL", source, re.IGNORECASE):
        return "브라질산"
    if re.search(r"국내\s*산|국산", source):
        return "국내산"
    for origin in ["미국산", "호주산", "태국산", "중국산"]:
        if origin in source:
            return origin
    return None


def _extract_weight(text):
    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*(kg|KG|Kg|킬로|g|G|그램)", text or "")
    if not m:
        return None
    unit = "kg" if m.group(2).lower() == "kg" or m.group(2) == "킬로" else "g"
    return f"{m.group(1)}{unit}"


def _extract_storage(text):
    source = (text or "").lower()
    if "냉동" in source or "frozen" in source:
        return "냉동"
    if "냉장" in source or "refrigerated" in source:
        return "냉장"
    if "실온" in source:
        return "실온"
    return None


def parse_args():
    p = argparse.ArgumentParser(description="Crawl URL and create page_snapshot")
    p.add_argument("--order-id", type=int, required=True)
    p.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    p.add_argument("--timeout", type=int, default=15)
    p.add_argument("--browser-fallback", dest="browser_fallback", action="store_true", default=True,
                   help="HTTP 403/429 시 Playwright 브라우저 렌더링을 한 번 시도")
    p.add_argument("--no-browser-fallback", dest="browser_fallback", action="store_false",
                   help="HTTP 403/429 시 브라우저 fallback을 사용하지 않음")
    p.add_argument("--browser-timeout", type=int, default=25)
    p.add_argument("--browser-settle-seconds", type=float, default=2.0)
    p.add_argument("--force", action="store_true", help="Overwrite existing snapshot")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=5432)
    p.add_argument("--dbname", default="precustomer")
    p.add_argument("--user", default="postgres")
    p.add_argument("--password", default="postgres")
    return p.parse_args()


if __name__ == "__main__":
    main()
