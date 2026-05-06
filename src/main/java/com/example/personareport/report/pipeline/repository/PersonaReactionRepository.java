package com.example.personareport.report.pipeline.repository;

import com.example.personareport.report.pipeline.entity.PersonaReaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonaReactionRepository extends JpaRepository<PersonaReaction, Long> {
    List<PersonaReaction> findByReportOrderId(Long orderId);
    int countByReportOrderId(Long orderId);
}
