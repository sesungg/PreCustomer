package com.example.personareport.modules.shopping.service;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import com.example.personareport.modules.shopping.client.NaverShoppingProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingSearchService {

    private static final List<String> SORTS = List.of("sim", "asc", "dsc", "date");
    private static final List<String> AD_TOKENS = List.of(
            "무료배송", "특가", "정품", "추천", "인기", "행사", "당일발송", "최저가", "한정", "공식", "국내배송"
    );

    private final NaverShoppingFeignClient naverFeign;
    private final NaverShoppingProperties props;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> executeSearch(String query, String baseProductName, Integer basePrice,
                                              String cat1, String cat2, String cat3, String cat4, boolean useVariants) {
        return executeSearch(null, query, baseProductName, basePrice, cat1, cat2, cat3, cat4, useVariants);
    }

    @Transactional
    public Map<String, Object> executeReportSearch(Long reportId, String query, String baseProductName, Integer basePrice,
                                                   String cat1, String cat2, String cat3, String cat4, boolean useVariants) {
        return executeSearch(reportId, query, baseProductName, basePrice, cat1, cat2, cat3, cat4, useVariants);
    }

    private Map<String, Object> executeSearch(Long reportId, String query, String baseProductName, Integer basePrice,
                                              String cat1, String cat2, String cat3, String cat4, boolean useVariants) {
        // 1. searchGroup 생성
        Long groupId = jdbc.queryForObject(
                "INSERT INTO shopping_search_group (report_id, search_purpose, original_query, base_product_name, base_price, base_category1, base_category2, base_category3, base_category4, collected_at) VALUES (?,'REPORT_PREPARE',?,?,?,?,?,?,?,NOW()) RETURNING id",
                Long.class, reportId, query, baseProductName, basePrice, cat1, cat2, cat3, cat4);

        // 2. query variants 생성
        List<String> queries = useVariants ? generateQueryVariants(query, cat1, cat2, cat3) : List.of(query);
        for (int i = 0; i < queries.size(); i++) {
            jdbc.update("INSERT INTO shopping_query_variant (search_group_id, query, query_type, priority) VALUES (?,?,?,?)",
                    groupId, queries.get(i), i == 0 ? "ORIGINAL" : "FALLBACK", i + 1);
        }

        // 3. sort별 API 호출 + product 저장 (Feign Client)
        int totalFetched = 0;
        boolean stopNaverCalls = false;
        for (String q : queries) {
            for (String sort : SORTS) {
                NaverSearchResult result = callNaverApi(q, sort, props.displaySize(), 1);
                totalFetched += result.totalCount;

                Long snapshotId = jdbc.queryForObject(
                        "INSERT INTO shopping_search_snapshot (search_group_id, query, sort, display, start, total_count, raw_response_json, success, error_message, collected_at) VALUES (?,?,?,?,?,?,?::jsonb,?,?,NOW()) RETURNING id",
                        Long.class, groupId, q, sort, props.displaySize(), 1, result.totalCount,
                        result.rawJson, result.success, result.errorMessage);

                if (!result.success && isNaverAuthFailure(result.errorMessage)) {
                    log.warn("네이버 쇼핑 인증 실패로 남은 쇼핑 API 호출을 중단합니다. searchGroupId={}, reason={}",
                            groupId, compactError(result.errorMessage));
                    stopNaverCalls = true;
                    break;
                }

                if (result.success) {
                    for (int rank = 0; rank < result.items.size(); rank++) {
                        NaverProductItem item = result.items.get(rank);
                        String titleClean = cleanTitle(item.title());
                        String normalizedTitle = normalizeTitle(titleClean);
                        int qualityScore = computeQualityScore(item);
                        String confidence = qualityScore >= 80 ? "HIGH" : qualityScore >= 50 ? "MEDIUM" : "LOW";

                        jdbc.update("""
                                INSERT INTO shopping_product_snapshot (search_group_id, search_snapshot_id, source,
                                external_product_id, deduplication_key, title_raw, title_clean, normalized_title,
                                product_url, image_url, lprice, hprice, mall_name, product_type,
                                brand_raw, brand_normalized, maker_raw, maker_normalized,
                                category1, category2, category3, category4, original_rank, sort_type,
                                data_quality_score, data_confidence, collected_at)
                                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
                                """, groupId, snapshotId, "NAVER_SHOPPING",
                                na(item.productId()), na(item.productId()) + "|" + sort,
                                item.title(), titleClean, normalizedTitle,
                                item.link(), item.image(), item.lprice(), item.hprice(),
                                item.mallName(), item.productType(),
                                item.brand(), norm(item.brand()), item.maker(), norm(item.maker()),
                                item.category1(), item.category2(), item.category3(), item.category4(),
                                rank + 1, sort, qualityScore, confidence);
                    }
                }
            }
            if (stopNaverCalls) {
                break;
            }
        }

        // 4. 중복 제거
        int deduped = deduplicate(groupId);

        // 5. 후보 점수 계산
        int candidates = computeCandidates(groupId, baseProductName, basePrice, cat1, cat2, cat3, cat4);

        // 6. 시장 분석
        computeMarketAnalysis(groupId, basePrice);

        jdbc.update("UPDATE shopping_search_group SET total_fetched_count=?, deduplicated_count=?, candidate_count=? WHERE id=?",
                totalFetched, deduped, candidates, groupId);

        return Map.of(
                "searchGroupId", groupId, "originalQuery", query,
                "totalFetchedCount", totalFetched, "deduplicatedCount", deduped,
                "candidateCount", candidates, "representativeSort", "sim"
        );
    }

    public List<Map<String, Object>> getCandidates(Long groupId) {
        return jdbc.queryForList("""
                SELECT cp.id as candidate_id, cp.product_snapshot_id, cp.candidate_score,
                       cp.title_similarity_score, cp.category_match_score, cp.price_range_score,
                       cp.brand_presence_score, cp.rank_score, cp.data_quality_score,
                       cp.data_confidence, cp.candidate_reason,
                       ps.title_clean as title, ps.mall_name, ps.brand_raw as brand,
                       ps.maker_raw as maker, ps.lprice, ps.hprice, ps.image_url, ps.product_url,
                       ps.category1, ps.category2, ps.category3, ps.category4,
                       array_agg(cpr.role_type) as roles
                FROM shopping_candidate_product cp
                JOIN shopping_product_snapshot ps ON ps.id = cp.product_snapshot_id
                LEFT JOIN shopping_candidate_product_role cpr ON cpr.candidate_product_id = cp.id
                WHERE cp.search_group_id = ?
                GROUP BY cp.id, ps.id
                ORDER BY cp.candidate_score DESC
                """, groupId);
    }

    public Map<String, Object> getPriceAnalysis(Long groupId) {
        return jdbc.queryForMap("""
                SELECT price_analysis_json, low_price_benchmark_json, high_price_benchmark_json,
                       representative_sort FROM shopping_market_analysis_snapshot
                WHERE search_group_id = ? ORDER BY id DESC LIMIT 1
                """, groupId);
    }

    public Map<String, Object> getDistribution(Long groupId) {
        return jdbc.queryForMap("""
                SELECT brand_distribution_json, maker_distribution_json, mall_distribution_json,
                       category_distribution_json, dominant_category_json, category_mixed,
                       dominant_category_confidence FROM shopping_market_analysis_snapshot
                WHERE search_group_id = ? ORDER BY id DESC LIMIT 1
                """, groupId);
    }

    public Map<String, Object> getReportContext(Long groupId) {
        return jdbc.queryForMap("""
                SELECT report_context_json FROM shopping_market_analysis_snapshot
                WHERE search_group_id = ? ORDER BY id DESC LIMIT 1
                """, groupId);
    }

    public void selectCandidates(Long groupId, List<Long> ids) {
        jdbc.update("UPDATE shopping_candidate_product SET is_selected_by_user = FALSE WHERE search_group_id = ?", groupId);
        for (Long id : ids) {
            jdbc.update("UPDATE shopping_candidate_product SET is_selected_by_user = TRUE WHERE id = ?", id);
        }
    }

    public List<Map<String, Object>> previewQueries(String baseName, String cat1, String cat2, String cat3) {
        List<String> variants = generateQueryVariants(baseName, cat1, cat2, cat3);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            String type = i == 0 ? "ORIGINAL" : i == 1 ? "BRAND_REMOVED" : i == 2 ? "CATEGORY_CORE" : "FUNCTIONAL_KEYWORD";
            result.add(Map.of("query", variants.get(i), "queryType", type, "priority", i + 1));
        }
        return result;
    }

    // --- Feign API helper ---

    private NaverSearchResult callNaverApi(String query, String sort, int display, int start) {
        try {
            if (isBlank(props.clientId()) || isBlank(props.clientSecret())) {
                return new NaverSearchResult(false, 0, List.of(), null,
                        "NAVER_AUTH_MISSING: NAVER_SHOPPING_CLIENT_ID/NAVER_SHOPPING_CLIENT_SECRET is required");
            }
            String raw = naverFeign.search(props.clientId(), props.clientSecret(), query, sort, display, start);
            var root = objectMapper.readTree(raw);
            int total = root.path("total").asInt();
            List<NaverProductItem> items = new ArrayList<>();
            for (var item : root.path("items")) {
                items.add(new NaverProductItem(
                        item.path("title").asText(""), item.path("link").asText(""),
                        item.path("image").asText(""), parseInt(item, "lprice"), parseInt(item, "hprice"),
                        item.path("mallName").asText(""), item.path("productId").asText(""),
                        item.path("productType").asText(""), item.path("maker").asText(""),
                        item.path("brand").asText(""), item.path("category1").asText(""),
                        item.path("category2").asText(""), item.path("category3").asText(""),
                        item.path("category4").asText("")));
            }
            return new NaverSearchResult(true, total, items, raw, null);
        } catch (Exception e) {
            String message = e.getMessage();
            if (isNaverAuthFailure(message)) {
                log.warn("Naver API authentication failed: {}", compactError(message));
                return new NaverSearchResult(false, 0, List.of(), null, "NAVER_AUTH_FAILED: " + compactError(message));
            }
            log.warn("Naver API error: {}", compactError(message));
            return new NaverSearchResult(false, 0, List.of(), null, compactError(message));
        }
    }

    private boolean isNaverAuthFailure(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("naver_auth_missing")
                || lower.contains("naver_auth_failed")
                || lower.contains("401 unauthorized")
                || lower.contains("authentication failed")
                || lower.contains("not exist client id")
                || lower.contains("\"errorcode\":\"024\"")
                || lower.contains("invalid client");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String compactError(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 500 ? compact.substring(0, 500) : compact;
    }

    private int parseInt(com.fasterxml.jackson.databind.JsonNode node, String field) {
        String val = node.path(field).asText("");
        try { return val.isEmpty() ? 0 : Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    private record NaverSearchResult(boolean success, int totalCount, List<NaverProductItem> items, String rawJson, String errorMessage) {}

    private record NaverProductItem(String title, String link, String image, int lprice, int hprice,
                                      String mallName, String productId, String productType,
                                      String maker, String brand, String category1, String category2,
                                      String category3, String category4) {}

    // --- private helpers ---

    private List<String> generateQueryVariants(String name, String cat1, String cat2, String cat3) {
        if (name == null || name.isBlank()) return List.of(cat3 != null ? cat3 : "검색");
        List<String> variants = new ArrayList<>();
        variants.add(name); // original
        // brand-removed: drop first token (often brand name)
        String[] parts = name.split("\\s+");
        if (parts.length > 1) {
            String noBrand = Arrays.stream(parts, 1, parts.length).collect(Collectors.joining(" "));
            if (!noBrand.equals(name)) variants.add(noBrand);
        }
        // category core
        if (cat3 != null && !cat3.isBlank() && !name.contains(cat3)) {
            variants.add(cat3);
        }
        // fallback: last 2 tokens
        if (parts.length > 2) {
            String shortQ = Arrays.stream(parts, Math.max(1, parts.length - 2), parts.length).collect(Collectors.joining(" "));
            if (!variants.contains(shortQ)) variants.add(shortQ);
        }
        return variants.stream().distinct().limit(5).toList();
    }

    private String cleanTitle(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        for (String token : AD_TOKENS) s = s.replace(token, " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private String normalizeTitle(String clean) {
        return clean.toLowerCase().replaceAll("[^a-z0-9가-힣\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private String norm(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim().replaceAll("\\s+", " ");
    }

    private String na(String s) { return s == null || s.isBlank() ? null : s; }

    private int computeQualityScore(NaverProductItem item) {
        int score = 0;
        if (item.title() != null && !item.title().isBlank()) score += 20;
        if (item.lprice() > 0) score += 20;
        if (item.category1() != null && item.category2() != null) score += 20;
        if ((item.brand() != null && !item.brand().isBlank()) || (item.maker() != null && !item.maker().isBlank())) score += 15;
        if (item.image() != null && !item.image().isBlank()) score += 10;
        if (item.link() != null && !item.link().isBlank()) score += 10;
        if (item.productId() != null && !item.productId().isBlank()) score += 5;
        return Math.min(100, score);
    }

    private int deduplicate(Long groupId) {
        // 1차: productId 중복 제거 (data_quality_score 높은 것 유지)
        jdbc.update("DELETE FROM shopping_product_snapshot WHERE id IN (SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY external_product_id ORDER BY data_quality_score DESC, sort_type='sim' DESC, original_rank) as rn FROM shopping_product_snapshot WHERE search_group_id=? AND external_product_id IS NOT NULL) t WHERE t.rn > 1)", groupId);

        // 2차: normalizedTitle + mallName + lprice 중복
        jdbc.update("DELETE FROM shopping_product_snapshot WHERE id IN (SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY normalized_title, mall_name, lprice ORDER BY data_quality_score DESC, sort_type='sim' DESC) as rn FROM shopping_product_snapshot WHERE search_group_id=?) t WHERE t.rn > 1)", groupId);

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM shopping_product_snapshot WHERE search_group_id = ?", Long.class, groupId);
        return count != null ? count.intValue() : 0;
    }

    private int computeCandidates(Long groupId, String baseName, Integer basePrice, String cat1, String cat2, String cat3, String cat4) {
        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT * FROM shopping_product_snapshot WHERE search_group_id = ?", groupId);

        int count = 0;
        for (Map<String, Object> p : products) {
            Long psId = (Long) p.get("id");
            int lprice = toInt(p.get("lprice"));
            String title = (String) p.get("title_clean");
            String brand = (String) p.get("brand_raw");
            String maker = (String) p.get("maker_raw");
            String pcat1 = (String) p.get("category1");
            String pcat2 = (String) p.get("category2");
            String pcat3 = (String) p.get("category3");
            int rank = toInt(p.get("original_rank"));
            int quality = toInt(p.get("data_quality_score"));

            double titleScore = computeTitleSimilarity(baseName, title);
            double catScore = computeCategoryMatch(cat1, cat2, cat3, pcat1, pcat2, pcat3);
            double priceScore = computePriceRange(basePrice, lprice);
            double brandScore = (brand != null ? 80 : 0) + (maker != null ? 70 : 0) > 0 ? ((brand != null ? 80 : 0) + (maker != null ? 70 : 0)) / 2.0 : 30;
            double rankScore = rank > 0 && rank <= 20 ? (100 - rank * 4) : 50;
            double candidateScore = titleScore * 0.35 + catScore * 0.25 + priceScore * 0.15 + brandScore * 0.10 + rankScore * 0.05 + quality * 0.10;

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("priceDiffRate", basePrice != null && lprice > 0 ? Math.round((lprice - basePrice) * 100.0 / basePrice) : 0);
            detail.put("brandPresent", brand != null && !brand.isBlank());
            detail.put("makerPresent", maker != null && !maker.isBlank());
            detail.put("dataQualityScore", quality);

            String reason = buildReason(baseName, title, cat1, cat2, cat3, pcat1, pcat2, pcat3, lprice, basePrice, brand);

            String confidence = quality >= 80 ? "HIGH" : quality >= 50 ? "MEDIUM" : "LOW";
            Long cpId = jdbc.queryForObject(
                    "INSERT INTO shopping_candidate_product (search_group_id, product_snapshot_id, candidate_score, title_similarity_score, category_match_score, price_range_score, brand_presence_score, rank_score, data_quality_score, data_confidence, scoring_version, scoring_detail_json, candidate_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) RETURNING id",
                    Long.class, groupId, psId, candidateScore, titleScore, catScore, priceScore, brandScore, rankScore, quality, confidence, props.scoringVersion(),
                    toJson(detail), reason);

            // roles
            List<String> roles = new ArrayList<>();
            if (count < 10) roles.add("TOP");
            if (lprice > 0 && basePrice != null && lprice <= basePrice * 0.8) roles.add("LOW_PRICE");
            else if (lprice > 0 && basePrice != null && lprice >= basePrice * 1.2) roles.add("HIGH_PRICE");
            else roles.add("MID_PRICE");
            if (brand != null || maker != null) roles.add("BRAND_AVAILABLE");
            if (catScore >= 80) roles.add("CATEGORY_MATCHED");
            if ("sim".equals(p.get("sort_type"))) roles.add("REPRESENTATIVE_SIM");
            for (String role : roles) {
                jdbc.update("INSERT INTO shopping_candidate_product_role (candidate_product_id, role_type) VALUES (?,?)", cpId, role);
            }
            count++;
            if (count >= 30) break;
        }
        return count;
    }

    private void computeMarketAnalysis(Long groupId, Integer basePrice) {
        List<Map<String, Object>> simProducts = jdbc.queryForList(
                "SELECT lprice, brand_raw, maker_raw, mall_name, category1, category2, category3, category4 FROM shopping_product_snapshot WHERE search_group_id=? AND sort_type='sim' AND lprice > 0 ORDER BY lprice", groupId);

        if (simProducts.isEmpty()) {
            jdbc.update("INSERT INTO shopping_market_analysis_snapshot (search_group_id, price_analysis_json) VALUES (?,'{}'::jsonb)", groupId);
            return;
        }

        List<Integer> prices = simProducts.stream().map(p -> toInt(p.get("lprice"))).filter(v -> v > 0).sorted().toList();
        int n = prices.size();
        int minP = prices.get(0), maxP = prices.get(n - 1);
        int medP = prices.get(n / 2), q1 = prices.get(n / 4), q3 = prices.get(3 * n / 4), p90 = prices.get((int) (n * 0.9));
        double avgP = prices.stream().mapToInt(Integer::intValue).average().orElse(0);

        Map<String, Object> priceAnalysis = new LinkedHashMap<>();
        priceAnalysis.put("minPrice", minP); priceAnalysis.put("maxPrice", maxP);
        priceAnalysis.put("averagePrice", Math.round(avgP)); priceAnalysis.put("medianPrice", medP);
        priceAnalysis.put("q1Price", q1); priceAnalysis.put("q3Price", q3); priceAnalysis.put("p90Price", p90);
        priceAnalysis.put("itemCount", n); priceAnalysis.put("validPriceItemCount", n);
        if (basePrice != null) {
            int rank = 0; for (int p : prices) if (p < basePrice) rank++;
            priceAnalysis.put("basePrice", basePrice);
            priceAnalysis.put("pricePositionPercentile", Math.round(rank * 100.0 / n));
            priceAnalysis.put("priceLevel", basePrice <= q1 ? "LOW" : basePrice <= q3 ? "MID" : basePrice <= p90 ? "HIGH" : "PREMIUM");
        }

        // brand distribution
        Map<String, Long> brands = simProducts.stream()
                .filter(p -> p.get("brand_raw") != null)
                .collect(Collectors.groupingBy(p -> (String) p.get("brand_raw"), Collectors.counting()));
        // mall distribution
        Map<String, Long> malls = simProducts.stream()
                .filter(p -> p.get("mall_name") != null)
                .collect(Collectors.groupingBy(p -> (String) p.get("mall_name"), Collectors.counting()));
        // category distribution
        Map<String, Long> cats = simProducts.stream()
                .collect(Collectors.groupingBy(p -> str(p.get("category1")) + ">" + str(p.get("category2")) + ">" + str(p.get("category3")), Collectors.counting()));

        // dominant category
        String domCat = cats.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        double domConf = cats.isEmpty() ? 0 : (double) cats.getOrDefault(domCat, 0L) / n * 100;

        // report context
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("representativeSort", "sim");
        context.put("medianPrice", medP);
        context.put("q1Price", q1); context.put("q3Price", q3); context.put("p90Price", p90);
        if (basePrice != null) {
            context.put("basePrice", basePrice);
            context.put("pricePositionPercentile", priceAnalysis.get("pricePositionPercentile"));
            context.put("priceLevel", priceAnalysis.get("priceLevel"));
        }
        context.put("dominantCategory", domCat);
        context.put("dominantCategoryConfidence", Math.round(domConf * 10) / 10.0);
        context.put("categoryMixed", domConf < 40);
        context.put("topBrands", brands.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).map(Map.Entry::getKey).toList());
        context.put("topMalls", malls.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).map(Map.Entry::getKey).toList());
        context.put("dataLimitations", List.of(
                "네이버 쇼핑 검색 결과 기준 데이터입니다.",
                "배송비, 쿠폰, 옵션별 가격, 리뷰 수, 평점은 포함되지 않았습니다.",
                "실제 구매 가격은 판매 조건에 따라 달라질 수 있습니다.",
                "가격 분석은 정확도순 검색 결과를 기준으로 계산했습니다."
        ));

        jdbc.update("""
                INSERT INTO shopping_market_analysis_snapshot (search_group_id, price_analysis_json, brand_distribution_json, maker_distribution_json, mall_distribution_json, category_distribution_json, dominant_category_json, category_mixed, dominant_category_confidence, report_context_json)
                VALUES (?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb)
                """, groupId, toJson(priceAnalysis), toJson(brands), toJson(Map.of()), toJson(malls), toJson(cats),
                toJson(Map.of("category", domCat)), domConf < 40, Math.round(domConf * 10) / 10.0, toJson(context));
    }

    private double computeTitleSimilarity(String base, String title) {
        if (base == null || title == null) return 50;
        String[] baseTokens = normalizeTitle(base).split("\\s+");
        String[] titleTokens = normalizeTitle(title).split("\\s+");
        int match = 0;
        for (String bt : baseTokens) {
            if (bt.length() < 2) continue;
            for (String tt : titleTokens) {
                if (tt.contains(bt) || bt.contains(tt)) { match++; break; }
            }
        }
        return Math.min(100, (double) match / Math.max(1, baseTokens.length) * 100);
    }

    private double computeCategoryMatch(String c1, String c2, String c3, String pc1, String pc2, String pc3) {
        int score = 0;
        if (c1 != null && c1.equals(pc1)) score += 25;
        else if (c1 == null || pc1 == null) score += 12;
        if (c2 != null && c2.equals(pc2)) score += 25;
        if (c3 != null && c3.equals(pc3)) score += 50;
        return Math.min(100, score);
    }

    private double computePriceRange(Integer base, int lprice) {
        if (base == null || lprice <= 0) return 50;
        double diff = Math.abs(lprice - base) / (double) base;
        if (diff <= 0.2) return 90;
        if (diff <= 0.5) return 60;
        return 30;
    }

    private String buildReason(String base, String title, String c1, String c2, String c3, String pc1, String pc2, String pc3, int lprice, Integer basePrice, String brand) {
        StringBuilder sb = new StringBuilder();
        if (base != null && title != null) {
            sb.append("기준 상품과 유사한 상품입니다.");
        }
        if (c3 != null && c3.equals(pc3)) {
            sb.append(" 카테고리가 일치합니다(category3=").append(c3).append(").");
        }
        if (basePrice != null && lprice > 0) {
            double diff = (lprice - basePrice) * 100.0 / basePrice;
            sb.append(String.format(" 가격은 %,d원으로 기준 %,d원 대비 약 %.0f%% %s.", lprice, basePrice, Math.abs(diff), diff > 0 ? "높습니다" : "낮습니다"));
        }
        if (brand != null && !brand.isBlank()) {
            sb.append(" 브랜드 정보가 확인됩니다.");
        }
        return sb.toString().trim();
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (JsonProcessingException e) { return "{}"; }
    }

    private String str(Object o) { return o != null ? o.toString() : ""; }
    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }
}
