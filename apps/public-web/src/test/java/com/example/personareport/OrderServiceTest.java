package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.domain.ReportPerspective;
import com.example.personareport.order.domain.TargetType;
import com.example.personareport.order.dto.OrderRequest;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.service.ImageStorageService;
import com.example.personareport.report.service.ImageStorageService.ImageUploadException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private ReactionReportOrderRepository orderRepository;

    @Mock
    private ImageStorageService imageStorageService;

    @InjectMocks
    private OrderService orderService;

    @Test
    void createOrder_savesWithRequestedStatus() {
        var request = new OrderRequest(
                "test@example.com", "테스트 상품", "19,900원", "완전 무료배송",
                "주요 타겟", "궁금한 점", ReportPerspective.GENERAL_REACTION, true
        );

        when(orderRepository.save(any(ReactionReportOrder.class))).thenAnswer(inv -> {
            ReactionReportOrder order = inv.getArgument(0);
            return order;
        });

        ReactionReportOrder order = orderService.createOrder(request, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REQUESTED);
        assertThat(order.getCustomerEmail()).isEqualTo("test@example.com");
        assertThat(order.getProjectName()).isEqualTo("테스트 상품");
        assertThat(order.getTargetType()).isEqualTo(TargetType.SMART_STORE);
        assertThat(order.getOneLineDescription()).isNull();
        assertThat(order.getDetailDescription()).isNull();
        assertThat(order.getPageUrl()).isNull();
        assertThat(order.getShippingPolicyText()).isEqualTo("완전 무료배송");
        assertThat(order.isPrivacyAgreement()).isTrue();
    }

    @Test
    void createOrder_imageUploadFailure_isPropagated() {
        var request = new OrderRequest(
                "test@example.com", "테스트 상품", "19,900원", "배송비 3,000원",
                "주요 타겟", "궁금한 점", ReportPerspective.GENERAL_REACTION, true
        );
        var image = new MockMultipartFile("images", "page.png", "image/png", new byte[]{1});

        when(orderRepository.save(any(ReactionReportOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(imageStorageService.storeImages(any(), any())).thenThrow(new ImageUploadException("저장 실패"));

        assertThatThrownBy(() -> orderService.createOrder(request, List.of(image)))
                .isInstanceOf(ImageUploadException.class)
                .hasMessage("저장 실패");
    }

    @Test
    void getOrder_existingId_returnsOrder() {
        ReactionReportOrder order = ReactionReportOrder.create(
                "test@example.com", "P", TargetType.SAAS, "desc", "detail",
                null, "price", "완전 무료배송", "target", "question", ReportPerspective.GENERAL_REACTION, true
        );
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        ReactionReportOrder found = orderService.getOrder(1L);
        assertThat(found).isNotNull();
        assertThat(found.getCustomerEmail()).isEqualTo("test@example.com");
    }

    @Test
    void findOrders_noStatus_returnsAll() {
        when(orderRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        assertThat(orderService.findOrders(null)).isEmpty();
    }

    @Test
    void markPaid_updatesStatus() {
        ReactionReportOrder order = ReactionReportOrder.create(
                "test@example.com", "P", TargetType.SAAS, "d", "d",
                null, "p", "완전 무료배송", "t", "q", ReportPerspective.GENERAL_REACTION, true
        );
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.markPaid(1L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void markFailed_updatesStatus() {
        ReactionReportOrder order = ReactionReportOrder.create(
                "test@example.com", "P", TargetType.SAAS, "d", "d",
                null, "p", "완전 무료배송", "t", "q", ReportPerspective.GENERAL_REACTION, true
        );
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.markFailed(1L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }
}
