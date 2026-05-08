package com.tcc.coordinator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CoordinatorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoordinatorServiceApplication.class, args);
    }
}
