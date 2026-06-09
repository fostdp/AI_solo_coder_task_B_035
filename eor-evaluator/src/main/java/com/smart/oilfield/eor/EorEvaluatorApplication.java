package com.smart.oilfield.eor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.smart.oilfield.common.config.AdvancedFeaturesProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AdvancedFeaturesProperties.class)
@EnableJpaRepositories(basePackages = "com.smart.oilfield.common.repository")
@EntityScan(basePackages = "com.smart.oilfield.common.entity")
public class EorEvaluatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EorEvaluatorApplication.class, args);
    }
}
