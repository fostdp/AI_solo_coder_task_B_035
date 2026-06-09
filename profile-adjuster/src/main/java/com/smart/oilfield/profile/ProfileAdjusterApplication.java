package com.smart.oilfield.profile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = {
        "com.smart.oilfield.common.repository",
        "com.smart.oilfield.profile.repository"
})
@EntityScan(basePackages = "com.smart.oilfield.common.entity")
public class ProfileAdjusterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProfileAdjusterApplication.class, args);
    }
}
