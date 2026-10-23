package com.example.demo.infrastructure.persistence.mongo;

import com.example.demo.application.ports.LinkRepository;
import com.example.demo.domain.model.ShortLink;
import com.example.demo.domain.model.UrlNormalizer;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class MongoLinkRepository implements LinkRepository {
    private final MongoTemplate mongo;

    public MongoLinkRepository(MongoTemplate mongo) {
        this.mongo = mongo;
        // Enforce uniqueness before accepting requests, even without auto-index-creation.
        mongo.indexOps(MongoLinkDocument.class).createIndex(
                new Index().on("shortCode", Sort.Direction.ASC).unique().named("shortCode"));
        mongo.indexOps(MongoLinkDocument.class).createIndex(
                new Index().on("normalizedUrl", Sort.Direction.ASC).unique().sparse());
        migrateLegacyLinks();
        MongoLinkDocument last = mongo.findOne(new Query().with(Sort.by(Sort.Direction.DESC, "id")), MongoLinkDocument.class);
        mongo.upsert(Query.query(Criteria.where("_id").is("links")),
                new Update().max("value", last == null ? 0L : last.getId()), SequenceDocument.class);
    }

    private void migrateLegacyLinks() {
        Query legacy = Query.query(Criteria.where("normalizedUrl").exists(false)
                .and("urlIdentityMigrated").ne(true)).with(Sort.by(Sort.Direction.ASC, "id"));
        for (MongoLinkDocument doc : mongo.find(legacy, MongoLinkDocument.class)) {
            Query byId = Query.query(Criteria.where("_id").is(doc.getId()));
            try {
                String normalized = UrlNormalizer.normalize(doc.getOriginalUrl());
                mongo.updateFirst(byId, new Update().set("normalizedUrl", normalized)
                        .set("urlIdentityMigrated", true), MongoLinkDocument.class);
            } catch (DuplicateKeyException | IllegalArgumentException ex) {
                // Keep historical duplicates and redirects. Earliest ID owns the URL.
                mongo.updateFirst(byId, new Update().set("urlIdentityMigrated", true), MongoLinkDocument.class);
            }
        }
    }

    @Override
    public Creation createIfAbsent(ShortLink link) {
        String normalized = UrlNormalizer.normalize(link.originalUrl());
        Optional<ShortLink> existing = findByNormalizedUrl(normalized);
        if (existing.isPresent()) return new Creation(existing.get(), false);
        SequenceDocument counter = mongo.findAndModify(
                Query.query(Criteria.where("_id").is("links")), new Update().inc("value", 1),
                FindAndModifyOptions.options().upsert(true).returnNew(true), SequenceDocument.class);
        if (counter == null) throw new IllegalStateException("No fue posible generar el identificador");
        MongoLinkDocument doc = new MongoLinkDocument(counter.getValue(), link.originalUrl(), link.shortCode(), link.createdAt());
        doc.setNormalizedUrl(normalized);
        doc.setCacheWindowConsumed(true);
        try {
            return new Creation(toDomain(mongo.insert(doc)), true);
        } catch (DuplicateKeyException ex) {
            // Another request may have inserted the URL after our initial read.
            existing = findByNormalizedUrl(normalized);
            if (existing.isPresent()) return new Creation(existing.get(), false);
            throw new CodeConflictException(ex);
        }
    }

    private Optional<ShortLink> findByNormalizedUrl(String normalized) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("normalizedUrl").is(normalized)),
                MongoLinkDocument.class)).map(MongoLinkRepository::toDomain);
    }

    @Override
    public List<ShortLink> findAll() {
        return mongo.find(new Query().with(Sort.by(Sort.Direction.ASC, "id")), MongoLinkDocument.class)
                .stream().map(MongoLinkRepository::toDomain).toList();
    }

    @Override
    public Optional<ShortLink> findByCode(String code) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("shortCode").is(code)),
                MongoLinkDocument.class)).map(MongoLinkRepository::toDomain);
    }

    private static ShortLink toDomain(MongoLinkDocument doc) {
        return new ShortLink(doc.getId(), doc.getOriginalUrl(), doc.getShortCode(), doc.getCreatedAt(), doc.isCacheWindowConsumed());
    }
}
