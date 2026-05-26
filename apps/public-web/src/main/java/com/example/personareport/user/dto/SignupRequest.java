package com.example.personareport.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "사용자 이름 또는 업체명을 입력해 주세요.")
        @Size(max = 120, message = "사용자 이름 또는 업체명은 120자 이하로 입력해 주세요.")
        String displayName,

        @Email(message = "올바른 이메일 형식을 입력해 주세요.")
        @NotBlank(message = "이메일을 입력해 주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해 주세요.")
        String password,

        @NotBlank(message = "비밀번호 확인을 입력해 주세요.")
        String passwordConfirm,

        @NotNull(message = "서비스 이용약관에 동의해 주세요.")
        @AssertTrue(message = "서비스 이용약관에 동의해 주세요.")
        Boolean termsAccepted,

        @NotNull(message = "개인정보 수집 및 이용에 동의해 주세요.")
        @AssertTrue(message = "개인정보 수집 및 이용에 동의해 주세요.")
        Boolean privacyAccepted,

        Boolean marketingAccepted,

        Long returnOrderId
) {

    @AssertTrue(message = "비밀번호와 비밀번호 확인이 일치해야 합니다.")
    public boolean isPasswordConfirmed() {
        return password != null && password.equals(passwordConfirm);
    }
}
