package com.example.personareport.order.dto;

import com.example.personareport.order.domain.ReportPerspective;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(
        @Email(message = "올바른 이메일 형식을 입력해 주세요.")
        @NotBlank(message = "신청자 이메일을 입력해 주세요.")
        String customerEmail,

        @NotBlank(message = "상품명을 입력해 주세요.")
        String projectName,

        String priceText,

        @NotBlank(message = "배송비 정책을 입력해 주세요.")
        String shippingPolicyText,

        String targetCustomer,
        String mainQuestion,

        @NotNull(message = "원하는 리포트 관점을 선택해 주세요.")
        ReportPerspective reportPerspective,

        @AssertTrue(message = "개인정보/민감정보 입력 금지 안내에 동의해 주세요.")
        boolean privacyAgreement
) {}
