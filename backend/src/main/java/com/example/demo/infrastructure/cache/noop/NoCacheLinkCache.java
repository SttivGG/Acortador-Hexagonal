package com.example.demo.infrastructure.cache.noop;

import com.example.demo.application.ports.LinkCache;
import com.example.demo.domain.model.ShortLink;
import java.util.Optional;

// Alternative Abstract Factory family for the sin-cache profile.
public class NoCacheLinkCache implements LinkCache {
    @Override public Optional<ShortLink> get(long id) { return Optional.empty(); }
    @Override public void startWindow(ShortLink link) { }
    @Override public long remainingMillis(long id) { return 0; }
}
