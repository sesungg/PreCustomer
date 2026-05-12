package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.modules.shopping.client.NaverShoppingFeignClient;
import com.example.personareport.report.job.ReportJobWorker;
import com.example.personareport.report.pipeline.DeepSeekFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"h2", "web"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.persona.import-enabled=false",
                "app.persona.sample-import-enabled=false"
        })
class ReportWebRuntimeProfileTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @MockBean
    private DeepSeekFeignClient deepSeekClient;

    @MockBean
    private NaverShoppingFeignClient naverFeign;

    @Test
    void webProfileDisablesReportWorker() {
        assertThat(environment.getProperty("app.pipeline.worker-enabled", Boolean.class)).isFalse();
        assertThat(context.getBeanNamesForType(ReportJobWorker.class)).isEmpty();
    }
}
