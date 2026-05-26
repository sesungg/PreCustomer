package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"h2", "public-web"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
                "spring.session.store-type=none",
                "management.health.redis.enabled=false",
                "app.persona.import-enabled=false",
                "app.persona.sample-import-enabled=false"
        })
class ReportPublicWebRuntimeProfileTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Test
    void publicWebProfileExposesOnlyPublicControllers() {
        assertThat(environment.getProperty("app.web.public-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.web.admin-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.pipeline.worker-enabled", Boolean.class)).isFalse();
        assertThat(context.containsBeanDefinition("landingController")).isTrue();
        assertThat(context.containsBeanDefinition("orderController")).isTrue();
        assertThat(context.containsBeanDefinition("accountReportController")).isTrue();
        assertThat(context.containsBeanDefinition("adminOrderController")).isFalse();
        assertThat(context.containsBeanDefinition("reportViewController")).isFalse();
        assertThat(context.containsBeanDefinition("reportPdfController")).isFalse();
        assertThat(context.containsBeanDefinition("adminMvController")).isFalse();
        assertThat(context.containsBeanDefinition("shoppingSearchController")).isFalse();
        assertThat(context.containsBeanDefinition("reportJobWorker")).isFalse();
    }
}
