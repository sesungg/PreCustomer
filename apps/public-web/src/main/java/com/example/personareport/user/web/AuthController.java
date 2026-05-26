package com.example.personareport.user.web;

import com.example.personareport.analytics.service.AnalyticsEventLogService;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.delivery.service.ReportDeliveryService;
import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.dto.SignupRequest;
import com.example.personareport.user.service.UserAccountService;
import com.example.personareport.user.service.UserAccountService.DuplicateEmailException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.web", name = "public-enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final UserAccountService userAccountService;
    private final ReportDeliveryService reportDeliveryService;
    private final OrderService orderService;
    private final AnalyticsEventLogService analyticsEventLogService;

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/signup")
    public String signup(@RequestParam(value = "orderId", required = false) Long orderId, Model model) {
        if (!model.containsAttribute("signupRequest")) {
            model.addAttribute("signupRequest", new SignupRequest("", "", "", "", false, false, false, orderId));
        }
        model.addAttribute("returnOrderId", orderId);
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String signup(
            @Valid @ModelAttribute SignupRequest signupRequest,
            BindingResult bindingResult,
            HttpServletRequest request,
            Model model
    ) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("returnOrderId", signupRequest.returnOrderId());
            return "auth/signup";
        }
        try {
            UserAccount account = userAccountService.register(signupRequest);
            if (signupRequest.returnOrderId() != null) {
                orderService.attachCustomerAccount(signupRequest.returnOrderId(), account);
                reportDeliveryService.saveAccountDelivery(signupRequest.returnOrderId(), account);
            }
            loginAfterSignup(account, request);
            analyticsEventLogService.recordServerEvent(
                    "signup_completed",
                    "auth",
                    signupRequest.returnOrderId(),
                    account.getId(),
                    Map.of("linkedOrder", signupRequest.returnOrderId() != null),
                    request
            );
            if (signupRequest.returnOrderId() != null) {
                return "redirect:/orders/" + signupRequest.returnOrderId() + "/complete?joined=1";
            }
            return "redirect:/orders/new";
        } catch (DuplicateEmailException e) {
            bindingResult.rejectValue("email", "duplicate", e.getMessage());
            model.addAttribute("returnOrderId", signupRequest.returnOrderId());
            return "auth/signup";
        }
    }

    private void loginAfterSignup(UserAccount account, HttpServletRequest request) {
        var authentication = new UsernamePasswordAuthenticationToken(
                account.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );
    }
}
