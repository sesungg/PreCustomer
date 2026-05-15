package com.example.personareport.report.pipeline.repository;

import com.example.personareport.report.pipeline.entity.PersonaReaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PersonaReactionRepository extends JpaRepository<PersonaReaction, Long> {

    List<PersonaReaction> findByReportOrderId(Long orderId);

    List<PersonaReaction> findByReportOrderIdAndResponseVersionOrderBySelectionRank(Long orderId, String responseVersion);

    int countByReportOrderId(Long orderId);

    int countByReportOrderIdAndResponseVersion(Long orderId, String responseVersion);

    boolean existsByReportOrderIdAndPersonaProfileIdAndResponseVersion(
            Long orderId, Long personaProfileId, String responseVersion);

    @Modifying
    @Query("DELETE FROM PersonaReaction WHERE reportOrderId = ?1 AND responseVersion = ?2")
    void deleteByReportOrderIdAndResponseVersion(Long orderId, String responseVersion);
}
