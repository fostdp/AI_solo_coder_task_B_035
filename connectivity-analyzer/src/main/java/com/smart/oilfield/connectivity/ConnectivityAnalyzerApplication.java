package com.smart.oilfield.connectivity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.smart.oilfield.common.repository")
@EntityScan(basePackages = "com.smart.oilfield.common.entity")
@EnableConfigurationProperties
public class ConnectivityAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectivityAnalyzerApplication.class, args);
    }
}
