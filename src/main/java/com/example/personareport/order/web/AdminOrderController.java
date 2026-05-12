package com.example.personareport.order.web;

import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.job.ReportJobService;
import com.example.personareport.report.job.ReportJobStatus;
import com.example.personareport.report.service.ImageStorageService;
import com.example.personareport.report.service.PipelineProgressService;
import com.example.personareport.report.service.ReportDataService;
import com.example.personareport.report.service.ReportPipelineService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** 관리자 주문 관리 화면. 주문 목록, 상세, 입금확인, 리포트생성, 진행상황 폴링. */
@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;
    private final ImageStorageService imageStorageService;
    private final ReportPipelineService reportPipelineService;
    private final PipelineProgressService progressService;
    private final ReportJobService reportJobService;
    private final ReportDataService reportDataService;

    /** 주문 목록 페이지. status 파라미터로 필터링 가능. */
    @GetMapping
    public String list(@RequestParam(required = false) OrderStatus status, Model model) {
        model.addAttribute("orders", orderService.findOrders(status));
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        return "admin/orders/list";
    }

    /** 주문 상세 페이지. 진행상황, 업로드 이미지, 리포트 존재 여부 표시. */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        var order = orderService.getOrder(id);
        model.addAttribute("order", order);
        List<Path> imagePaths = imageStorageService.resolvePaths(order.getImagePaths());
        model.addAttribute("imageFilenames", imagePaths.stream()
                .map(Path::getFileName).map(Path::toString).toList());
        progressService.findById(id).ifPresent(p -> model.addAttribute("progress", p));
        reportJobService.findLatestForOrder(id).ifPresent(j -> model.addAttribute("reportJob", j));
        model.addAttribute("reportExists", reportDataService.countReportByOrderId(id) > 0);
        return "admin/orders/detail";
    }

    @PostMapping("/{id}/paid")
    public String markPaid(@PathVariable Long id) {
        orderService.markPaid(id);
        return "redirect:/admin/orders/" + id;
    }

    /** 비동기 리포트 생성 시작. 즉시 응답 후 백그라운드 실행.
     * FAILED/STOPPED 상태는 재개(resume), 그 외는 신규 시작으로 응답 메시지를 구분한다.
     * markPaid는 REQUESTED 상태일 때만 호출하여 이미 PAID 이상인 주문의 상태를 되돌리지 않는다.
     */
    @PostMapping("/{id}/generate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generate(@PathVariable Long id) {
        if (progressService.findById(id).filter(PipelineProgress::isActive).isPresent()
                || reportJobService.hasActiveJob(id)) {
            return ResponseEntity.ok(Map.of("status", "already_running", "orderId", id));
        }
        // FAILED/STOPPED 이력이 있으면 재개, 없으면 신규 시작
        boolean resume = progressService.findById(id)
                .map(p -> PipelineProgress.STATUS_FAILED.equals(p.getStatus())
                        || PipelineProgress.STATUS_STOPPED.equals(p.getStatus()))
                .orElse(false);
        // REQUESTED 상태인 경우에만 PAID로 전이 (이미 PAID 이상이면 상태 유지)
        var order = orderService.getOrder(id);
        if (OrderStatus.REQUESTED.equals(order.getStatus())) {
            orderService.markPaid(id);
        }
        List<Path> imagePaths = imageStorageService.resolvePaths(order.getImagePaths());
        var job = reportJobService.enqueueDetailPageReport(id, false, !imagePaths.isEmpty());
        return ResponseEntity.ok(Map.of(
                "status", resume ? "resume_queued" : "queued",
                "orderId", id,
                "jobId", job.getId()
        ));
    }

    /** 실행 중인 리포트 생성을 graceful stop으로 요청한다. */
    @PostMapping("/{id}/stop")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stop(@PathVariable Long id) {
        boolean accepted = reportJobService.requestCancelForOrder(id);
        accepted = reportPipelineService.requestStop(id) || accepted;
        return ResponseEntity.ok(Map.of(
                "status", accepted ? "stop_requested" : "not_running",
                "orderId", id
        ));
    }

    /** 기존 리포트 산출물을 삭제하고 처음부터 다시 생성한다.
     * markPaid는 REQUESTED 상태일 때만 호출하여 이미 PAID 이상인 주문의 상태를 되돌리지 않는다.
     */
    @PostMapping("/{id}/regenerate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> regenerate(@PathVariable Long id) {
        if (progressService.findById(id).filter(PipelineProgress::isActive).isPresent()
                || reportJobService.hasActiveJob(id)) {
            return ResponseEntity.ok(Map.of("status", "already_running", "orderId", id));
        }
        // REQUESTED 상태인 경우에만 PAID로 전이 (이미 PAID 이상이면 상태 유지)
        var order = orderService.getOrder(id);
        if (OrderStatus.REQUESTED.equals(order.getStatus())) {
            orderService.markPaid(id);
        }
        List<Path> imagePaths = imageStorageService.resolvePaths(order.getImagePaths());
        var job = reportJobService.enqueueDetailPageReport(id, true, !imagePaths.isEmpty());
        return ResponseEntity.ok(Map.of("status", "regenerate_queued", "orderId", id, "jobId", job.getId()));
    }

    /** 리포트 생성 진행상황 폴링 API. JS에서 2초 간격으로 호출. */
    @GetMapping("/{id}/progress")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable Long id) {
        Optional<PipelineProgress> opt = progressService.findById(id);
        var latestJob = reportJobService.findLatestForOrder(id);
        if (latestJob.isPresent()
                && ReportJobStatus.ACTIVE.contains(latestJob.get().getStatus())
                && (opt.isEmpty() || opt.get().isTerminal())) {
            var job = latestJob.get();
            String currentStepName = job.getAttemptCount() > 0
                    ? "재시도 대기 중"
                    : "리포트 작업 대기 중";
            return ResponseEntity.ok(Map.of(
                    "status", job.getStatus(),
                    "currentStep", opt.map(PipelineProgress::getCurrentStep).orElse(0),
                    "totalSteps", opt.map(PipelineProgress::getTotalSteps).orElse(0),
                    "currentStepName", currentStepName,
                    "errorMessage", job.getErrorMessage() != null ? job.getErrorMessage() : "",
                    "completed", false
            ));
        }
        if (opt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "PENDING", "currentStep", 0, "totalSteps", 0,
                    "currentStepName", "대기 중", "errorMessage", "",
                    "completed", false
            ));
        }
        PipelineProgress p = opt.get();
        long elapsedSec = 0;
        if (p.getStepStartedAt() != null) {
            elapsedSec = java.time.Duration.between(p.getStepStartedAt(), java.time.LocalDateTime.now()).getSeconds();
        }
        boolean completed = PipelineProgress.STATUS_COMPLETED.equals(p.getStatus())
                || PipelineProgress.STATUS_FAILED.equals(p.getStatus())
                || PipelineProgress.STATUS_STOPPED.equals(p.getStatus());
        return ResponseEntity.ok(Map.of(
                "status", p.getStatus(), "currentStep", p.getCurrentStep(),
                "totalSteps", p.getTotalSteps(), "currentStepName", p.getCurrentStepName(),
                "errorMessage", p.getErrorMessage() != null ? p.getErrorMessage() : "",
                "completed", completed,
                "elapsedSeconds", elapsedSec
        ));
    }

    @PostMapping("/{id}/failed")
    public String markFailed(@PathVariable Long id) {
        orderService.markFailed(id);
        return "redirect:/admin/orders/" + id;
    }
}
