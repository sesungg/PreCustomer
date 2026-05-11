package com.example.personareport.report.pipeline.repository;

import com.example.personareport.report.pipeline.entity.ProductTargetProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductTargetProfileRepository extends JpaRepository<ProductTargetProfile, Long> {

    Optional<ProductTargetProfile> findFirstByReportOrderIdOrderByIdDesc(Long orderId);

    Optional<ProductTargetProfile> findFirstByReportOrderIdAndProfileVersionOrderByUpdatedAtDescIdDesc(
            Long orderId, String profileVersion);

    @Modifying
    @Query("DELETE FROM ProductTargetProfile WHERE reportOrderId = ?1")
    void deleteByReportOrderId(Long orderId);
}
