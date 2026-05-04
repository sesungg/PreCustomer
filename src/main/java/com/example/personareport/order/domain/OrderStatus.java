package com.example.personareport.order.domain;

import lombok.Getter;

@Getter
public enum OrderStatus {
    REQUESTED("신청 완료"),
    PAID("입금 확인"),
    GENERATING("리포트 생성 중"),
    COMPLETED("리포트 완료"),
    FAILED("실패");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }
}
