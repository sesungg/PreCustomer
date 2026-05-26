package com.example.personareport.analytics.repository;

import com.example.personareport.analytics.domain.AnalyticsEventLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsEventLogRepository extends JpaRepository<AnalyticsEventLog, Long> {
}
