package com.example.personareport.publicweb;

import com.example.personareport.PersonaReportApplication;
import org.springframework.boot.SpringApplication;

public class PublicWebApplication {

    private PublicWebApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(PersonaReportApplication.class, args);
    }
}
