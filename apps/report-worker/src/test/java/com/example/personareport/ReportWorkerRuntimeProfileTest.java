package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import com.example.personareport.report.pipeline.DeepSeekFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"h2", "worker"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.persona.import-enabled=false",
                "app.persona.sample-import-enabled=false",
                "app.pipeline.worker-poll-ms=600000"
        })
class ReportWorkerRuntimeProfileTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @MockBean
    private DeepSeekFeignClient deepSeekClient;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void workerProfileEnablesReportWorkerWithoutWebServer() {
        assertThat(environment.getProperty("app.web.public-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.web.admin-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.pipeline.worker-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(context.containsBeanDefinition("reportJobWorker")).isTrue();
        assertThat(context.containsBeanDefinition("landingController")).isFalse();
        assertThat(context.containsBeanDefinition("orderController")).isFalse();
        assertThat(context.containsBeanDefinition("authController")).isFalse();
        assertThat(context.containsBeanDefinition("adminOrderController")).isFalse();
        assertThat(context.containsBeanDefinition("reportViewController")).isFalse();
        assertThat(context.containsBeanDefinition("reportPdfController")).isFalse();
        assertThat(context.containsBeanDefinition("adminMvController")).isFalse();
        assertThat(context.containsBeanDefinition("shoppingSearchController")).isFalse();
    }
}
