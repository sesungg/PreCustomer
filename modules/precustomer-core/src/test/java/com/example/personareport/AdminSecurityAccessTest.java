package com.example.personareport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import com.example.personareport.report.pipeline.DeepSeekFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles({"h2", "web"})
@SpringBootTest(properties = {
        "app.persona.import-enabled=false",
        "app.persona.sample-import-enabled=false"
})
@AutoConfigureMockMvc
class AdminSecurityAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeepSeekFeignClient deepSeekClient;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void adminPage_redirectsToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPage_allowsAdminRole() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk());
    }

    @Test
    void publicOrderForm_allowsAnonymous() throws Exception {
        mockMvc.perform(get("/orders/new"))
                .andExpect(status().isOk());
    }

    @Test
    void loginPage_allowsAnonymous() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownRoute_redirectsAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/internal/unknown"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
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
                .andExpect(header().string("Location", containsString("/login")));
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
