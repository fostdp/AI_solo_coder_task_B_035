package com.smart.oilfield.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    @Value("${gateway.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${gateway.rate-limit.default-permits-per-second:100}")
    private int defaultPermitsPerSecond;

    @Value("${gateway.rate-limit.burst-capacity:200}")
    private int burstCapacity;

    @Value("${gateway.rate-limit.timeout-seconds:1}")
    private int timeoutSeconds;

    private final ConcurrentMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!rateLimitEnabled) {
            return chain.filter(exchange);
        }

        String clientIp = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";

        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(clientIp,
                k -> new RateLimiter(defaultPermitsPerSecond, burstCapacity));

        if (rateLimiter.tryAcquire()) {
            return chain.filter(exchange);
        } else {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            return rateLimitExceededResponse(exchange);
        }
    }

    private Mono<Void> rateLimitExceededResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String errorMessage = "{\"error\":\"Rate limit exceeded\",\"status\":429,\"message\":\"Too many requests\"}";
        byte[] bytes = errorMessage.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private static class RateLimiter {
        private final double permitsPerSecond;
        private final int burstCapacity;
        private final AtomicInteger availablePermits;
        private final AtomicLong lastRefillTime;
        private final long refillIntervalMillis;

        public RateLimiter(double permitsPerSecond, int burstCapacity) {
            this.permitsPerSecond = permitsPerSecond;
            this.burstCapacity = burstCapacity;
            this.availablePermits = new AtomicInteger(burstCapacity);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
            this.refillIntervalMillis = 1000;
        }

        public synchronized boolean tryAcquire() {
            refill();
            if (availablePermits.get() > 0) {
                availablePermits.decrementAndGet();
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long last = lastRefillTime.get();
            long elapsed = now - last;

            if (elapsed >= refillIntervalMillis) {
                int permitsToAdd = (int) ((elapsed / refillIntervalMillis) * permitsPerSecond);
                permitsToAdd = Math.min(permitsToAdd, burstCapacity);
                if (permitsToAdd > 0) {
                    availablePermits.set(Math.min(availablePermits.get() + permitsToAdd, burstCapacity));
                    lastRefillTime.set(now);
                }
            }
        }
    }
}
