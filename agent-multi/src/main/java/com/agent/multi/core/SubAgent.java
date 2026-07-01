package com.agent.multi.core;

import com.agent.common.model.Message;
import com.agent.common.model.ToolCall;
import com.agent.llm.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 基类 — 每个子 Agent 以特定 System Prompt 运行 LLM 推理。
 * <p>
 * 不是独立进程。通过 DirectTool 方式注册到 McpToolRegistry，
 * 主 Agent（Orchestrator）通过 MCP 工具调用子 Agent。
 * <p>
 * 子类只需提供 System Prompt（角色定义）即可得到一个专家 Agent。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * SubAgent searchAgent = new SubAgent(llmService, "你是搜索专家，擅长从知识库检索信息。");
 * searchAgent.registerAsTool(registry, "search_expert");
 * }</pre>
 */
@Slf4j
public class SubAgent {

    private final LlmService llmService;
    private final String systemPrompt;
    private final String agentName;
    private final ObjectMapper objectMapper;

    public SubAgent(LlmService llmService, String agentName, String systemPrompt) {
        this.llmService = llmService;
        this.agentName = agentName;
        this.systemPrompt = systemPrompt;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 执行推理任务。
     * 子 Agent 收到任务描述 → 用自己的 System Prompt + LLM 生成回答。
     *
     * @param task 任务描述（由主 Agent 传入）
     * @return 执行结果 JSON
     */
    public JsonNode think(String task) {
        log.info("[{}] 收到任务: {}", agentName, task.substring(0, Math.min(100, task.length())));

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(task));

        // 同步调用 LLM，不使用工具（纯推理）
        LlmService.ChatResponse response = llmService.chatSync(messages, List.of());

        ObjectNode result = objectMapper.createObjectNode();
        result.put("agent", agentName);
        result.put("task", task);
        result.put("result", response.content() != null ? response.content() : "（空响应）");

        log.info("[{}] 推理完成，输出长度: {}", agentName,
                response.content() != null ? response.content().length() : 0);
        return result;
    }

    /**
     * 执行带上下文的推理任务。
     * 主 Agent 可以把之前收集的信息作为 context 传给子 Agent。
     *
     * @param task    任务描述
     * @param context 上下文信息（如 RAG 检索结果、数据库查询结果等）
     * @return 执行结果 JSON
     */
    public JsonNode thinkWithContext(String task, String context) {
        String fullTask = String.format("""
                任务：%s

                以下是相关背景信息，请基于这些信息完成任务：
                ---
                %s
                ---
                请给出专业的分析和回答。
                """, task, context);

        return think(fullTask);
    }

    /**
     * 将子 Agent 注册为 MCP 直接工具。
     *
     * @param registry McpToolRegistry
     * @param toolName 工具短名（如 "search_expert"）
     * @param prefix   前缀（如 "expert"）
     */
    public void registerAsTool(com.agent.mcp.integration.McpToolRegistry registry,
                                String prefix, String toolName) {
        var toolDef = com.agent.common.model.ToolDefinition.builder()
                .name(toolName)
                .description(String.format("""
                        调用 %s（%s）。
                        参数: task - 要执行的任务描述（必填），context - 背景信息（可选）
                        """, agentName, systemPrompt.substring(0, Math.min(80, systemPrompt.length()))))
                .parameters(com.agent.mcp.core.SchemaFactory.object()
                        .add("task", com.agent.mcp.core.SchemaFactory.string("任务描述"))
                        .add("context", com.agent.mcp.core.SchemaFactory.string("背景信息（可选）"))
                        .required("task")
                        .build())
                .build();

        registry.registerDirectTool(prefix, toolName, toolDef, args -> {
            String task = args.has("task") ? args.get("task").asText() : args.toString();
            if (args.has("context") && !args.get("context").isNull()) {
                return thinkWithContext(task, args.get("context").asText());
            }
            return think(task);
        });

        log.info("子 Agent 已注册为工具: {}:{} ({})", prefix, toolName, agentName);
    }

    public String getAgentName() {
        return agentName;
    }
}
