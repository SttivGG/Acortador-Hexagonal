package com.example.demo.infrastructure.factory;

import com.example.demo.application.ports.LinkCache;
import com.example.demo.application.ports.LinkInfrastructureFactory;
import com.example.demo.application.ports.LinkRepository;
import com.example.demo.infrastructure.cache.redis.RedisLinkCache;
import com.example.demo.infrastructure.persistence.mongo.MongoLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

// Familia principal: MongoDB para datos persistentes + Redis para caché.
public class MongoRedisFactory implements LinkInfrastructureFactory {
    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public MongoRedisFactory(MongoTemplate mongo, StringRedisTemplate redis, ObjectMapper mapper) {
        this.mongo = mongo;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override public LinkRepository createRepository() { return new MongoLinkRepository(mongo); }
    @Override public LinkCache createCache() { return new RedisLinkCache(redis, mapper); }
}
