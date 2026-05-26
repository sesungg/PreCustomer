package com.example.personareport.order.service;

import com.example.personareport.common.exception.ResourceNotFoundException;
import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.domain.TargetType;
import com.example.personareport.order.dto.OrderRequest;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.report.service.ImageStorageService;
import com.example.personareport.report.service.ImageStorageService.ImageUploadException;
import com.example.personareport.user.domain.UserAccount;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 주문 접수, 조회, 상태 변경을 담당하는 서비스 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final ReactionReportOrderRepository orderRepository;
    private final ImageStorageService imageStorageService;

    /**
     * 주문을 생성하고 업로드된 이미지를 저장한다.
     * 이미지 업로드 실패 시에는 주문 생성을 롤백한다.
     */
    @Transactional
    public ReactionReportOrder createOrder(OrderRequest request, List<MultipartFile> images) {
        return createOrder(request, images, null);
    }

    @Transactional
    public ReactionReportOrder createOrder(OrderRequest request, List<MultipartFile> images, UserAccount account) {
        ReactionReportOrder order = ReactionReportOrder.create(
                request.customerEmail(),
                request.projectName(),
                TargetType.SMART_STORE,
                null,
                null,
                null,
                request.priceText(),
                request.shippingPolicyText(),
                request.targetCustomer(),
                request.mainQuestion(),
                request.reportPerspective(),
                request.privacyAgreement()
        );
        if (account != null) {
            order.attachCustomerAccount(account.getId(), account.getEmail());
        }

        order = orderRepository.save(order);

        if (images != null && !images.isEmpty()) {
            try {
                String imagePaths = imageStorageService.storeImages(order.getId(), images);
                if (imagePaths != null) {
                    order.setImagePaths(imagePaths);
                }
            } catch (ImageUploadException e) {
                log.warn("이미지 업로드 실패: {}", e.getMessage());
                throw e;
            }
        }

        return order;
    }

    @Transactional
    public ReactionReportOrder attachCustomerAccount(Long id, UserAccount account) {
        ReactionReportOrder order = getOrder(id);
        order.attachCustomerAccount(account.getId(), account.getEmail());
        return order;
    }

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

    @Transactional(readOnly = true)
    public List<ReactionReportOrder> findOrdersForCustomer(UserAccount account) {
        if (account == null) return List.of();
        return orderRepository.findByCustomerAccountIdOrCustomerEmailIgnoreCaseOrderByCreatedAtDesc(
                account.getId(),
                account.getEmail()
        );
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
