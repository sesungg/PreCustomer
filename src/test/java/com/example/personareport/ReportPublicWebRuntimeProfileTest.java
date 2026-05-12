package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import com.example.personareport.modules.shopping.web.ShoppingSearchController;
import com.example.personareport.order.web.AdminOrderController;
import com.example.personareport.order.web.LandingController;
import com.example.personareport.order.web.OrderController;
import com.example.personareport.report.job.ReportJobWorker;
import com.example.personareport.report.pipeline.DeepSeekFeignClient;
import com.example.personareport.report.web.AdminMvController;
import com.example.personareport.report.web.ReportPdfController;
import com.example.personareport.report.web.ReportViewController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"h2", "public-web"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.persona.import-enabled=false",
                "app.persona.sample-import-enabled=false"
        })
class ReportPublicWebRuntimeProfileTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @MockBean
    private DeepSeekFeignClient deepSeekClient;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void publicWebProfileExposesOnlyPublicControllers() {
        assertThat(environment.getProperty("app.web.public-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.web.admin-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.pipeline.worker-enabled", Boolean.class)).isFalse();
        assertThat(context.getBeanNamesForType(LandingController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(OrderController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(AdminOrderController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReportViewController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReportPdfController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AdminMvController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ShoppingSearchController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReportJobWorker.class)).isEmpty();
    }
}
