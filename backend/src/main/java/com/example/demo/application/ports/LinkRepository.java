package com.example.demo.application.ports;

import com.example.demo.domain.model.ShortLink;
import java.util.List;
import java.util.Optional;

public interface LinkRepository {
    Creation createIfAbsent(ShortLink link);
    List<ShortLink> findAll();
    Optional<ShortLink> findByCode(String code);
    record Creation(ShortLink link, boolean created) { }

    class CodeConflictException extends RuntimeException {
        public CodeConflictException(Throwable cause) { super(cause); }
    }
}
