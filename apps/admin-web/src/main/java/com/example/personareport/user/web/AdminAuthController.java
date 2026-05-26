package com.example.personareport.user.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("admin-web")
public class AdminAuthController {

    @GetMapping("/admin/login")
    public String login() {
        return "auth/login";
    }
}
