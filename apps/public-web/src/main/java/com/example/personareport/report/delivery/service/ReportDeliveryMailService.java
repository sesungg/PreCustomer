package com.example.personareport.report.delivery.service;

import com.example.personareport.report.delivery.domain.ReportDeliveryRequest;
import com.example.personareport.report.service.ReportDataService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDeliveryMailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ReportDeliveryMailProperties properties;
    private final ReportDataService reportDataService;

    public DeliveryResult sendCompletedReport(ReportDeliveryRequest request) {
        if (!properties.enabled()) {
            return DeliveryResult.ready("메일 발송이 비활성화되어 리포트 완료 상태만 저장했습니다.");
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            return DeliveryResult.ready("JavaMailSender 설정이 없어 리포트 완료 상태만 저장했습니다.");
        }

        var reports = reportDataService.findReportByOrderId(request.getReportOrderId());
        if (reports.isEmpty()) {
            return DeliveryResult.failed("최종 리포트 데이터가 없어 메일을 보낼 수 없습니다.");
        }
        Map<String, Object> report = reports.get(0);
        String markdown = String.valueOf(report.getOrDefault("report_markdown", ""));
        if (markdown == null || markdown.isBlank() || "null".equals(markdown)) {
            markdown = buildPlainSummary(report);
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(request.getEmail());
        message.setSubject("[PreCustomerReport] 리포트가 완성되었습니다");
        message.setText("""
                리포트가 완성되었습니다.

                주문 번호: %d
                확인 링크: %s/orders/%d/complete

                아래는 생성된 리포트 원문입니다.

                %s
                """.formatted(
                request.getReportOrderId(),
                properties.baseUrl(),
                request.getReportOrderId(),
                markdown
        ));
        mailSender.send(message);
        log.info("report delivery mail sent orderId={}, email={}", request.getReportOrderId(), request.getEmail());
        return DeliveryResult.sentResult();
    }

    private String buildPlainSummary(Map<String, Object> report) {
        return """
                한눈에 보는 결론
                %s

                최종 판단
                %s

                개선 제안
                %s
                """.formatted(
                value(report.get("executive_summary")),
                value(report.get("final_verdict")),
                value(report.get("improvement_summary"))
        );
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    public record DeliveryResult(boolean sent, boolean readyOnly, String message) {
        public static DeliveryResult sentResult() {
            return new DeliveryResult(true, false, null);
        }

        public static DeliveryResult ready(String message) {
            return new DeliveryResult(false, true, message);
        }

        public static DeliveryResult failed(String message) {
            return new DeliveryResult(false, false, message);
        }
    }
}
