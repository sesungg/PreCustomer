package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.personareport.analytics.repository.AnalyticsEventLogRepository;
import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.repository.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles({"h2", "public-web"})
@SpringBootTest(properties = {
        "app.persona.import-enabled=false",
        "app.persona.sample-import-enabled=false",
        "app.analytics.ga4.enabled=true",
        "app.analytics.ga4.measurement-id=G-TEST123"
})
@AutoConfigureMockMvc
class AnalyticsEventLogTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalyticsEventLogRepository analyticsEventLogRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @BeforeEach
    void setUp() {
        analyticsEventLogRepository.deleteAll();
    }

    @Test
    void publicPages_includeGa4AndInternalEventScript() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("googletagmanager.com/gtag/js?id=G-TEST123")))
                .andExpect(content().string(containsString("gtag('config', \"G-TEST123\"")))
                .andExpect(content().string(containsString("/js/analytics.js")));
    }

    @Test
    void eventLogEndpoint_persistsAuthenticatedClientEvent() throws Exception {
        UserAccount account = userAccountRepository.saveAndFlush(UserAccount.create(
                "테스트 사용자",
                "analytics-" + UUID.randomUUID() + "@example.com",
                "hash"
        ));

        mockMvc.perform(post("/events/log")
                        .with(csrf())
                        .with(user(account.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventName": "final_report_open_clicked",
                                  "eventCategory": "account_report_list",
                                  "pagePath": "/account/reports",
                                  "anonymousId": "anon_test",
                                  "reportOrderId": 42,
                                  "metadata": {"source": "test"}
                                }
                                """))
                .andExpect(status().isNoContent());

        var events = analyticsEventLogRepository.findAll();
        assertThat(events).hasSize(1);
        var event = events.get(0);
        assertThat(event.getEventName()).isEqualTo("final_report_open_clicked");
        assertThat(event.getEventCategory()).isEqualTo("account_report_list");
        assertThat(event.getPagePath()).isEqualTo("/account/reports");
        assertThat(event.getUserAccountId()).isEqualTo(account.getId());
        assertThat(event.getReportOrderId()).isEqualTo(42L);
        assertThat(event.getMetadataJson()).contains("\"source\":\"test\"");
    }
}
