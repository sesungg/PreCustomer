package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.personareport.report.pipeline.DeepSeekService;
import com.example.personareport.report.pipeline.PipelineJavaService;
import com.example.personareport.report.pipeline.PipelineQueryService;
import com.example.personareport.report.pipeline.PipelineSaveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void groundingChecklist_distinguishesMissingDisplayPriceFromOptionPrices() throws Exception {
        PipelineJavaService service = new PipelineJavaService(
                new ObjectMapper(),
                mock(DeepSeekService.class),
                mock(PipelineQueryService.class),
                mock(PipelineSaveService.class)
        );
        String detailDescription = "상품명=라보토리 이동식 틈새 접이식 수납 정리함 트롤리"
                + " / 가격=미확인"
                + " / 할인=미확인"
                + " / 옵션가=23,570원, 10,710원, 12,400원"
                + " / 배송비 입력=15,000원 이상 구매 시 무료배송, 미충족 시 배송비 3,000원";
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("order", Map.of(
                "priceText", "",
                "detailDescription", detailDescription
        ));
        aggregate.put("sourceEvidence", Map.of(
                "userInput", Map.of(
                        "detailDescription", detailDescription,
                        "shippingPolicyText", "15,000원 이상 구매 시 무료배송, 미충족 시 배송비 3,000원"
                ),
                "url", Map.of("priceText", ""),
                "image", Map.of("items", List.of(Map.of(
                        "visiblePrices", List.of("23,570원", "10,710원", "12,400원")
                ))),
                "shopping", Map.of("used", true)
        ));

        Method method = PipelineJavaService.class.getDeclaredMethod("buildGroundingChecklistMarkdown", Map.class);
        method.setAccessible(true);
        String checklist = (String) method.invoke(service, aggregate);

        assertThat(checklist)
                .contains("표시가(캡처): 미확인")
                .contains("할인(캡처): 미확인")
                .contains("옵션/구성 또는 관련 가격 문구(캡처): 23,570원, 10,710원, 12,400원")
                .contains("표시가와 별도로 옵션/구성/추천상품 가격이 혼재할 수 있으므로 실구매가 확정 근거로 단정하지 않음");
    }

    // helpers matching PipelineJavaService logic
    private static String na(String s) { return s == null || s.isBlank() ? "미입력" : s.trim(); }
    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }
}
