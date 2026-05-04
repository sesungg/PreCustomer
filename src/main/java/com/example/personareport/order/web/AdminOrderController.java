package com.example.personareport.order.web;

import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.domain.PipelineProgress;
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

@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;
    private final ImageStorageService imageStorageService;
    private final ReportPipelineService reportPipelineService;
    private final PipelineProgressService progressService;
    private final ReportDataService reportDataService;

    @GetMapping
    public String list(@RequestParam(required = false) OrderStatus status, Model model) {
        model.addAttribute("orders", orderService.findOrders(status));
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        return "admin/orders/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        var order = orderService.getOrder(id);
        model.addAttribute("order", order);
        List<Path> imagePaths = imageStorageService.resolvePaths(order.getImagePaths());
        model.addAttribute("imageFilenames", imagePaths.stream()
                .map(Path::getFileName).map(Path::toString).toList());
        progressService.findById(id).ifPresent(p -> model.addAttribute("progress", p));
        model.addAttribute("reportExists", reportDataService.countReportByOrderId(id) > 0);
        return "admin/orders/detail";
    }

    @PostMapping("/{id}/paid")
    public String markPaid(@PathVariable Long id) {
        orderService.markPaid(id);
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/generate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generate(@PathVariable Long id) {
        orderService.markPaid(id);
        var order = orderService.getOrder(id);
        List<Path> imagePaths = imageStorageService.resolvePaths(order.getImagePaths());
        reportPipelineService.runDetailPagePipeline(id, imagePaths);
        return ResponseEntity.ok(Map.of("status", "started", "orderId", id));
    }

    @GetMapping("/{id}/progress")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable Long id) {
        Optional<PipelineProgress> opt = progressService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "PENDING", "currentStep", 0, "totalSteps", 0,
                    "currentStepName", "대기 중", "errorMessage", "",
                    "completed", false
            ));
        }
        PipelineProgress p = opt.get();
        return ResponseEntity.ok(Map.of(
                "status", p.getStatus(), "currentStep", p.getCurrentStep(),
                "totalSteps", p.getTotalSteps(), "currentStepName", p.getCurrentStepName(),
                "errorMessage", p.getErrorMessage() != null ? p.getErrorMessage() : "",
                "completed", "COMPLETED".equals(p.getStatus()) || "FAILED".equals(p.getStatus())
        ));
    }

    @PostMapping("/{id}/failed")
    public String markFailed(@PathVariable Long id) {
        orderService.markFailed(id);
        return "redirect:/admin/orders/" + id;
    }
}
