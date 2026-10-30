package com.moveinsync.roster.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.roster.core.FieldOwnershipPolicy;
import com.moveinsync.roster.core.HrFingerprintService;
import com.moveinsync.roster.core.SafetyBrake;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfiguration {
    @Bean
    HrFingerprintService hrFingerprintService() {
        return new HrFingerprintService();
    }

    @Bean
    FieldOwnershipPolicy fieldOwnershipPolicy() {
        return new FieldOwnershipPolicy();
    }

    @Bean
    SafetyBrake safetyBrake() {
        return new SafetyBrake();
    }

    @Bean
    Cache<String, Boolean> officeCache(
            @Value("${roster.cache.maximum-size:10000}") long maximumSize,
            @Value("${roster.cache.ttl:5m}") Duration ttl) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttl)
                .build();
    }

    @Bean
    Cache<String, BigDecimal> tenantSettingsCache(
            @Value("${roster.cache.maximum-size:10000}") long maximumSize,
            @Value("${roster.cache.ttl:5m}") Duration ttl) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttl)
                .build();
    }
}
