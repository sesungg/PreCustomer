package com.example.personareport.order.service;

import com.example.personareport.common.exception.ResourceNotFoundException;
import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 주문 조회와 상태 전이를 담당하는 서비스. */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final ReactionReportOrderRepository orderRepository;

    /** 주문 ID로 조회. 없으면 ResourceNotFoundException 발생. */
    @Transactional(readOnly = true)
    public ReactionReportOrder getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("신청 정보를 찾을 수 없습니다."));
    }

    /** 주문 목록 조회. status가 null이면 전체, 있으면 필터링. */
    @Transactional(readOnly = true)
    public List<ReactionReportOrder> findOrders(OrderStatus status) {
        if (status == null) {
            return orderRepository.findAllByOrderByCreatedAtDesc();
        }
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    /** 주문 상태를 PAID로 변경한다. */
    @Transactional
    public ReactionReportOrder markPaid(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markPaid();
        return order;
    }

    /** 주문 상태를 GENERATING으로 변경한다. */
    @Transactional
    public ReactionReportOrder markGenerating(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markGenerating();
        return order;
    }

    /** 주문 상태를 FAILED로 변경한다. */
    @Transactional
    public ReactionReportOrder markFailed(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markFailed();
        return order;
    }

    /** 주문 상태를 STOPPED로 변경한다. */
    @Transactional
    public ReactionReportOrder markStopped(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markStopped();
        return order;
    }

    /** 주문 상태를 COMPLETED로 변경한다. (리포트 생성 완료 시 호출) */
    @Transactional
    public ReactionReportOrder markCompleted(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markCompleted();
        return order;
    }
}
