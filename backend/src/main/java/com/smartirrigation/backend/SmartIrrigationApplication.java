package com.smartirrigation.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SmartIrrigationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartIrrigationApplication.class, args);
    }
}
