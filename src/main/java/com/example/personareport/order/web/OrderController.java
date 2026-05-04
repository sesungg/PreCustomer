package com.example.personareport.order.web;

import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.domain.TargetType;
import com.example.personareport.order.dto.OrderRequest;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.service.ImageStorageService.ImageUploadException;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/new")
    public String newOrder(Model model) {
        model.addAttribute("orderRequest", emptyOrderRequest());
        addFormOptions(model);
        return "orders/new";
    }

    @PostMapping
    public String createOrder(
            @Valid @ModelAttribute OrderRequest orderRequest,
            BindingResult bindingResult,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            addFormOptions(model);
            return "orders/new";
        }

        try {
            Long orderId = orderService.createOrder(orderRequest, images).getId();
            return "redirect:/orders/" + orderId + "/complete";
        } catch (ImageUploadException e) {
            model.addAttribute("uploadError", e.getMessage());
            addFormOptions(model);
            return "orders/new";
        }
    }

    @GetMapping("/{id}/complete")
    public String complete(@PathVariable Long id, Model model) {
        model.addAttribute("order", orderService.getOrder(id));
        return "orders/complete";
    }

    private void addFormOptions(Model model) {
        model.addAttribute("targetTypes", TargetType.values());
        model.addAttribute("reportPerspectives", ReportPerspective.values());
    }

    private OrderRequest emptyOrderRequest() {
        return new OrderRequest(null, null, null, null, null, null, null, null, null, null, false);
    }
}
