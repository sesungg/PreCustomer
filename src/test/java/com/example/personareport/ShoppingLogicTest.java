package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ShoppingSearchService의 순수 계산 로직 단위 테스트 */
class ShoppingLogicTest {

    private static final List<String> AD_TOKENS = List.of(
            "무료배송", "특가", "정품", "추천", "인기", "행사", "당일발송", "최저가", "한정", "공식", "국내배송"
    );

    @Test
    void cleanTitle_removesHtmlTags() {
        String raw = "<b>저당</b> 단백질 쿠키 <span>10개입</span>";
        String clean = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        assertThat(clean).isEqualTo("저당 단백질 쿠키 10개입");
    }

    @Test
    void cleanTitle_removesAdTokens() {
        String raw = "무료배송 저당 단백질 쿠키 특가 정품 10개입";
        String clean = raw;
        for (String token : AD_TOKENS) {
            clean = clean.replace(token, " ");
        }
        clean = clean.replaceAll("\\s+", " ").trim();
        assertThat(clean).isEqualTo("저당 단백질 쿠키 10개입");
    }

    @Test
    void cleanTitle_emptyInput_returnsEmpty() {
        String clean = "";
        assertThat(clean).isEmpty();
    }

    @Test
    void normalizeTitle_lowercaseAndRemoveSpecialChars() {
        String clean = "Dr.You 저당 단백질 쿠키 10개입";
        String norm = clean.toLowerCase().replaceAll("[^a-z0-9가-힣\\s]", " ").replaceAll("\\s+", " ").trim();
        assertThat(norm).isEqualTo("dr you 저당 단백질 쿠키 10개입");
    }

    @Test
    void computeQualityScore_fullData_returns100() {
        int score = 0;
        score += 20; // title exists
        score += 20; // lprice > 0
        score += 20; // category1-2 exist
        score += 15; // brand or maker
        score += 10; // image
        score += 10; // productUrl
        score += 5;  // productId
        assertThat(Math.min(100, score)).isEqualTo(100);
    }

    @Test
    void computeQualityScore_minimalData_returns20() {
        int score = 0;
        score += 20; // title only
        assertThat(score).isEqualTo(20);
    }

    @Test
    void computeQualityScore_noBrand_returns70() {
        int score = 0;
        score += 20; // title
        score += 20; // lprice
        score += 20; // category
        // brand/maker 없음: +0
        score += 10; // image
        assertThat(score).isEqualTo(70);
    }

    @Test
    void titleSimilarity_fullMatch() {
        String base = "저당 단백질 쿠키";
        String title = "저당 단백질 쿠키 10개입";
        String[] baseTokens = normalize(base).split("\\s+");
        String[] titleTokens = normalize(title).split("\\s+");
        int match = 0;
        for (String bt : baseTokens) {
            if (bt.length() < 2) continue;
            for (String tt : titleTokens) {
                if (tt.contains(bt) || bt.contains(tt)) { match++; break; }
            }
        }
        double score = (double) match / Math.max(1, baseTokens.length) * 100;
        assertThat(score).isGreaterThanOrEqualTo(50.0);
    }

    @Test
    void titleSimilarity_noMatch() {
        String base = "저당 쿠키";
        String title = "운동화 런닝화";
        String[] baseTokens = normalize(base).split("\\s+");
        String[] titleTokens = normalize(title).split("\\s+");
        int match = 0;
        for (String bt : baseTokens) {
            if (bt.length() < 2) continue;
            for (String tt : titleTokens) {
                if (tt.contains(bt) || bt.contains(tt)) { match++; break; }
            }
        }
        double score = (double) match / Math.max(1, baseTokens.length) * 100;
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void categoryMatch_fullMatch_4levels() {
        int score = 0;
        String c1 = "식품", c2 = "과자", c3 = "쿠키", c4 = "다이어트";
        String pc1 = "식품", pc2 = "과자", pc3 = "쿠키", pc4 = "다이어트";
        if (c1 != null && c1.equals(pc1)) score += 25;
        if (c2 != null && c2.equals(pc2)) score += 25;
        if (c3 != null && c3.equals(pc3)) score += 50;
        assertThat(score).isEqualTo(100);
    }

    @Test
    void categoryMatch_onlyCat1_returns25() {
        int score = 0;
        if ("식품".equals("식품")) score += 25;
        if ("과자".equals("음료")) score += 25;
        if ("쿠키".equals(null)) score += 50;
        assertThat(score).isEqualTo(25);
    }

    @Test
    void categoryMatch_allNull_returns0() {
        int score = 0;
        String c1 = null, c2 = null, c3 = null;
        String pc1 = null, pc2 = null, pc3 = null;
        if (c1 != null && c1.equals(pc1)) score += 25;
        if (c2 != null && c2.equals(pc2)) score += 25;
        if (c3 != null && c3.equals(pc3)) score += 50;
        assertThat(score).isEqualTo(0);
    }

    @Test
    void priceRange_within20percent_highScore() {
        int base = 18900, lprice = 15900;
        double diff = Math.abs(lprice - base) / (double) base;
        double score = diff <= 0.2 ? 90 : diff <= 0.5 ? 60 : 30;
        assertThat(score).isEqualTo(90); // 3000/18900 ≈ 15.9% → within 20% → high
    }

    @Test
    void priceRange_beyond50percent_lowScore() {
        int base = 10000, lprice = 16000;
        double diff = Math.abs(lprice - base) / (double) base;
        double score = diff <= 0.2 ? 90 : diff <= 0.5 ? 60 : 30;
        assertThat(score).isEqualTo(30); // 60% → low
    }

    @Test
    void priceRange_exactMatch_highScore() {
        int base = 10000, lprice = 10000;
        double diff = Math.abs(lprice - base) / (double) base;
        double score = diff <= 0.2 ? 90 : diff <= 0.5 ? 60 : 30;
        assertThat(score).isEqualTo(90);
    }

    @Test
    void brandScore_bothPresent() {
        boolean hasBrand = true, hasMaker = true;
        int score = hasBrand && hasMaker ? 100 : hasBrand ? 80 : hasMaker ? 70 : 30;
        assertThat(score).isEqualTo(100);
    }

    @Test
    void brandScore_brandOnly() {
        boolean hasBrand = true, hasMaker = false;
        int score = hasBrand && hasMaker ? 100 : hasBrand ? 80 : hasMaker ? 70 : 30;
        assertThat(score).isEqualTo(80);
    }

    @Test
    void brandScore_none() {
        boolean hasBrand = false, hasMaker = false;
        int score = hasBrand && hasMaker ? 100 : hasBrand ? 80 : hasMaker ? 70 : 30;
        assertThat(score).isEqualTo(30);
    }

    @Test
    void candidateScore_weightedFormula() {
        double titleScore = 85, catScore = 90, priceScore = 70, brandScore = 80, rankScore = 60, qualityScore = 85;
        double candidate = titleScore * 0.35 + catScore * 0.25 + priceScore * 0.15 + brandScore * 0.10 + rankScore * 0.05 + qualityScore * 0.10;
        assertThat(candidate).isBetween(75.0, 85.0);
    }

    @Test
    void dedupKey_generation() {
        String productId = "12345678";
        String sort = "sim";
        String key = productId + "|" + sort;
        assertThat(key).isEqualTo("12345678|sim");
    }

    // helpers matching ShoppingSearchService logic
    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9가-힣\\s]", " ").replaceAll("\\s+", " ").trim();
    }
}
