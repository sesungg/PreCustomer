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
    private DeepSeekFeignClient deepSeekClient;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void adminWebProfileExposesOnlyAdminControllers() {
        assertThat(environment.getProperty("app.web.public-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.web.admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.pipeline.worker-enabled", Boolean.class)).isFalse();
        assertThat(context.getBeanNamesForType(LandingController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OrderController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AdminOrderController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ReportViewController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ReportPdfController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(AdminMvController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ShoppingSearchController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ReportJobWorker.class)).isEmpty();
    }
}
