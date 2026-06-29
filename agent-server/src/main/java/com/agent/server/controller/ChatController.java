package com.agent.server.controller;

import com.agent.common.model.AgentEvent;
import com.agent.core.ConversationStore;
import com.agent.core.FcAgentEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final FcAgentEngine agentEngine;
    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper;

    public ChatController(FcAgentEngine agentEngine,
                          ConversationStore conversationStore,
                          ObjectMapper objectMapper) {
        this.agentEngine = agentEngine;
        this.conversationStore = conversationStore;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("pong", true);
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody String rawBody) {
        try {
            Map<String, Object> request = objectMapper.readValue(rawBody, Map.class);
            String message = (String) request.getOrDefault("message", "");
            String convId = request.containsKey("conversationId") && request.get("conversationId") != null
                    ? (String) request.get("conversationId")
                    : conversationStore.createConversation("新对话", "qwen-plus");
            return agentEngine.executeStream(message, convId)
                    .map(event -> ServerSentEvent.<String>builder()
                            .event(event.getType().name().toLowerCase())
                            .data(toJson(event))
                            .build())
                    .onErrorResume(e -> {
                        log.error("Agent 执行异常", e);
                        return Flux.just(ServerSentEvent.<String>builder()
                                .event("error")
                                .data("{\"error\":\"" + e.getMessage() + "\"}")
                                .build());
                    });
        } catch (Exception e) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"error\":\"请求解析失败: " + e.getMessage() + "\"}")
                    .build());
        }
    }

    @PostMapping
    public Mono<Map<String, Object>> chatSync(@RequestBody String rawBody) {
        try {
            Map<String, Object> request = objectMapper.readValue(rawBody, Map.class);
            String message = (String) request.getOrDefault("message", "");
            String convId = request.containsKey("conversationId") && request.get("conversationId") != null
                    ? (String) request.get("conversationId")
                    : conversationStore.createConversation("新对话", "qwen-plus");
            return agentEngine.executeStream(message, convId)
                    .collectList()
                    .map(events -> {
                        String answer = events.stream()
                                .filter(e -> e.getType() == AgentEvent.EventType.ANSWER)
                                .map(AgentEvent::getContent)
                                .findFirst().orElse("无响应");
                        return Map.of("answer", answer, "conversationId", convId,
                                "eventCount", events.size());
                    });
        } catch (Exception e) {
            return Mono.just(Map.of("error", "请求解析失败: " + e.getMessage()));
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }
}
