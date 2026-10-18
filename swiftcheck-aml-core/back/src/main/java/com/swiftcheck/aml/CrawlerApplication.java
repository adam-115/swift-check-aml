package com.swiftcheck.aml;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // planificateur des parsers (IngestionScheduler)
public class CrawlerApplication {

    public static void main(String[] args) {
        // Force PF4J to deployment mode so it loads JARs instead of unpacked
        // directories
        System.setProperty("pf4j.mode", "deployment");
        SpringApplication.run(CrawlerApplication.class, args);
    }
}