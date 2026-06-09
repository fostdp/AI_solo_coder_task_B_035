package com.smart.oilfield.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("connectivity-service", r -> r
                        .path("/api/connectivity/**")
                        .filters(f -> f.stripPrefix(1)
                                .addRequestHeader("X-Gateway-Id", "smart-oilfield-gateway")
                                .addResponseHeader("X-Gateway-Processed", "true"))
                        .uri("http://localhost:8081"))

                .route("profile-service", r -> r
                        .path("/api/profile/**")
                        .filters(f -> f.stripPrefix(1)
                                .addRequestHeader("X-Gateway-Id", "smart-oilfield-gateway")
                                .addResponseHeader("X-Gateway-Processed", "true"))
                        .uri("http://localhost:8082"))

                .route("eor-service", r -> r
                        .path("/api/eor/**")
                        .filters(f -> f.stripPrefix(1)
                                .addRequestHeader("X-Gateway-Id", "smart-oilfield-gateway")
                                .addResponseHeader("X-Gateway-Processed", "true"))
                        .uri("http://localhost:8083"))

                .route("pump-diagnoser-service", r -> r
                        .path("/api/fault-prediction/**", "/api/alarms/**", "/api/onnx/**")
                        .filters(f -> f.stripPrefix(1)
                                .addRequestHeader("X-Gateway-Id", "smart-oilfield-gateway")
                                .addResponseHeader("X-Gateway-Processed", "true"))
                        .uri("http://localhost:8084"))

                .route("simulation-service", r -> r
                        .path("/api/simulation/**")
                        .filters(f -> f.stripPrefix(1)
                                .addRequestHeader("X-Gateway-Id", "smart-oilfield-gateway")
                                .addResponseHeader("X-Gateway-Processed", "true"))
                        .uri("http://localhost:8085"))

                .build();
    }
}
