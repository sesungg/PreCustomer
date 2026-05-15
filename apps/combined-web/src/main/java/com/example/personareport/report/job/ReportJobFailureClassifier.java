package com.example.personareport.report.job;

import com.example.personareport.report.pipeline.DeepSeekService;
import feign.FeignException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportJobFailureClassifier {

    private final DeepSeekService deepSeekService;

    public Failure classify(Throwable error, String message) {
        String combined = (messageOrEmpty(message) + "\n" + collectMessages(error))
                .toLowerCase(Locale.ROOT);

        if (isUserStop(combined)) {
            return new Failure("USER_STOP", false);
        }
        if ((error != null && deepSeekService.isTransientFailure(error))
                || isDeepSeekTransient(combined)) {
            return new Failure("DEEPSEEK_TRANSIENT", true);
        }
        if (isExternalTransient(combined)) {
            return new Failure("EXTERNAL_TRANSIENT", true);
        }
        if (combined.contains("bad sql grammar")
                || combined.contains("constraint")
                || combined.contains("current transaction is aborted")) {
            return new Failure("DATA_ERROR", false);
        }
        return new Failure("UNKNOWN", false);
    }

    private boolean isUserStop(String message) {
        return message.contains("사용자 요청")
                || message.contains("stop requested")
                || message.contains("cancel");
    }

    private boolean isDeepSeekTransient(String message) {
        return (message.contains("deepseek")
                && (message.contains("일시 장애")
                || message.contains("응답에 choices가 없습니다")
                || message.contains("json 파싱 실패")
                || message.contains("응답 파싱 오류")
                || message.contains("error while extracting response")
                || message.contains("read timed out")
                || message.contains("timeout")
                || message.contains("service is too busy")
                || message.contains("service_unavailable")
                || message.contains("internal_error")
                || message.contains("internal server error")
                || message.contains("503")
                || message.contains("502")
                || message.contains("500")
                || message.contains("too many requests")
                || message.contains("429")))
                || message.contains("모든 페르소나 반응 생성 실패");
    }

    private boolean isExternalTransient(String message) {
        return message.contains("read timed out")
                || message.contains("connect timed out")
                || message.contains("connection reset")
                || message.contains("socket timeout")
                || message.contains("temporarily unavailable")
                || message.contains("service unavailable")
                || message.contains("too many requests")
                || message.contains("503")
                || message.contains("502")
                || message.contains("504")
                || message.contains("429");
    }

    private String collectMessages(Throwable error) {
        if (error == null) return "";
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append('\n');
            }
            if (current instanceof FeignException feign) {
                sb.append(feign.contentUTF8()).append('\n');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private String messageOrEmpty(String message) {
        return message == null ? "" : message;
    }

    public record Failure(String type, boolean retryable) {}
}
