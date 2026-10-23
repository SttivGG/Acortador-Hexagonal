package com.example.demo.domain.model;

import java.time.Instant;

// Dominio puro: no depende de Spring ni de MongoDB.
public record ShortLink(long id, String originalUrl, String shortCode, Instant createdAt,
                        boolean cacheWindowConsumed) { }
