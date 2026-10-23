package com.example.demo.infrastructure.web.websocket;

import com.example.demo.application.service.LinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class CacheStatusSocket extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final LinkService service;
    private final ObjectMapper mapper;

    public CacheStatusSocket(LinkService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        sendTo(session); // En recarga/reconexión, Redis continúa siendo la autoridad del tiempo.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        try { session.close(); } catch (IOException ignored) { }
    }

    @Scheduled(fixedRate = 1000)
    public void broadcastEverySecond() {
        if (sessions.isEmpty()) return;
        // Snapshot por ID, incluyendo expiraciones; ninguna lectura escribe claves Redis.
        try {
            TextMessage message = new TextMessage(mapper.writeValueAsString(service.cacheStatus()));
            for (WebSocketSession session : sessions) send(session, message);
        } catch (Exception ex) {
            // Do not leave clients displaying a stale active TTL during a storage outage.
            for (WebSocketSession session : sessions) handleTransportError(session, ex);
        }
    }

    private void sendTo(WebSocketSession session) {
        try { send(session, new TextMessage(mapper.writeValueAsString(service.cacheStatus()))); }
        catch (Exception ex) { handleTransportError(session, ex); }
    }

    private void send(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) { sessions.remove(session); return; }
        try {
            synchronized (session) { session.sendMessage(message); }
        } catch (Exception ex) {
            sessions.remove(session);
            try { session.close(); } catch (IOException ignored) { }
        }
    }
}
