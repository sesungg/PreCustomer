package com.example.personareport.order.service;

import com.example.personareport.common.exception.ResourceNotFoundException;
import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** report-worker에서 필요한 주문 조회와 상태 전이만 담당한다. */
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
