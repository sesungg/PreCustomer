package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.personareport.order.repository.ReactionReportOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
        "spring.session.store-type=none",
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"h2", "public-web"})
class OrderFormFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReactionReportOrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Test
    void newOrderForm_isScreenshotBasedAndDoesNotShowRemovedFields() throws Exception {
        String html = mockMvc.perform(get("/orders/new"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("상품 판매 상세페이지 전체 캡처");
        assertThat(html).contains("이미지 최대 20장");
        assertThat(html).contains("배송비 정책");
        assertThat(html).contains("완전 무료배송");
        assertThat(html).doesNotContain("분석 대상 유형");
        assertThat(html).doesNotContain("한 줄 소개");
        assertThat(html).doesNotContain("상세 설명");
        assertThat(html).doesNotContain("페이지 URL");
        assertThat(html).doesNotContain("이미지 최대 10장");
        assertThat(html).doesNotContain("WebP로 자동 압축");
        assertThat(html).doesNotContain("절감");
    }

    @Test
    void createOrder_requiresDetailPageScreenshot() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("customerEmail", "test@example.com")
                        .param("projectName", "테스트 상품")
                        .param("priceText", "12,500원 / 2kg")
                        .param("shippingPolicyText", "완전 무료배송")
                        .param("targetCustomer", "온라인 식품 구매 고객")
                        .param("mainQuestion", "가격 경쟁력이 궁금합니다.")
                        .param("reportPerspective", "GENERAL_REACTION")
                        .param("privacyAgreement", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "상품 상세페이지 전체 캡처 이미지를 업로드해 주세요.")));

        assertThat(orderRepository.count()).isZero();
    }
}
