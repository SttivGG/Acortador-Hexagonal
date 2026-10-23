package com.example.demo.infrastructure;

import com.example.demo.domain.model.ShortLink;
import com.example.demo.infrastructure.cache.redis.RedisLinkCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisLinkCacheTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisLinkCache cache;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new RedisLinkCache(redis, mapper);
    }

    @Test
    void writesOnlyAnIndividualKeyWithNxAndThirtySeconds() throws Exception {
        ShortLink link = new ShortLink(7, "https://example.com/", "code1234", Instant.now(), true);
        cache.startWindow(link);
        verify(values).setIfAbsent("shortener:link:7", mapper.writeValueAsString(link), Duration.ofSeconds(30));
        verifyNoMoreInteractions(values);
    }

    @Test
    void readsNeverWriteOrExtendEvenOnMissOrCorruptPayload() {
        when(values.get("shortener:link:7")).thenReturn(null, "corrupt");
        assertTrue(cache.get(7).isEmpty());
        assertTrue(cache.get(7).isEmpty());
        verify(values, times(2)).get("shortener:link:7");
        verifyNoMoreInteractions(values);
    }

    @Test
    void ttlComesDirectlyFromPttlAndTreatsMissingAsZero() {
        when(redis.getExpire("shortener:link:7", TimeUnit.MILLISECONDS)).thenReturn(19931L, -2L, -1L);
        assertEquals(19931, cache.remainingMillis(7));
        assertEquals(0, cache.remainingMillis(7));
        assertEquals(0, cache.remainingMillis(7));
        verifyNoInteractions(values);
    }
}
