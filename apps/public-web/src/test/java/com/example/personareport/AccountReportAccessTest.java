package com.example.personareport;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.domain.TargetType;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.repository.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class AccountReportAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ReactionReportOrderRepository orderRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void accountReports_redirectsAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/account/reports"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
    }

    @Test
    void loginSuccess_redirectsUserToAccountReports() throws Exception {
        UserAccount owner = saveUser("login-owner");

        mockMvc.perform(formLogin("/login")
                        .user("username", owner.getEmail())
                        .password("password123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/account/reports"));
    }

    @Test
    void loginRejectsAccountWithoutRequiredConsents() throws Exception {
        String email = "legacy-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO user_account
                    (display_name, email, password_hash, role, enabled,
                     terms_accepted, privacy_accepted, marketing_accepted,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'USER', TRUE, FALSE, FALSE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                "기존 가입자",
                email,
                passwordEncoder.encode("password123")
        );

        mockMvc.perform(formLogin("/login")
                        .user("username", email)
                        .password("password123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login?error")));
    }

    @Test
    void accountReports_listsOnlyCurrentUserReports() throws Exception {
        UserAccount owner = saveUser("owner");
        UserAccount other = saveUser("other");
        ReactionReportOrder ownerOrder = saveCompletedOrder(owner, "내 최종 리포트");
        saveCompletedOrder(other, "다른 사용자 리포트");
        insertFinalReport(ownerOrder.getId(), "내 리포트 핵심 요약");

        mockMvc.perform(get("/account/reports").with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("내 최종 리포트")))
                .andExpect(content().string(containsString("내 리포트 핵심 요약")))
                .andExpect(content().string(not(containsString("다른 사용자 리포트"))));
    }

    @Test
    void accountReportDetail_allowsOnlyOwnedReport() throws Exception {
        UserAccount owner = saveUser("detail-owner");
        UserAccount other = saveUser("detail-other");
        ReactionReportOrder ownerOrder = saveCompletedOrder(owner, "상세 확인 리포트");
        ReactionReportOrder otherOrder = saveCompletedOrder(other, "타인 리포트");
        insertFinalReport(ownerOrder.getId(), "상세 화면 요약");
        insertFinalReport(otherOrder.getId(), "보이면 안 되는 요약");

        mockMvc.perform(get("/account/reports/{id}", ownerOrder.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("상세 확인 리포트")))
                .andExpect(content().string(containsString("상세 화면 요약")));

        mockMvc.perform(get("/account/reports/{id}", otherOrder.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isNotFound());
    }

    private UserAccount saveUser(String name) {
        String email = name + "-" + UUID.randomUUID() + "@example.com";
        return userAccountRepository.saveAndFlush(UserAccount.create(
                name,
                email,
                passwordEncoder.encode("password123")
        ));
    }

    private ReactionReportOrder saveCompletedOrder(UserAccount account, String projectName) {
        ReactionReportOrder order = ReactionReportOrder.create(
                account.getEmail(),
                projectName,
                TargetType.DETAIL_PAGE,
                "상품 설명",
                "상세 설명",
                null,
                "12,500원",
                "완전 무료배송",
                "구매자",
                "구매할까?",
                ReportPerspective.GENERAL_REACTION,
                true
        );
        order.attachCustomerAccount(account.getId(), account.getEmail());
        order.markCompleted();
        return orderRepository.saveAndFlush(order);
    }

    private void insertFinalReport(Long orderId, String summary) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS final_report (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    report_order_id BIGINT NOT NULL,
                    report_version VARCHAR(100),
                    response_version VARCHAR(100),
                    model_name VARCHAR(100),
                    model_version VARCHAR(100),
                    response_count INTEGER,
                    overall_purchase_intent_score INTEGER,
                    overall_target_fit_score INTEGER,
                    overall_price_acceptance_score INTEGER,
                    overall_trust_score INTEGER,
                    overall_detail_page_clarity_score INTEGER,
                    final_verdict TEXT,
                    executive_summary TEXT,
                    purchase_intent_summary TEXT,
                    price_summary TEXT,
                    trust_summary TEXT,
                    target_validation_summary TEXT,
                    segment_summary TEXT,
                    detail_page_summary TEXT,
                    improvement_summary TEXT,
                    risk_summary TEXT,
                    report_markdown TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO final_report (
                    report_order_id, report_version, response_version, model_name, model_version,
                    response_count, overall_purchase_intent_score, overall_target_fit_score,
                    overall_price_acceptance_score, overall_trust_score, overall_detail_page_clarity_score,
                    final_verdict, executive_summary, purchase_intent_summary, price_summary,
                    trust_summary, target_validation_summary, segment_summary,
                    detail_page_summary, improvement_summary, risk_summary, report_markdown
                )
                VALUES (?, 'test_report_v1', 'test_response_v1', 'test', 'test-model',
                    3, 82, 77, 74, 69, 88,
                    '구매 고려', ?, '구매 가능성이 있습니다.', '가격은 수용 가능합니다.',
                    '신뢰 보강이 필요합니다.', '타겟과 잘 맞습니다.', '핵심 고객군 반응이 좋습니다.',
                    '상세페이지 이해가 쉽습니다.', '후기를 더 노출하세요.', '과장 표현은 피하세요.', ?)
                """, orderId, summary, "# " + summary);
    }
}
