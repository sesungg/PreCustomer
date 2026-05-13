package com.example.personareport.report.web;

import com.example.personareport.report.pipeline.PersonaNormalizedScoreMvService;
import com.example.personareport.report.pipeline.PipelineQueryService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Materialized View 관리 관리자 API.
 *
 * <p>ML 파이프라인 적재 완료 후 MV를 갱신하거나, 현재 MV 상태를 확인하는 엔드포인트를 제공한다.
 *
 * <ul>
 *   <li>{@code GET  /admin/mv/status}  - MV 존재 여부 및 통계 조회</li>
 *   <li>{@code POST /admin/mv/refresh} - MV CONCURRENTLY 갱신</li>
 *   <li>{@code POST /admin/mv/recreate} - MV 전체 재생성 (DROP → CREATE)</li>
 * </ul>
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "app.web", name = "admin-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/admin/mv")
@RequiredArgsConstructor
public class AdminMvController {

    private final PersonaNormalizedScoreMvService mvService;
    private final PipelineQueryService queryService;

    /**
     * MV 상태 조회.
     *
     * @return MV 존재 여부, 행 수, model_version별 통계
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        boolean available = queryService.isMvAvailable();
        long rowCount = mvService.countRows();
        var stats = mvService.getStats();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mvAvailable", available);
        body.put("rowCount", rowCount);
        body.put("stats", stats);
        return ResponseEntity.ok(body);
    }

    /**
     * MV CONCURRENTLY 갱신.
     *
     * <p>갱신 중에도 SELECT 블로킹이 없으므로 운영 중 호출 가능하다.
     * ML 파이프라인 완료 후 자동 호출하거나 관리자가 수동으로 호출한다.
     *
     * @return 갱신 후 행 수
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        log.info("[AdminMvController] MV 갱신 요청");
        try {
            long rowCount = mvService.refresh();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("rowCount", rowCount);
            body.put("message", "MV 갱신 완료. rowCount=" + rowCount);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("[AdminMvController] MV 갱신 실패", e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "MV 갱신 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * MV 전체 재생성 (DROP → CREATE).
     *
     * <p>스키마 변경이나 인덱스 재구성이 필요할 때 사용한다.
     * 재생성 중에는 SELECT가 블로킹될 수 있으므로 유지보수 시간대에 실행을 권장한다.
     *
     * @return 재생성 후 행 수
     */
    @PostMapping("/recreate")
    public ResponseEntity<Map<String, Object>> recreate() {
        log.info("[AdminMvController] MV 재생성 요청");
        try {
            long rowCount = mvService.recreate();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("rowCount", rowCount);
            body.put("message", "MV 재생성 완료. rowCount=" + rowCount);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("[AdminMvController] MV 재생성 실패", e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "MV 재생성 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
