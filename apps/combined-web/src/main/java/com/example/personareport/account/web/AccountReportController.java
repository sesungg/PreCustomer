package com.example.personareport.account.web;

import com.example.personareport.common.exception.ResourceNotFoundException;
import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.service.ReportDataService;
import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.service.UserAccountService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/account/reports")
@ConditionalOnProperty(prefix = "app.web", name = "public-enabled", havingValue = "true", matchIfMissing = true)
public class AccountReportController {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREA);

    private final OrderService orderService;
    private final ReportDataService reportDataService;
    private final UserAccountService userAccountService;

    @GetMapping
    public String list(Model model, Authentication authentication) {
        UserAccount account = requireUser(authentication);
        List<AccountReportSummary> reports = orderService.findOrdersForCustomer(account).stream()
                .map(this::toSummary)
                .toList();

        model.addAttribute("loggedInUser", account);
        model.addAttribute("reports", reports);
        model.addAttribute("completedCount", reports.stream().filter(AccountReportSummary::reportReady).count());
        return "account/reports/list";
    }

    @GetMapping("/{orderId}")
    public String detail(@PathVariable Long orderId, Model model, Authentication authentication) {
        UserAccount account = requireUser(authentication);
        ReactionReportOrder order = orderService.getOrder(orderId);
        if (!owns(order, account)) {
            throw new ResourceNotFoundException("리포트를 찾을 수 없습니다.");
        }

        var reports = reportDataService.findReportByOrderId(orderId);
        boolean reportReady = !reports.isEmpty();
        Map<String, Object> report = reportReady ? reports.get(0) : Map.of();

        model.addAttribute("loggedInUser", account);
        model.addAttribute("order", order);
        model.addAttribute("reportReady", reportReady);
        model.addAttribute("report", report);
        model.addAttribute("summary", toSummary(order));
        model.addAttribute("reportDate", reportReady ? dateText(report.get("created_at")) : null);
        model.addAttribute("purchaseIntentScore", toInt(report.get("overall_purchase_intent_score")));
        model.addAttribute("targetFitScore", toInt(report.get("overall_target_fit_score")));
        model.addAttribute("priceScore", toInt(report.get("overall_price_acceptance_score")));
        model.addAttribute("trustScore", toInt(report.get("overall_trust_score")));
        model.addAttribute("clarityScore", toInt(report.get("overall_detail_page_clarity_score")));
        model.addAttribute("sections", reportReady ? reportSections(report) : List.of());
        return "account/reports/detail";
    }

    private AccountReportSummary toSummary(ReactionReportOrder order) {
        var reports = reportDataService.findReportByOrderId(order.getId());
        boolean reportReady = !reports.isEmpty();
        Map<String, Object> report = reportReady ? reports.get(0) : Map.of();
        return new AccountReportSummary(
                order.getId(),
                order.getProjectName(),
                order.getTargetType().getLabel(),
                order.getStatus(),
                order.getStatus().getLabel(),
                dateText(order.getCreatedAt()),
                reportReady,
                dateText(report.get("created_at")),
                text(report.get("executive_summary")),
                toInt(report.get("overall_purchase_intent_score")),
                toInt(report.get("overall_trust_score"))
        );
    }

    private UserAccount requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ResourceNotFoundException("로그인이 필요합니다.");
        }
        UserAccount account = userAccountService.findByEmail(authentication.getName());
        if (account == null) {
            throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        }
        return account;
    }

    private boolean owns(ReactionReportOrder order, UserAccount account) {
        if (order.getCustomerAccountId() != null && order.getCustomerAccountId().equals(account.getId())) {
            return true;
        }
        return order.getCustomerEmail() != null
                && order.getCustomerEmail().equalsIgnoreCase(account.getEmail());
    }

    private List<ReportSection> reportSections(Map<String, Object> report) {
        List<ReportSection> sections = new ArrayList<>();
        addSection(sections, "한눈에 보는 결론", report.get("executive_summary"));
        addSection(sections, "최종 판단", report.get("final_verdict"));
        addSection(sections, "구매 가능성", report.get("purchase_intent_summary"));
        addSection(sections, "가격 반응", report.get("price_summary"));
        addSection(sections, "신뢰 분석", report.get("trust_summary"));
        addSection(sections, "타겟 적합성", report.get("target_validation_summary"));
        addSection(sections, "고객군별 반응", report.get("segment_summary"));
        addSection(sections, "상세페이지 피드백", report.get("detail_page_summary"));
        addSection(sections, "개선 제안", report.get("improvement_summary"));
        addSection(sections, "주의할 점", report.get("risk_summary"));
        return sections;
    }

    private void addSection(List<ReportSection> sections, String title, Object value) {
        String text = text(value);
        if (!text.isBlank()) {
            sections.add(new ReportSection(title, text));
        }
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String dateText(Object value) {
        if (value == null) return "";
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.format(DATE_TIME);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DATE_TIME);
        }
        return value.toString();
    }

    public record AccountReportSummary(
            Long orderId,
            String projectName,
            String targetTypeLabel,
            OrderStatus status,
            String statusLabel,
            String orderedAt,
            boolean reportReady,
            String reportCreatedAt,
            String executiveSummary,
            int purchaseIntentScore,
            int trustScore
    ) {
    }

    public record ReportSection(String title, String content) {
    }
}
