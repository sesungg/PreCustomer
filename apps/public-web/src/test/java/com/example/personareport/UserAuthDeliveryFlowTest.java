package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.domain.TargetType;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.report.delivery.domain.ReportDeliveryStatus;
import com.example.personareport.report.delivery.repository.ReportDeliveryRequestRepository;
import com.example.personareport.user.repository.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles({"h2", "public-web"})
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
        "spring.session.store-type=none",
        "management.health.redis.enabled=false",
        "app.persona.import-enabled=false",
        "app.persona.sample-import-enabled=false"
})
@AutoConfigureMockMvc
class UserAuthDeliveryFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ReactionReportOrderRepository orderRepository;

    @Autowired
    private ReportDeliveryRequestRepository deliveryRequestRepository;

    @Test
    void signupAfterOrder_linksAccountAndDeliveryRequest() throws Exception {
        ReactionReportOrder order = orderRepository.saveAndFlush(sampleOrder("before@example.com"));
        String email = uniqueEmail();

        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("displayName", "테스트 업체")
                        .param("email", email)
                        .param("password", "password123")
                        .param("passwordConfirm", "password123")
                        .param("termsAccepted", "true")
                        .param("privacyAccepted", "true")
                        .param("marketingAccepted", "true")
                        .param("returnOrderId", String.valueOf(order.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/orders/" + order.getId() + "/complete?joined=1"));

        var account = userAccountRepository.findByEmail(email).orElseThrow();
        var savedOrder = orderRepository.findById(order.getId()).orElseThrow();
        var delivery = deliveryRequestRepository.findByReportOrderId(order.getId()).orElseThrow();

        assertThat(account.getDisplayName()).isEqualTo("테스트 업체");
        assertThat(account.isTermsAccepted()).isTrue();
        assertThat(account.isPrivacyAccepted()).isTrue();
        assertThat(account.isMarketingAccepted()).isTrue();
        assertThat(account.getTermsAcceptedAt()).isNotNull();
        assertThat(account.getPrivacyAcceptedAt()).isNotNull();
        assertThat(account.getMarketingAcceptedAt()).isNotNull();
        assertThat(savedOrder.getCustomerAccountId()).isEqualTo(account.getId());
        assertThat(savedOrder.getCustomerEmail()).isEqualTo(email);
        assertThat(delivery.getUserAccountId()).isEqualTo(account.getId());
        assertThat(delivery.getEmail()).isEqualTo(email);
        assertThat(delivery.getStatus()).isEqualTo(ReportDeliveryStatus.PENDING);
    }

    @Test
    void signup_requiresMandatoryTermsConsent() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("displayName", "테스트 업체")
                        .param("email", uniqueEmail())
                        .param("password", "password123")
                        .param("passwordConfirm", "password123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("서비스 이용약관에 동의해 주세요.")))
                .andExpect(content().string(containsString("개인정보 수집 및 이용에 동의해 주세요.")));
    }

    @Test
    void anonymousUserCanSaveDeliveryEmailWithoutSignup() throws Exception {
        ReactionReportOrder order = orderRepository.saveAndFlush(sampleOrder("requester@example.com"));
        String email = uniqueEmail();

        mockMvc.perform(post("/orders/{id}/delivery-email", order.getId())
                        .with(csrf())
                        .param("email", email))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/orders/" + order.getId() + "/complete?deliverySaved=1"));

        var delivery = deliveryRequestRepository.findByReportOrderId(order.getId()).orElseThrow();

        assertThat(delivery.getUserAccountId()).isNull();
        assertThat(delivery.getEmail()).isEqualTo(email);
        assertThat(delivery.getStatus()).isEqualTo(ReportDeliveryStatus.PENDING);
    }

    private ReactionReportOrder sampleOrder(String email) {
        return ReactionReportOrder.create(
                email,
                "테스트 리포트",
                TargetType.DETAIL_PAGE,
                "상품 설명",
                "상세 설명",
                null,
                "12,500원",
                "완전 무료배송",
                "식품 구매자",
                "구매할까?",
                ReportPerspective.GENERAL_REACTION,
                true
        );
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
