package com.smart.oilfield.pump;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.smart.oilfield.common.repository")
@EntityScan(basePackages = "com.smart.oilfield.common.entity")
@EnableConfigurationProperties
@EnableAsync
@EnableScheduling
public class PumpDiagnoserApplication {

    public static void main(String[] args) {
        SpringApplication.run(PumpDiagnoserApplication.class, args);
    }
}
