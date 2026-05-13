package com.example.personareport.order.domain;

import lombok.Getter;

@Getter
public enum TargetType {
    IDEA("창업 아이디어"),
    APP("앱"),
    SAAS("SaaS"),
    SMART_STORE("스마트스토어"),
    DETAIL_PAGE("상세페이지"),
    EBOOK("전자책"),
    COURSE("강의"),
    FUNDING("펀딩"),
    CONTEST("공모전"),
    ETC("기타");

    private final String label;

    TargetType(String label) {
        this.label = label;
    }
}
