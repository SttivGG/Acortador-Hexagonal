package com.example.demo.application.service;

import com.example.demo.application.ports.LinkCache;
import com.example.demo.application.ports.LinkRepository;
import com.example.demo.domain.model.ShortLink;
import com.example.demo.domain.model.UrlNormalizer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LinkService {
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final LinkRepository repository;
    private final LinkCache cache;

    public LinkService(LinkRepository repository, LinkCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public ShortLink shorten(String input) {
        UrlNormalizer.normalize(input);
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                // Persistent claim BEFORE Redis. Existing links never claim again.
                LinkRepository.Creation result = repository.createIfAbsent(
                        new ShortLink(0, input.trim(), randomCode(), Instant.now(), true));
                if (result.created()) cache.startWindow(result.link());
                return result.link();
            } catch (LinkRepository.CodeConflictException ignored) {
                // The unique shortCode index arbitrates collisions atomically.
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No fue posible generar un código único");
    }

    public LinkListResult listLinks() {
        List<ShortLink> persisted = repository.findAll();
        List<ShortLink> links = persisted.stream().map(link -> cache.get(link.id()).orElse(link)).toList();
        return new LinkListResult(links, persisted.stream().map(this::status).toList());
    }

    public String resolveOriginalUrl(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Enlace no encontrado"))
                .originalUrl();
    }

    public List<CacheStatus> cacheStatus() {
        return repository.findAll().stream().map(this::status).toList();
    }

    private CacheStatus status(ShortLink link) {
        long observedAt = System.currentTimeMillis();
        long ttl = cache.remainingMillis(link.id());
        return new CacheStatus(link.id(), ttl > 0, ttl <= 0 && link.cacheWindowConsumed(), Math.max(ttl, 0), observedAt);
    }

    private static String randomCode() {
        StringBuilder code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) code.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return code.toString();
    }
}
