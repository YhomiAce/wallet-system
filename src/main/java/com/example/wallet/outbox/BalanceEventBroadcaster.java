package com.example.wallet.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fans Redis pub/sub balance events out to connected SSE clients (web/mobile screens that show a live balance).
 * Each app instance holds only its own connections; Redis pub/sub reaches all instances.
 */
@Component
public class BalanceEventBroadcaster implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceEventBroadcaster.class);

    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public BalanceEventBroadcaster(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter subscribe(UUID accountId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.computeIfAbsent(accountId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable cleanup = () -> remove(accountId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        return emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode node = mapper.readTree(message.getBody());
            UUID accountId = UUID.fromString(node.get("accountId").asText());
            Set<SseEmitter> targets = emitters.get(accountId);
            if (targets == null || targets.isEmpty()) return;

            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(SseEmitter.event().name("balance").data(json, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    remove(accountId, emitter);
                }
            }
        } catch (Exception e) {
            log.warn("Could not process balance event: {}", e.toString());
        }
    }

    private void remove(UUID accountId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(accountId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) emitters.remove(accountId, set);
        }
    }
}
