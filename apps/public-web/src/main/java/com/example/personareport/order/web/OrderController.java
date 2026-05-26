package com.example.personareport.order.web;

import com.example.personareport.analytics.service.AnalyticsEventLogService;
import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.dto.OrderRequest;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.delivery.service.ReportDeliveryService;
import com.example.personareport.report.service.ImageStorageService.ImageUploadException;
import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.dto.SignupRequest;
import com.example.personareport.user.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
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
@ConditionalOnProperty(prefix = "app.web", name = "public-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final UserAccountService userAccountService;
    private final ReportDeliveryService reportDeliveryService;
    private final AnalyticsEventLogService analyticsEventLogService;

    @GetMapping("/new")
    public String newOrder(Model model, Authentication authentication) {
        model.addAttribute("orderRequest", emptyOrderRequest(currentUser(authentication)));
        addFormOptions(model);
        return "orders/new";
    }

    @PostMapping
    public String createOrder(
            @Valid @ModelAttribute OrderRequest orderRequest,
            BindingResult bindingResult,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            Model model,
            RedirectAttributes redirectAttributes,
            Authentication authentication,
            HttpServletRequest request
    ) {
        boolean hasImages = hasImages(images);
        if (bindingResult.hasErrors() || !hasImages) {
            if (!hasImages) {
                model.addAttribute("uploadError", "상품 상세페이지 전체 캡처 이미지를 업로드해 주세요.");
            }
            addFormOptions(model);
            return "orders/new";
        }

        try {
            UserAccount account = currentUser(authentication);
            var order = orderService.createOrder(orderRequest, images, account);
            Long orderId = order.getId();
            if (account != null) {
                reportDeliveryService.saveAccountDelivery(orderId, account);
            }
            analyticsEventLogService.recordServerEvent(
                    "report_order_submitted",
                    "order",
                    orderId,
                    account != null ? account.getId() : null,
                    orderMetadata(order, images, account != null),
                    request
            );
            return "redirect:/orders/" + orderId + "/complete";
        } catch (ImageUploadException e) {
            model.addAttribute("uploadError", e.getMessage());
            addFormOptions(model);
            return "orders/new";
        }
    }

    @GetMapping("/{id}/complete")
    public String complete(@PathVariable Long id,
                           Model model,
                           Authentication authentication,
                           @RequestParam(value = "joined", required = false) String joined,
                           @RequestParam(value = "deliverySaved", required = false) String deliverySaved) {
        var order = orderService.getOrder(id);
        UserAccount account = currentUser(authentication);
        if (account != null) {
            orderService.attachCustomerAccount(id, account);
            reportDeliveryService.saveAccountDelivery(id, account);
        }
        model.addAttribute("order", order);
        model.addAttribute("loggedInUser", account);
        model.addAttribute("joined", joined != null);
        model.addAttribute("deliverySaved", deliverySaved != null);
        model.addAttribute("signupRequest", new SignupRequest("", order.getCustomerEmail(), "", "", false, false, false, id));
        model.addAttribute("deliveryEmail", order.getCustomerEmail());
        return "orders/complete";
    }

    @PostMapping("/{id}/delivery-email")
    public String saveDeliveryEmail(@PathVariable Long id,
                                    @RequestParam("email") String email,
                                    RedirectAttributes redirectAttributes,
                                    HttpServletRequest request) {
        if (!isEmail(email)) {
            redirectAttributes.addFlashAttribute("deliveryError", "올바른 이메일을 입력해 주세요.");
            return "redirect:/orders/" + id + "/complete";
        }
        reportDeliveryService.saveEmailOnly(id, email);
        analyticsEventLogService.recordServerEvent(
                "report_delivery_email_saved",
                "delivery",
                id,
                null,
                Map.of("emailOnly", true),
                request
        );
        return "redirect:/orders/" + id + "/complete?deliverySaved=1";
    }

    private void addFormOptions(Model model) {
        model.addAttribute("reportPerspectives", ReportPerspective.values());
    }

    private OrderRequest emptyOrderRequest(UserAccount account) {
        return new OrderRequest(account != null ? account.getEmail() : null,
                null, null, null, null, null, null, false);
    }

    private Map<String, Object> orderMetadata(com.example.personareport.order.domain.ReactionReportOrder order,
                                              List<MultipartFile> images,
                                              boolean customerLinked) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetType", order.getTargetType().name());
        metadata.put("reportPerspective", order.getReportPerspective().name());
        metadata.put("hasShippingPolicyText", order.getShippingPolicyText() != null && !order.getShippingPolicyText().isBlank());
        metadata.put("hasImages", images != null && images.stream().anyMatch(file -> file != null && !file.isEmpty()));
        metadata.put("customerLinked", customerLinked);
        return metadata;
    }

    private UserAccount currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        Object principal = authentication.getPrincipal();
        if ("anonymousUser".equals(principal)) return null;
        return userAccountService.findByEmail(authentication.getName());
    }

    private boolean isEmail(String value) {
        return value != null && value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }

    private boolean hasImages(List<MultipartFile> images) {
        return images != null && images.stream().anyMatch(file -> file != null && !file.isEmpty());
    }
}
