package com.example.demo.infrastructure.cache.redis;

import com.example.demo.application.ports.LinkCache;
import com.example.demo.domain.model.ShortLink;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisLinkCache implements LinkCache {
    private static final Duration TTL = Duration.ofSeconds(30);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisLinkCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Optional<ShortLink> get(long id) {
        String value = redis.opsForValue().get(key(id));
        if (value == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(value, ShortLink.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Bad payload: fall back to MongoDB. Reads never rebuild Redis.
            return Optional.empty();
        }
    }

    @Override
    public void startWindow(ShortLink link) {
        try {
            redis.opsForValue().setIfAbsent(key(link.id()), mapper.writeValueAsString(link), TTL);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el enlace", ex);
        }
    }

    @Override
    public long remainingMillis(long id) {
        Long ttl = redis.getExpire(key(id), TimeUnit.MILLISECONDS);
        return ttl == null ? 0 : Math.max(0, ttl);
    }

    private static String key(long id) { return "shortener:link:" + id; }
}
