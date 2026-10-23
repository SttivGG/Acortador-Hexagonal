package com.example.demo.infrastructure.factory;

import com.example.demo.application.ports.LinkCache;
import com.example.demo.application.ports.LinkInfrastructureFactory;
import com.example.demo.application.ports.LinkRepository;
import com.example.demo.infrastructure.cache.noop.NoCacheLinkCache;
import com.example.demo.infrastructure.persistence.mongo.MongoLinkRepository;
import org.springframework.data.mongodb.core.MongoTemplate;

// Familia alternativa demostrativa; NO usar para evaluar el requisito Redis/WebSocket.
public class MongoOnlyFactory implements LinkInfrastructureFactory {
    private final MongoTemplate mongo;
    public MongoOnlyFactory(MongoTemplate mongo) { this.mongo = mongo; }
    @Override public LinkRepository createRepository() { return new MongoLinkRepository(mongo); }
    @Override public LinkCache createCache() { return new NoCacheLinkCache(); }
}
