package com.example.personareport.order.web;

import com.example.personareport.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.web", name = "public-enabled", havingValue = "true", matchIfMissing = true)
public class LandingController {

    private final UserAccountService userAccountService;

    @GetMapping("/")
    public String index(Model model, Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            model.addAttribute("loggedInUser", userAccountService.findByEmail(authentication.getName()));
        }
        return "index";
    }
}
