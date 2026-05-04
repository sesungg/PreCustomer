package com.example.personareport.modules.shopping.web;

import com.example.personareport.modules.shopping.dto.PreviewQueriesRequest;
import com.example.personareport.modules.shopping.dto.SearchRequest;
import com.example.personareport.modules.shopping.dto.SelectCandidatesRequest;
import com.example.personareport.modules.shopping.service.ShoppingSearchService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shopping/naver")
@RequiredArgsConstructor
public class ShoppingSearchController {

    private final ShoppingSearchService shoppingService;

    @PostMapping("/search/preview-queries")
    public ResponseEntity<Map<String, Object>> previewQueries(@RequestBody PreviewQueriesRequest req) {
        var variants = shoppingService.previewQueries(req.baseProductName(), req.baseCategory1(), req.baseCategory2(), req.baseCategory3());
        return ResponseEntity.ok(Map.of("originalQuery", req.baseProductName(), "queryVariants", variants));
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@Valid @RequestBody SearchRequest req) {
        var result = shoppingService.executeSearch(req.query(), req.baseProductName(), req.basePrice(),
                req.baseCategory1(), req.baseCategory2(), req.baseCategory3(), req.baseCategory4(), req.useQueryVariants());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/search-groups/{groupId}/candidates")
    public ResponseEntity<Map<String, Object>> getCandidates(@PathVariable Long groupId) {
        return ResponseEntity.ok(Map.of("candidates", shoppingService.getCandidates(groupId)));
    }

    @PostMapping("/search-groups/{groupId}/candidates/select")
    public ResponseEntity<Map<String, Object>> selectCandidates(@PathVariable Long groupId, @RequestBody SelectCandidatesRequest req) {
        shoppingService.selectCandidates(groupId, req.selectedCandidateIds());
        return ResponseEntity.ok(Map.of("searchGroupId", groupId, "selectedCandidateCount", req.selectedCandidateIds().size(), "selectedCandidateIds", req.selectedCandidateIds()));
    }

    @GetMapping("/search-groups/{groupId}/price-analysis")
    public ResponseEntity<Map<String, Object>> getPriceAnalysis(@PathVariable Long groupId) {
        return ResponseEntity.ok(shoppingService.getPriceAnalysis(groupId));
    }

    @GetMapping("/search-groups/{groupId}/distribution")
    public ResponseEntity<Map<String, Object>> getDistribution(@PathVariable Long groupId) {
        return ResponseEntity.ok(shoppingService.getDistribution(groupId));
    }

    @GetMapping("/search-groups/{groupId}/report-context")
    public ResponseEntity<Map<String, Object>> getReportContext(@PathVariable Long groupId) {
        return ResponseEntity.ok(shoppingService.getReportContext(groupId));
    }
}
