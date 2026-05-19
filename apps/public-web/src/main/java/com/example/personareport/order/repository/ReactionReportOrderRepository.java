package com.example.personareport.order.repository;

import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.domain.ReactionReportOrder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReactionReportOrderRepository extends JpaRepository<ReactionReportOrder, Long> {

    List<ReactionReportOrder> findAllByOrderByCreatedAtDesc();

    List<ReactionReportOrder> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    List<ReactionReportOrder> findByCustomerAccountIdOrCustomerEmailIgnoreCaseOrderByCreatedAtDesc(
            Long customerAccountId,
            String customerEmail
    );
}
