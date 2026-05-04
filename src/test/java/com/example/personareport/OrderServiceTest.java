package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                "test@example.com", "테스트 프로젝트", TargetType.SAAS,
                "한 줄 소개", "상세 설명", null, "19,900원",
                "주요 타겟", "궁금한 점", ReportPerspective.GENERAL_REACTION, true
        );

        when(orderRepository.save(any(ReactionReportOrder.class))).thenAnswer(inv -> {
            ReactionReportOrder order = inv.getArgument(0);
            return order;
        });

        ReactionReportOrder order = orderService.createOrder(request, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REQUESTED);
        assertThat(order.getCustomerEmail()).isEqualTo("test@example.com");
        assertThat(order.getProjectName()).isEqualTo("테스트 프로젝트");
        assertThat(order.isPrivacyAgreement()).isTrue();
    }

    @Test
    void getOrder_existingId_returnsOrder() {
        ReactionReportOrder order = ReactionReportOrder.create(
                "test@example.com", "P", TargetType.SAAS, "desc", "detail",
                null, "price", "target", "question", ReportPerspective.GENERAL_REACTION, true
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
                null, "p", "t", "q", ReportPerspective.GENERAL_REACTION, true
        );
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.markPaid(1L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void markFailed_updatesStatus() {
        ReactionReportOrder order = ReactionReportOrder.create(
                "test@example.com", "P", TargetType.SAAS, "d", "d",
                null, "p", "t", "q", ReportPerspective.GENERAL_REACTION, true
        );
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.markFailed(1L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }
}
