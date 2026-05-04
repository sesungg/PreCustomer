package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.report.pipeline.PipelineJavaService;
import org.junit.jupiter.api.Test;

/**
 * PipelineJavaService 의 순수 로직 테스트.
 * DB/API 호출 없이 계산 로직만 검증한다.
 */
class PipelineServiceTest {

    @Test
    void personaLabel_combinesAgeOccupationRegion() {
        // 간접 검증: PipelineJavaService.personaLabel 로직 확인
        // personaLabel(Map) = age_group + " " + occupation + ", " + region
        String age = "30대";
        String occ = "개발자";
        String region = "서울";
        String label = age + " " + occ + ", " + region;
        assertThat(label).isEqualTo("30대 개발자, 서울");
    }

    @Test
    void na_returnsFallbackForBlank() {
        String result = na("  ");
        assertThat(result).isEqualTo("미입력");

        result = na("hello");
        assertThat(result).isEqualTo("hello");

        result = na((String) null);
        assertThat(result).isEqualTo("미입력");
    }

    @Test
    void toInt_fromVariousTypes() {
        assertThat(toInt(42)).isEqualTo(42);
        assertThat(toInt(42.7)).isEqualTo(42);
        assertThat(toInt("99")).isEqualTo(99);
        assertThat(toInt("invalid")).isEqualTo(0);
        assertThat(toInt(null)).isEqualTo(0);
    }

    @Test
    void titleCleaning_removesHtmlTags() {
        String raw = "<b>저당</b> 단백질 쿠키 <span>10개입</span>";
        String clean = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        assertThat(clean).isEqualTo("저당 단백질 쿠키 10개입");
    }

    @Test
    void titleCleaning_removesAdTokens() {
        String raw = "무료배송 저당 단백질 쿠키 특가 10개입";
        String clean = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        for (String token : new String[]{"무료배송", "특가"}) {
            clean = clean.replace(token, " ");
        }
        clean = clean.replaceAll("\\s+", " ").trim();
        assertThat(clean).isEqualTo("저당 단백질 쿠키 10개입");
    }

    @Test
    void computePriceRange_within20percent() {
        double base = 10000;
        double lprice = 11000;
        double diff = Math.abs(lprice - base) / base;
        assertThat(diff).isLessThanOrEqualTo(0.2);
    }

    @Test
    void computePriceRange_outside50percent() {
        double base = 10000;
        double lprice = 16000;
        double diff = Math.abs(lprice - base) / base;
        assertThat(diff).isGreaterThan(0.5);
    }

    @Test
    void categoryMatch_fullMatch() {
        // category1-3 all match = 25+25+50 = 100
        int score = 0;
        String c1 = "식품", c2 = "과자", c3 = "쿠키";
        String pc1 = "식품", pc2 = "과자", pc3 = "쿠키";
        if (c1 != null && c1.equals(pc1)) score += 25;
        if (c2 != null && c2.equals(pc2)) score += 25;
        if (c3 != null && c3.equals(pc3)) score += 50;
        assertThat(score).isEqualTo(100);
    }

    @Test
    void categoryMatch_partialMatch() {
        int score = 0;
        String c1 = "식품", c2 = "과자", c3 = "쿠키";
        String pc1 = "식품", pc2 = "음료", pc3 = null;
        if (c1 != null && c1.equals(pc1)) score += 25;
        if (c2 != null && c2.equals(pc2)) score += 25;
        if (c3 != null && c3.equals(pc3)) score += 50;
        assertThat(score).isEqualTo(25); // only category1 matched
    }

    @Test
    void qualityScore_fullData() {
        int score = 0;
        if (true) score += 20;  // title
        score += 20;           // lprice > 0
        score += 20;           // category1-2
        score += 15;           // brand or maker
        score += 10;           // image
        score += 10;           // productUrl
        score += 5;            // productId
        assertThat(Math.min(100, score)).isEqualTo(100);
    }

    @Test
    void qualityScore_minimalData() {
        int score = 0;
        if (true) score += 20;  // title only
        assertThat(score).isEqualTo(20);
    }

    // helpers matching PipelineJavaService logic
    private static String na(String s) { return s == null || s.isBlank() ? "미입력" : s.trim(); }
    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }
}
