package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.personareport.report.web.ReportViewController;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportShippingEvidenceTest {

    @Test
    void shippingEvidence_usesManualFreeShippingAsActualPrice() throws Exception {
        Map<String, Object> shipping = invokeShippingEvidence("완전 무료배송", 19_900);

        assertThat(shipping.get("typeText")).isEqualTo("무료배송");
        assertThat(shipping.get("source")).isEqualTo("사용자 직접 입력");
        assertThat(shipping.get("actualPriceText")).isEqualTo("19,900원");
    }

    @Test
    void shippingEvidence_calculatesConditionalFreeThresholdWithManWon() throws Exception {
        Map<String, Object> shipping = invokeShippingEvidence("10만원 이상 구매 시 무료배송, 배송비 3,000원", 99_000);

        assertThat(shipping.get("typeText")).isEqualTo("조건부 무료배송");
        assertThat(shipping.get("thresholdText")).isEqualTo("100,000원 이상 구매 시");
        assertThat(shipping.get("amountText")).isEqualTo("3,000원");
        assertThat(shipping.get("actualPriceText")).isEqualTo("102,000원");
    }

    @Test
    void shippingEvidence_keepsMembershipFreeShippingConditional() throws Exception {
        Map<String, Object> shipping = invokeShippingEvidence("쿠팡와우 멤버십 가입 시 무료배송", 19_900);

        assertThat(shipping.get("typeText")).isEqualTo("조건부 무료배송");
        assertThat(shipping.get("actualPriceText")).isEqualTo("계산 불가");
        assertThat(shipping.get("note")).isEqualTo("조건부 배송 정책이라 조건 충족 여부를 함께 보아야 합니다.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evidenceSummary_usesImageReviewCountForScreenshotPrimary() throws Exception {
        ReportViewController controller = new ReportViewController(null);
        Method method = ReportViewController.class.getDeclaredMethod(
                "buildEvidenceSummary", Map.class, Map.class, List.class, Map.class, List.class);
        method.setAccessible(true);

        Map<String, Object> summary = (Map<String, Object>) method.invoke(
                controller,
                Map.of("project_name", "EDITOR PD 25W", "price_text", "4,400원", "shipping_policy_text", "배송비 3,000원"),
                Map.of("snapshot_status", "SCREENSHOT_PRIMARY", "raw_meta_json", "{}"),
                List.of(Map.of("visible_text", "상품명: EDITOR PD 25W. 리뷰 수: 535. 긍정 비율: 79%.")),
                Map.of(),
                List.of());

        Map<String, Object> product = (Map<String, Object>) summary.get("product");
        Map<String, String> reviewCount = (Map<String, String>) product.get("reviewCount");
        assertThat(reviewCount.get("value")).isEqualTo("535");
        assertThat(reviewCount.get("source")).isEqualTo("이미지 분석");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeShippingEvidence(String shippingPolicyText, long displayPrice) throws Exception {
        ReportViewController controller = new ReportViewController(null);
        Method method = ReportViewController.class.getDeclaredMethod(
                "buildShippingEvidence", Map.class, String.class, String.class, long.class, long.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(controller, Map.of(), "", shippingPolicyText, displayPrice, 0L);
    }
}
