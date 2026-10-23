package com.example.demo.infrastructure.persistence.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "links")
public class MongoLinkDocument {
    @Id
    private Long id;
    private String originalUrl;
    @Indexed(unique = true)
    private String shortCode;
    private Instant createdAt;
    private String normalizedUrl;
    private boolean cacheWindowConsumed;

    public MongoLinkDocument() { }
    public MongoLinkDocument(Long id, String originalUrl, String shortCode, Instant createdAt) {
        this.id = id;
        this.originalUrl = originalUrl;
        this.shortCode = shortCode;
        this.createdAt = createdAt;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
    public String getShortCode() { return shortCode; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getNormalizedUrl() { return normalizedUrl; }
    public void setNormalizedUrl(String normalizedUrl) { this.normalizedUrl = normalizedUrl; }
    public boolean isCacheWindowConsumed() { return cacheWindowConsumed; }
    public void setCacheWindowConsumed(boolean consumed) { this.cacheWindowConsumed = consumed; }
}
