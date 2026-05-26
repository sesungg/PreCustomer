package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"h2", "admin-web"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.persona.import-enabled=false",
                "app.persona.sample-import-enabled=false"
        })
class ReportAdminWebRuntimeProfileTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void adminWebProfileExposesOnlyAdminControllers() {
        assertThat(environment.getProperty("app.web.public-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.web.admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.pipeline.worker-enabled", Boolean.class)).isFalse();
        assertThat(context.containsBeanDefinition("landingController")).isFalse();
        assertThat(context.containsBeanDefinition("orderController")).isFalse();
        assertThat(context.containsBeanDefinition("authController")).isFalse();
        assertThat(context.containsBeanDefinition("adminAuthController")).isTrue();
        assertThat(context.containsBeanDefinition("adminOrderController")).isTrue();
        assertThat(context.containsBeanDefinition("reportViewController")).isTrue();
        assertThat(context.containsBeanDefinition("reportPdfController")).isTrue();
        assertThat(context.containsBeanDefinition("adminMvController")).isTrue();
        assertThat(context.containsBeanDefinition("shoppingSearchController")).isTrue();
        assertThat(context.containsBeanDefinition("reportJobWorker")).isFalse();
    }
}
