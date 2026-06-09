package com.smart.oilfield.reservoir;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.smart.oilfield.common.repository")
@EntityScan(basePackages = "com.smart.oilfield.common.entity")
@EnableConfigurationProperties
@EnableAsync
public class ReservoirSimulationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservoirSimulationApplication.class, args);
    }
}
