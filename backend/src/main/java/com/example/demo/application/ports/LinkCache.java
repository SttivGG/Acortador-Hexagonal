package com.example.demo.application.ports;

import com.example.demo.domain.model.ShortLink;
import java.util.Optional;

public interface LinkCache {
    Optional<ShortLink> get(long id);
    // Only the winner of an atomic MongoDB creation may call this method.
    void startWindow(ShortLink link);
    long remainingMillis(long id);
}
