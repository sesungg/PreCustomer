package com.example.personareport.report.repository;

import com.example.personareport.report.domain.PipelineProgress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineProgressRepository extends JpaRepository<PipelineProgress, Long> {
}
