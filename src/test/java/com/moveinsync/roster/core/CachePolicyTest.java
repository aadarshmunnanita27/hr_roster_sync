package com.moveinsync.roster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CachePolicyTest {
    @Test
    void expiresReadMostlyDataAtTheConfiguredBoundAndSupportsInvalidation() {
        AtomicLong clock = new AtomicLong();
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMinutes(5))
                .ticker(clock::get)
                .build();
        cache.put("tenant:BLR01", "active");

        assertEquals("active", cache.getIfPresent("tenant:BLR01"));
        cache.invalidate("tenant:BLR01");
        assertNull(cache.getIfPresent("tenant:BLR01"));

        cache.put("tenant:BLR02", "active");
        clock.addAndGet(TimeUnit.MINUTES.toNanos(6));
        assertNull(cache.getIfPresent("tenant:BLR02"));
    }
}
