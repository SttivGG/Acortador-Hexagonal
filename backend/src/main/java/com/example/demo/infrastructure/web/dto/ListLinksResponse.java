package com.example.demo.infrastructure.web.dto;

import java.util.List;
import com.example.demo.application.service.CacheStatus;

public record ListLinksResponse(List<LinkResponse> links, List<CacheStatus> cacheStatuses) { }
