package com.example.demo.application.service;

import com.example.demo.domain.model.ShortLink;
import java.util.List;

public record LinkListResult(List<ShortLink> links, List<CacheStatus> cacheStatuses) { }
