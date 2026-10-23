package com.example.demo.app;

import com.example.demo.application.ports.LinkCache;
import com.example.demo.application.ports.LinkInfrastructureFactory;
import com.example.demo.application.ports.LinkRepository;
import com.example.demo.infrastructure.factory.MongoOnlyFactory;
import com.example.demo.infrastructure.factory.MongoRedisFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class InfrastructureConfig {
    @Bean
    @Profile("!sin-cache")
    LinkInfrastructureFactory mongoRedisFactory(MongoTemplate mongo, StringRedisTemplate redis, ObjectMapper mapper) {
        return new MongoRedisFactory(mongo, redis, mapper);
    }

    @Bean
    @Profile("sin-cache")
    LinkInfrastructureFactory mongoOnlyFactory(MongoTemplate mongo) {
        return new MongoOnlyFactory(mongo);
    }

    @Bean
    LinkRepository linkRepository(LinkInfrastructureFactory factory) {
        return factory.createRepository();
    }

    @Bean
    LinkCache linkCache(LinkInfrastructureFactory factory) {
        return factory.createCache();
    }
}
