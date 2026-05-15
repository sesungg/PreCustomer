package com.example.personareport.report.job;

import java.util.List;

public final class ReportJobStatus {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String STOP_REQUESTED = "STOP_REQUESTED";
    public static final String STOPPED = "STOPPED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    public static final List<String> ACTIVE = List.of(PENDING, RUNNING, STOP_REQUESTED);

    private ReportJobStatus() {
    }
}
