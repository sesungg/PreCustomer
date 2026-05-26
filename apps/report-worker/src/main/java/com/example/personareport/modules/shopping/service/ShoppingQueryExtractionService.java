package com.example.personareport.modules.shopping.service;

import com.example.personareport.report.pipeline.DeepSeekService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingQueryExtractionService {

    private final DeepSeekService deepSeekService;

    @Value("${app.deepseek.model:deepseek-v4-flash}")
    private String model = "deepseek-v4-flash";

    public String buildCompetitorQuery(String productName, String detailDescription, String fallbackCategory) {
        String heuristic = ShoppingSearchService.buildCompetitorQuery(productName, detailDescription, fallbackCategory);
        if (!ShoppingSearchService.needsAiCompetitorQuery(productName, heuristic)) {
            return heuristic;
        }

        try {
            Map<String, Object> response = deepSeekService.callDeepSeek(
                    buildSystemPrompt(),
                    buildUserPrompt(productName, detailDescription, fallbackCategory, heuristic),
                    model,
                    0.1,
                    800,
                    "disabled");
            String aiQuery = stringValue(response.get("query"));
            String cleanedAiQuery = ShoppingSearchService.cleanCompetitorQueryCandidate(aiQuery);
            if (!ShoppingSearchService.needsAiCompetitorQuery(productName, cleanedAiQuery)) {
                log.info("DeepSeek 경쟁상품 검색어 추출 적용 productName={}, heuristic={}, query={}",
                        productName, heuristic, cleanedAiQuery);
                return cleanedAiQuery;
            }
            String refined = ShoppingSearchService.buildCompetitorQuery(aiQuery, detailDescription, heuristic);
            if (!ShoppingSearchService.needsAiCompetitorQuery(productName, refined)) {
                log.info("DeepSeek 경쟁상품 검색어 추출 적용 productName={}, heuristic={}, query={}",
                        productName, heuristic, refined);
                return refined;
            }
            log.warn("DeepSeek 경쟁상품 검색어가 자사/모델 토큰을 충분히 제거하지 못해 휴리스틱을 사용합니다. productName={}, aiQuery={}",
                    productName, aiQuery);
        } catch (RuntimeException e) {
            log.warn("DeepSeek 경쟁상품 검색어 추출 실패로 휴리스틱을 사용합니다. productName={}, reason={}",
                    productName, e.getMessage());
        }
        return heuristic;
    }

    private String buildSystemPrompt() {
        return """
                너는 네이버 쇼핑 경쟁상품 검색어를 추출하는 분류기다.
                출력은 JSON 객체 하나만 반환한다. 마크다운 금지.
                목표는 기준 상품 자체나 같은 브랜드 제품군이 아니라, 실제 경쟁사가 나올 범용 카테고리 검색어를 만드는 것이다.
                규칙:
                - 자사 브랜드명, 판매자명, 고유 모델명, 제품 고유 코드, 휴대폰 브랜드/모델명은 제거한다.
                - 핵심 카테고리, 기능, 구성품, 형태만 남긴다.
                - 너무 넓은 단어 하나만 쓰지 말고 2~6개 키워드로 만든다.
                - 가격, 배송비, 할인율, 리뷰 수는 검색어에 넣지 않는다.
                - 예: "EDITOR PD 25W 아이폰 갤럭시 고속 c타입 충전기 + 케이블 세트" -> "고속 c타입 충전기 케이블 세트"
                출력 템플릿: {"query":"UNKNOWN","reason":"UNKNOWN"}
                """;
    }

    private String buildUserPrompt(String productName, String detailDescription, String fallbackCategory, String heuristic) {
        return """
                {
                  "productName": "%s",
                  "detailDescription": "%s",
                  "fallbackCategory": "%s",
                  "heuristicQuery": "%s"
                }
                """.formatted(escape(productName), escape(detailDescription), escape(fallbackCategory), escape(heuristic));
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
