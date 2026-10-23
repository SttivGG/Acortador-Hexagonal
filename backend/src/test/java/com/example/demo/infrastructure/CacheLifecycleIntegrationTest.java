package com.example.demo.infrastructure;

import com.example.demo.application.ports.LinkRepository;
import com.example.demo.application.service.LinkService;
import com.example.demo.domain.model.ShortLink;
import com.example.demo.infrastructure.cache.redis.RedisLinkCache;
import com.example.demo.infrastructure.persistence.mongo.MongoLinkRepository;
import com.example.demo.infrastructure.persistence.mongo.MongoLinkDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Uses dedicated test containers on 27018/6380, never the application's database. */
@EnabledIfEnvironmentVariable(named = "SHORTENER_INTEGRATION_TESTS", matches = "true")
class CacheLifecycleIntegrationTest {
    @Test
    void realRedisLifetimeDeduplicationConcurrencyAndPersistentRestart() throws Exception {
        try (var client = MongoClients.create("mongodb://127.0.0.1:27018")) {
            MongoTemplate mongo = new MongoTemplate(client, "shortener_test_" + UUID.randomUUID().toString().replace("-", ""));
            mongo.getCollection("sequences").insertOne(new Document("_id", "links").append("value", System.currentTimeMillis()));
            LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6380);
            connection.afterPropertiesSet();
            try {
                StringRedisTemplate redis = new StringRedisTemplate(connection);
                RedisLinkCache cache = new RedisLinkCache(redis, new ObjectMapper().findAndRegisterModules());
                LinkService service = new LinkService(new MongoLinkRepository(mongo), cache);
                ShortLink a = service.shorten("https://example.com/a");
                long initialTtl = cache.remainingMillis(a.id());
                assertTrue(initialTtl > 29000 && initialTtl <= 30000);
                assertTrue(mongo.findById(a.id(), MongoLinkDocument.class).isCacheWindowConsumed());
                Thread.sleep(10000);
                long ttlAfterTen = service.listLinks().cacheStatuses().getFirst().remainingMillis();
                assertTrue(ttlAfterTen > 17000 && ttlAfterTen <= 20000, "TTL after ten seconds: " + ttlAfterTen);
                assertEquals(a.id(), service.shorten("https://EXAMPLE.com:443/a").id());
                assertTrue(cache.remainingMillis(a.id()) <= ttlAfterTen);
                LinkService restarted = new LinkService(new MongoLinkRepository(mongo), cache);
                assertTrue(restarted.cacheStatus().getFirst().remainingMillis() <= ttlAfterTen);
                assertEquals(a.originalUrl(), restarted.resolveOriginalUrl(a.shortCode()));
                // Wait for actual Redis expiration, without manipulating EXPIRE or deleting the key.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (cache.remainingMillis(a.id()) > 0 && System.nanoTime() < deadline) Thread.sleep(100);
                assertEquals(0, cache.remainingMillis(a.id()));
                assertTrue(restarted.listLinks().cacheStatuses().getFirst().expired());
                assertEquals(a.id(), restarted.shorten(a.originalUrl()).id());
                assertEquals(0, cache.remainingMillis(a.id()));
                assertEquals(a.originalUrl(), restarted.resolveOriginalUrl(a.shortCode()));
                ShortLink b = restarted.shorten("https://example.com/b");
                assertTrue(cache.remainingMillis(b.id()) > 29000);
                assertEquals(0, cache.remainingMillis(a.id()));
                var pool = Executors.newFixedThreadPool(2);
                try {
                    var gate = new java.util.concurrent.CyclicBarrier(2);
                    Callable<ShortLink> create = () -> { gate.await(); return restarted.shorten("https://example.com/concurrent"); };
                    var results = pool.invokeAll(List.of(create, create));
                    assertEquals(results.get(0).get().id(), results.get(1).get().id());
                    assertEquals(1, mongo.getCollection("links").countDocuments(new Document("normalizedUrl", "https://example.com/concurrent")));
                } finally { pool.shutdownNow(); }
                assertEquals(3, mongo.getCollection("links").countDocuments());
                LinkService afterRestart = new LinkService(new MongoLinkRepository(mongo), cache);
                assertEquals(a.id(), afterRestart.shorten(a.originalUrl()).id());
                assertTrue(afterRestart.cacheStatus().getFirst().expired());
                assertFalse(Boolean.TRUE.equals(redis.hasKey("shortener:links:all")));
                assertFalse(Boolean.TRUE.equals(redis.hasKey("shortener:links:hit")));
            } finally { connection.destroy(); }
        }
    }

    @Test
    void migrationPreservesHistoricalDuplicatesAndTheirRedirects() {
        try (var client = MongoClients.create("mongodb://127.0.0.1:27018")) {
            MongoTemplate mongo = new MongoTemplate(client, "shortener_legacy_" + UUID.randomUUID().toString().replace("-", ""));
            mongo.getCollection("links").insertMany(List.of(
                    new Document("_id", 41L).append("originalUrl", "https://EXAMPLE.com:443").append("shortCode", "legacy41").append("createdAt", java.util.Date.from(Instant.now())),
                    new Document("_id", 42L).append("originalUrl", "https://example.com/").append("shortCode", "legacy42").append("createdAt", java.util.Date.from(Instant.now()))));
            MongoLinkRepository repo = new MongoLinkRepository(mongo);
            LinkRepository.Creation reused = repo.createIfAbsent(new ShortLink(0, "https://example.com/", "newcode1", Instant.now(), true));
            assertFalse(reused.created());
            assertEquals(41, reused.link().id());
            assertFalse(reused.link().cacheWindowConsumed());
            assertTrue(repo.findByCode("legacy41").isPresent());
            assertTrue(repo.findByCode("legacy42").isPresent());
            repo = new MongoLinkRepository(mongo);
            assertEquals(2, repo.findAll().size());
            assertEquals(1, mongo.getCollection("links").countDocuments(new Document("normalizedUrl", "https://example.com/")));
        }
    }
}
