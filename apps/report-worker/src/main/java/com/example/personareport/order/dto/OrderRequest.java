package com.example.personareport.order.dto;

import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.domain.TargetType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(
        @Email(message = "올바른 이메일 형식을 입력해 주세요.")
        @NotBlank(message = "신청자 이메일을 입력해 주세요.")
        String customerEmail,

        @NotBlank(message = "프로젝트/상품/서비스명을 입력해 주세요.")
        String projectName,

        @NotNull(message = "분석 대상 유형을 선택해 주세요.")
        TargetType targetType,

        String oneLineDescription,
        String detailDescription,
        String pageUrl,
        String priceText,
        String targetCustomer,
        String mainQuestion,

        @NotNull(message = "원하는 리포트 관점을 선택해 주세요.")
        ReportPerspective reportPerspective,

        @AssertTrue(message = "개인정보/민감정보 입력 금지 안내에 동의해 주세요.")
        boolean privacyAgreement
) {

    @AssertTrue(message = "한 줄 소개 또는 상세 설명 중 하나는 입력해 주세요.")
    public boolean isDescriptionPresent() {
        return hasText(oneLineDescription) || hasText(detailDescription);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
