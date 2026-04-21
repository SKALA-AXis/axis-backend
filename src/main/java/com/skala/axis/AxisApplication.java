package com.skala.axis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AxisApplication {
    public static void main(String[] args) {
        SpringApplication.run(AxisApplication.class, args);
    }
}
