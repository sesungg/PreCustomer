package com.example.personareport.order.domain;

import lombok.Getter;

@Getter
public enum ReportPerspective {
    GENERAL_REACTION("전체 반응 진단"),
    PURCHASE_INTENT("사용/구매 의향"),
    TARGET_VALIDATION("타겟 검증"),
    MESSAGE_VALIDATION("메시지 검증"),
    PRICE_RESISTANCE("가격 저항"),
    CONTEST_EVALUATION("공모전 평가 관점");

    private final String label;

    ReportPerspective(String label) {
        this.label = label;
    }
}
