package com.example.personareport.report.pipeline.repository;

import com.example.personareport.report.pipeline.entity.PageImageAnalysis;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PageImageAnalysisRepository extends JpaRepository<PageImageAnalysis, Long> {

    List<PageImageAnalysis> findByReportOrderIdOrderByImagePathAscImagePartNoAsc(Long reportOrderId);

    List<PageImageAnalysis> findByReportOrderIdAndPageSnapshotIdOrderByImagePathAscImagePartNoAsc(
            Long reportOrderId, Long pageSnapshotId
    );
}
