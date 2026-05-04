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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final ReactionReportOrderRepository orderRepository;
    private final ImageStorageService imageStorageService;

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

    @Transactional(readOnly = true)
    public ReactionReportOrder getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("신청 정보를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<ReactionReportOrder> findOrders(OrderStatus status) {
        if (status == null) {
            return orderRepository.findAllByOrderByCreatedAtDesc();
        }
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public ReactionReportOrder markPaid(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markPaid();
        return order;
    }

    @Transactional
    public ReactionReportOrder markFailed(Long id) {
        ReactionReportOrder order = getOrder(id);
        order.markFailed();
        return order;
    }
}
