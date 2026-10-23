package com.example.demo.infrastructure.web.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final CacheStatusSocket statusSocket;
    public WebSocketConfig(CacheStatusSocket statusSocket) { this.statusSocket = statusSocket; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(statusSocket, "/ws/cache")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:8080");
    }
}
