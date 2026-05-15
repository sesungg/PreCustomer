package com.example.personareport.modules.shopping.web;

import com.example.personareport.modules.shopping.dto.PreviewQueriesRequest;
import com.example.personareport.modules.shopping.dto.SearchRequest;
import com.example.personareport.modules.shopping.dto.SelectCandidatesRequest;
import com.example.personareport.modules.shopping.service.ShoppingSearchService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 네이버 쇼핑 검색 REST API. 검색 실행, 후보 조회/선택, 가격/분포 분석, 리포트 context. */
@RestController
@ConditionalOnProperty(prefix = "app.web", name = "admin-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/shopping/naver")
@RequiredArgsConstructor
public class ShoppingSearchController {

    private final ShoppingSearchService shoppingService;

    /** 검색어에서 query variant 미리보기 생성. */
    @PostMapping("/search/preview-queries")
    public ResponseEntity<Map<String, Object>> previewQueries(@RequestBody PreviewQueriesRequest req) {
        var variants = shoppingService.previewQueries(req.baseProductName(), req.baseCategory1(), req.baseCategory2(), req.baseCategory3());
        return ResponseEntity.ok(Map.of("originalQuery", req.baseProductName(), "queryVariants", variants));
    }

    /** 네이버 쇼핑 검색 실행. query variants 생성 → sort별 API 호출 → 중복제거 → 후보계산 → 분석. */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@Valid @RequestBody SearchRequest req) {
        var result = shoppingService.executeSearch(req.query(), req.baseProductName(), req.basePrice(),
                req.baseCategory1(), req.baseCategory2(), req.baseCategory3(), req.baseCategory4(), req.useQueryVariants());
        return ResponseEntity.ok(result);
    }

    /** 후보 상품 목록 조회. */
    @GetMapping("/search-groups/{groupId}/candidates")
    public ResponseEntity<Map<String, Object>> getCandidates(@PathVariable Long groupId) {
        return ResponseEntity.ok(Map.of("candidates", shoppingService.getCandidates(groupId)));
    }

    /** 사용자가 선택한 후보 상품 저장. */
    @PostMapping("/search-groups/{groupId}/candidates/select")
    public ResponseEntity<Map<String, Object>> selectCandidates(@PathVariable Long groupId, @RequestBody SelectCandidatesRequest req) {
        shoppingService.selectCandidates(groupId, req.selectedCandidateIds());
        return ResponseEntity.ok(Map.of("searchGroupId", groupId, "selectedCandidateCount", req.selectedCandidateIds().size(), "selectedCandidateIds", req.selectedCandidateIds()));
    }

    /** 가격대 분석 결과 조회. */
    @GetMapping("/search-groups/{groupId}/price-analysis")
    public ResponseEntity<Map<String, Object>> getPriceAnalysis(@PathVariable Long groupId) {
        return ResponseEntity.ok(shoppingService.getPriceAnalysis(groupId));
    }

    /** 브랜드/몰/카테고리 분포 분석 결과 조회. */
    @GetMapping("/search-groups/{groupId}/distribution")
    public ResponseEntity<Map<String, Object>> getDistribution(@PathVariable Long groupId) {
        return ResponseEntity.ok(shoppingService.getDistribution(groupId));
    }

    /** 페르소나 리포트에 전달할 요약 context 조회. */
    @GetMapping("/search-groups/{groupId}/report-context")
    public ResponseEntity<Map<String, Object>> getReportContext(@PathVariable Long groupId) {
        return ResponseEntity.ok(shoppingService.getReportContext(groupId));
    }
}
