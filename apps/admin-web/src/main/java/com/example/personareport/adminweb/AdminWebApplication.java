package com.example.personareport.adminweb;

import com.example.personareport.PersonaReportApplication;
import org.springframework.boot.SpringApplication;

public class AdminWebApplication {

    private AdminWebApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(PersonaReportApplication.class, args);
    }
}
