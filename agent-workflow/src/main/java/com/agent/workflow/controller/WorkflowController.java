package com.agent.workflow.controller;

import com.agent.workflow.engine.WorkflowEngine;
import com.agent.workflow.model.WorkflowDefinition;
import com.agent.workflow.model.WorkflowEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作流 API 控制器。
 *
 * <h3>接口</h3>
 * <ul>
 *   <li>{@code POST /api/workflow/execute} — 执行工作流（SSE 流式）</li>
 *   <li>{@code GET  /api/workflow/list} — 列出所有已注册的工作流</li>
 * </ul>
 *
 * <h3>你要写的内容</h3>
 * {@link #execute(Map)} — 接收 JSON 工作流定义并执行
 */
@Slf4j
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper;

    /** 内存中已注册的工作流（v1 简单方案，后续可改数据库） */
    private final List<WorkflowDefinition> workflows = new CopyOnWriteArrayList<>();

    public WorkflowController(WorkflowEngine engine, ObjectMapper objectMapper) {
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    /**
     * TODO: 执行工作流。
     *
     * <p>接收 JSON 格式的工作流定义 + 初始参数，SSE 流式返回每一步的执行结果。
     *
     * <h3>请求体格式</h3>
     * <pre>{@code
     * {
     *   "workflow": {
     *     "name": "查询并发送",
     *     "steps": [
     *       {"order": 1, "toolName": "database:query_database",
     *        "params": {"sql": "SELECT * FROM users"}, "outputVar": "users"},
     *       {"order": 2, "toolName": "http-api:http_post",
     *        "params": {"url": "https://api.example.com/report", "body": "{{users}}"},
     *        "outputVar": "result"}
     *     ]
     *   },
     *   "input": {}
     * }
     * }</pre>
     */
    @PostMapping(path = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> execute(@RequestBody Map<String, Object> request) {
        try {
            WorkflowDefinition wf = objectMapper.convertValue(request.get("workflow"), WorkflowDefinition.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) request.getOrDefault("input", Map.of());
            return engine.execute(wf, input)
                    .map(event -> ServerSentEvent.<String>builder()
                            .event(event.getType().name().toLowerCase())
                            .data(toJson(event))
                            .build());
        } catch (Exception e) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"error\":\"" + e.getMessage() + "\"}")
                    .build());
        }
    }
    private String toJson(Object obj) {
    try {
        return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
        return "{}";
    }
}

    /**
     * TODO: 注册一个工作流（存入内存列表，后续可持久化）。
     */
    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@RequestBody WorkflowDefinition wf) {
        if (wf.getId() == null || wf.getId().isBlank()) {
            wf.setId(java.util.UUID.randomUUID().toString());
        }
        workflows.add(wf);
        log.info("注册工作流: {} ({} 步骤)", wf.getName(), wf.getSteps().size());
        return Mono.just(Map.of("id", wf.getId(), "message", "注册成功"));
    }

    /**
     * 列出所有已注册的工作流。
     */
    @GetMapping("/list")
    public List<WorkflowDefinition> list() {
        return List.copyOf(workflows);
    }
}
