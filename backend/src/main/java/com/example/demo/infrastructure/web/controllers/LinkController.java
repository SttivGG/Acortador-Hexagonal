package com.example.demo.infrastructure.web.controllers;

import com.example.demo.application.service.LinkListResult;
import com.example.demo.application.service.LinkService;
import com.example.demo.domain.model.ShortLink;
import com.example.demo.infrastructure.web.dto.CreateLinkRequest;
import com.example.demo.infrastructure.web.dto.LinkResponse;
import com.example.demo.infrastructure.web.dto.ListLinksResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links")
public class LinkController {
    private final LinkService service;
    private final String baseUrl;

    public LinkController(LinkService service, @Value("${app.public-base-url}") String baseUrl) {
        this.service = service;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @PostMapping
    public ResponseEntity<LinkResponse> shorten(@Valid @RequestBody CreateLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(service.shorten(request.url())));
    }

    @GetMapping
    public ListLinksResponse list() {
        LinkListResult result = service.listLinks();
        return new ListLinksResponse(result.links().stream().map(this::toResponse).toList(),
                result.cacheStatuses());
    }

    private LinkResponse toResponse(ShortLink link) {
        return new LinkResponse(link.id(), link.originalUrl(), baseUrl + "/r/" + link.shortCode());
    }
}
