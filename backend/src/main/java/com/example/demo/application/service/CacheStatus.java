package com.example.demo.application.service;

public record CacheStatus(long id, boolean active, boolean expired, long remainingMillis,
                          long observedAtMillis) { }
