package com.example.demo.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(@NotBlank(message = "La URL es obligatoria") String url) { }
