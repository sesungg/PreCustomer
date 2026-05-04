package com.example.personareport.order.service;

import com.example.personareport.common.exception.ResourceNotFoundException;
import com.example.personareport.order.domain.OrderStatus;
import com.example.personareport.order.domain.ReactionReportOrder;
import com.example.personareport.order.dto.OrderRequest;
import com.example.personareport.order.repository.ReactionReportOrderRepository;
import com.example.personareport.report.service.ImageStorageService;
import com.example.personareport.report.service.ImageStorageService.ImageUploadException;
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
     * 이미지 업로드 실패 시에도 주문은 정상 생성된다.
     */
    @Transactional
    public ReactionReportOrder createOrder(OrderRequest request, List<MultipartFile> images) {
        ReactionReportOrder order = ReactionReportOrder.create(
                request.customerEmail(),
                request.projectName(),
                request.targetType(),
                request.oneLineDescription(),
                request.detailDescription(),
                request.pageUrl(),
                request.priceText(),
                request.targetCustomer(),
                request.mainQuestion(),
                request.reportPerspective(),
                request.privacyAgreement()
        );

        order = orderRepository.save(order);

        if (images != null && !images.isEmpty()) {
            try {
                String imagePaths = imageStorageService.storeImages(order.getId(), images);
                if (imagePaths != null) {
                    order.setImagePaths(imagePaths);
                }
            } catch (ImageUploadException e) {
                log.warn("이미지 업로드 실패 (주문은 생성됨): {}", e.getMessage());
            }
        }

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

    /** 주문 상태를 PAID로 변경한다. */
    @Transactional
    public ReactionReportOrder markPaid(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markPaid();
        return order;
    }

    /** 주문 상태를 FAILED로 변경한다. */
    @Transactional
    public ReactionReportOrder markFailed(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markFailed();
        return order;
    }
}
