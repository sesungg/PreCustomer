package com.example.personareport.modules.shopping.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NaverShoppingClient {

    private final NaverShoppingProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NaverShoppingClient(NaverShoppingProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.timeoutMillis()))
                .build();
    }

    public NaverSearchResult search(String query, String sort, int display, int start) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = props.baseUrl() + "?query=" + encoded
                    + "&sort=" + sort + "&display=" + display + "&start=" + start;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Naver-Client-Id", props.clientId())
                    .header("X-Naver-Client-Secret", safeSecret(props.clientSecret()))
                    .timeout(Duration.ofMillis(props.timeoutMillis()))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Naver API error: status={} body={}", response.statusCode(), limit(response.body(), 300));
                return NaverSearchResult.failed("HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            int total = root.path("total").asInt();
            List<NaverProductItem> items = new ArrayList<>();

            for (JsonNode item : root.path("items")) {
                items.add(new NaverProductItem(
                        item.path("title").asText(""),
                        item.path("link").asText(""),
                        item.path("image").asText(""),
                        parseInt(item, "lprice"),
                        parseInt(item, "hprice"),
                        item.path("mallName").asText(""),
                        item.path("productId").asText(""),
                        item.path("productType").asText(""),
                        item.path("maker").asText(""),
                        item.path("brand").asText(""),
                        item.path("category1").asText(""),
                        item.path("category2").asText(""),
                        item.path("category3").asText(""),
                        item.path("category4").asText("")
                ));
            }

            return NaverSearchResult.success(total, items, response.body());
        } catch (Exception e) {
            log.error("Naver API call failed: {}", e.getMessage());
            return NaverSearchResult.failed(e.getMessage());
        }
    }

    private int parseInt(JsonNode node, String field) {
        String val = node.path(field).asText("");
        try { return val.isEmpty() ? 0 : Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    private String safeSecret(String secret) {
        if (secret == null) return "";
        return secret;
    }

    private String limit(String s, int len) { return s != null && s.length() > len ? s.substring(0, len) : s; }

    public record NaverProductItem(String title, String link, String image, int lprice, int hprice,
                                     String mallName, String productId, String productType,
                                     String maker, String brand, String category1, String category2,
                                     String category3, String category4) {}

    public record NaverSearchResult(boolean success, int totalCount, List<NaverProductItem> items,
                                      String rawJson, String errorMessage) {
        public static NaverSearchResult success(int total, List<NaverProductItem> items, String rawJson) {
            return new NaverSearchResult(true, total, items, rawJson, null);
        }
        public static NaverSearchResult failed(String msg) {
            return new NaverSearchResult(false, 0, List.of(), null, msg);
        }
    }
}
