package com.example.personareport.report.event;

import com.example.personareport.report.job.ReportJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportJobEventPublisher {

    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
    private final ObjectMapper objectMapper;

    @Value("${app.events.kafka.enabled:false}")
    private boolean enabled;

    @Value("${app.events.kafka.topics.report-job-events:report-job-events}")
    private String reportJobEventsTopic;

    public void publishQueued(ReportJob job) {
        publish("REPORT_JOB_QUEUED", job);
    }

    public void publishCompleted(ReportJob job) {
        publish("REPORT_JOB_COMPLETED", job);
    }

    private void publish(String eventType, ReportJob job) {
        if (!enabled || job == null) return;
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        if (kafkaTemplate == null) {
            log.warn("Kafka event publishing is enabled but KafkaTemplate is not available. eventType={}, jobId={}",
                    eventType, job.getId());
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", eventType);
            event.put("eventVersion", 1);
            event.put("occurredAt", Instant.now().toString());
            event.put("jobId", job.getId());
            event.put("reportOrderId", job.getReportOrderId());
            event.put("jobType", job.getJobType());
            event.put("status", job.getStatus());
            event.put("forceRegenerate", job.isForceRegenerate());
            event.put("hasImages", job.isHasImages());
            kafkaTemplate.send(reportJobEventsTopic, String.valueOf(job.getReportOrderId()),
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Kafka report job event publish failed. eventType={}, jobId={}, error={}",
                    eventType, job.getId(), e.getMessage());
        }
    }
}
