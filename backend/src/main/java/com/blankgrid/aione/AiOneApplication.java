package com.blankgrid.aione;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiOneApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiOneApplication.class, args);
    }
}
