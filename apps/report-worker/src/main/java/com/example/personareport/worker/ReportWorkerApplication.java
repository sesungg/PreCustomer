package com.example.personareport.worker;

import com.example.personareport.PersonaReportApplication;
import org.springframework.boot.SpringApplication;

public class ReportWorkerApplication {

    private ReportWorkerApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(PersonaReportApplication.class, args);
    }
}
