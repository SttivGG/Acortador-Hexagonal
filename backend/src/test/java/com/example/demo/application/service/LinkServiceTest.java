package com.example.demo.application.service;

import com.example.demo.application.ports.LinkCache;
import com.example.demo.application.ports.LinkRepository;
import com.example.demo.domain.model.ShortLink;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LinkServiceTest {
    private LinkRepository repository;
    private LinkCache cache;
    private LinkService service;
    private final ShortLink sample = new ShortLink(1, "https://example.com/", "ABC123xy", Instant.now(), true);

    @BeforeEach
    void setup() {
        repository = mock(LinkRepository.class);
        cache = mock(LinkCache.class);
        service = new LinkService(repository, cache);
        when(repository.findAll()).thenReturn(List.of(sample));
    }

    @Test
    void creationStartsTheOnlyWindow() {
        when(repository.createIfAbsent(any())).thenReturn(new LinkRepository.Creation(sample, true));
        assertEquals(sample, service.shorten(sample.originalUrl()));
        verify(cache).startWindow(sample);
    }

    @Test
    void repeatedCreationDoesNotWriteCache() {
        when(repository.createIfAbsent(any())).thenReturn(new LinkRepository.Creation(sample, false));
        assertEquals(sample, service.shorten(sample.originalUrl()));
        verifyNoInteractions(cache);
    }

    @Test
    void navigationReloadAndSocketSnapshotsOnlyReadRemainingTtl() {
        when(cache.remainingMillis(1)).thenReturn(20000L, 19000L, 18000L);
        assertEquals(20000, service.listLinks().cacheStatuses().getFirst().remainingMillis());
        assertEquals(19000, service.listLinks().cacheStatuses().getFirst().remainingMillis());
        assertEquals(18000, service.cacheStatus().getFirst().remainingMillis());
        verify(cache, never()).startWindow(any());
    }

    @Test
    void expiredLinkIsNeverRepopulatedEvenAfterServiceRestart() {
        when(cache.remainingMillis(1)).thenReturn(0L);
        assertTrue(service.listLinks().cacheStatuses().getFirst().expired());
        service = new LinkService(repository, cache);
        assertTrue(service.cacheStatus().getFirst().expired());
        service.listLinks();
        verify(cache, never()).startWindow(any());
    }

    @Test
    void multipleLinksKeepIndependentStates() {
        ShortLink second = new ShortLink(2, "https://example.com/b", "BBBBBBBB", Instant.now(), true);
        when(repository.findAll()).thenReturn(List.of(sample, second));
        when(cache.remainingMillis(1)).thenReturn(0L);
        when(cache.remainingMillis(2)).thenReturn(27000L);
        List<CacheStatus> statuses = service.cacheStatus();
        assertTrue(statuses.get(0).expired());
        assertTrue(statuses.get(1).active());
        assertEquals(27000, statuses.get(1).remainingMillis());
    }

    @Test
    void legacyLinksHaveNoInventedCountdown() {
        when(repository.findAll()).thenReturn(List.of(new ShortLink(1, sample.originalUrl(), sample.shortCode(), Instant.now(), false)));
        CacheStatus status = service.cacheStatus().getFirst();
        assertFalse(status.active());
        assertFalse(status.expired());
        assertEquals(0, status.remainingMillis());
    }

    @Test
    void invalidSchemeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.shorten("javascript:alert(1)"));
        verify(repository, never()).createIfAbsent(any());
    }

    @Test
    void redirectsWorkBeforeAndAfterExpiry() {
        when(repository.findByCode(sample.shortCode())).thenReturn(Optional.of(sample));
        assertEquals(sample.originalUrl(), service.resolveOriginalUrl(sample.shortCode()));
        assertEquals(sample.originalUrl(), service.resolveOriginalUrl(sample.shortCode()));
        verifyNoInteractions(cache);
    }

    @Test
    void missingRedirectIs404() {
        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.resolveOriginalUrl("missing"));
        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void codeCollisionRetriesWithoutClaimingAnotherWindow() {
        when(repository.createIfAbsent(any())).thenThrow(new LinkRepository.CodeConflictException(null))
                .thenReturn(new LinkRepository.Creation(sample, true));
        service.shorten(sample.originalUrl());
        verify(cache, times(1)).startWindow(sample);
    }
}
