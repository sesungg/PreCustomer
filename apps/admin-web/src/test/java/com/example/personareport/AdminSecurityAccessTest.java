package com.example.personareport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.personareport.contracts.auth.PassportCodec;
import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles({"h2", "admin-web"})
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
        "spring.session.store-type=none",
        "management.health.redis.enabled=false",
        "app.persona.import-enabled=false",
        "app.persona.sample-import-enabled=false"
})
@AutoConfigureMockMvc
class AdminSecurityAccessTest {

    private static final String PASSPORT_SECRET = "local-passport-secret-change-me-32bytes!";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void adminPage_redirectsToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/admin/login")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPage_allowsAdminRole() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk());
    }

    @Test
    void adminPage_allowsGatewayPassport() throws Exception {
        String passport = PassportCodec.issue(
                PASSPORT_SECRET,
                "gateway-admin@example.com",
                List.of("ROLE_ADMIN"),
                Duration.ofMinutes(5)
        );

        mockMvc.perform(get("/admin/orders")
                        .header("X-PreCustomer-Passport", passport))
                .andExpect(status().isOk());
    }

    @Test
    void adminLoginPage_allowsAnonymous() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealth_allowsAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownRoute_redirectsAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/internal/unknown"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/admin/login")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownRoute_isDeniedForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/internal/unknown"))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadedFiles_requireAdminAuthentication() throws Exception {
        mockMvc.perform(get("/uploads/sample.png"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/admin/login")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPost_requiresCsrf() throws Exception {
        mockMvc.perform(post("/admin/orders/1/stop"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/orders/1/stop").with(csrf()))
                .andExpect(status().isOk());
    }
}
