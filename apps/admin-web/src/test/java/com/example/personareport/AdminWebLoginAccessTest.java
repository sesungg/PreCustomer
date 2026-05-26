package com.example.personareport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

@ActiveProfiles({"h2", "admin-web"})
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
        "spring.session.store-type=none",
        "management.health.redis.enabled=false",
        "app.persona.import-enabled=false",
        "app.persona.sample-import-enabled=false"
})
@AutoConfigureMockMvc
class AdminWebLoginAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void adminPage_redirectsAnonymousToAdminLogin() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/admin/login")));
    }

    @Test
    void adminLoginPage_isAvailableInAdminWeb() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 계정으로 계속하기")));
    }

    @Test
    void adminLogin_redirectsToAdminOrders() throws Exception {
        mockMvc.perform(formLogin("/admin/login")
                        .user("username", "admin")
                        .password("admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/orders"));
    }
}
