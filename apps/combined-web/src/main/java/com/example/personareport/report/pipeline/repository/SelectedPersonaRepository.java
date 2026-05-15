package com.example.personareport.report.pipeline.repository;

import com.example.personareport.report.pipeline.entity.SelectedPersona;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SelectedPersonaRepository extends JpaRepository<SelectedPersona, Long> {

    List<SelectedPersona> findByReportOrderId(Long orderId);

    @Modifying
    @Query("DELETE FROM SelectedPersona WHERE reportOrderId = ?1")
    void deleteByReportOrderId(Long orderId);
}
