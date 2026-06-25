package com.agent.mcp.integration;

import com.agent.common.model.ToolDefinition;
import com.agent.mcp.core.McpSession;
import com.agent.mcp.core.McpSessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具注册中心 — 将 MCP Server 暴露的工具统一注册到 Agent 引擎。
 * <p>
 * Agent 引擎不需要知道工具来自哪个 MCP Server，
 * 只需要知道工具名称和参数 Schema。调用时由 McpToolRegistry
 * 根据工具名前缀路由到对应的 MCP Server。
 *
 * <h3>工具命名约定：</h3>
 * 为避免跨 Server 的工具名冲突，建议格式：{@code serverName:toolName}。
 * 例如：{@code database:query_database}、{@code http-api:http_get}。
 *
 * <h3>你需要实现的内容：</h3>
 * <ol>
 *   <li>{@link #loadAllTools()} — 加载所有 MCP Server 的工具列表</li>
 *   <li>{@link #execute(String, JsonNode)} — 执行工具调用并路由到正确的 Server</li>
 * </ol>
 */
@Slf4j
public class McpToolRegistry {

    private final McpSessionManager sessionManager;

    /** 已注册的工具定义：toolName → ToolDefinition */
    private final Map<String, ToolDefinition> toolDefinitions = new java.util.concurrent.ConcurrentHashMap<>();

    /** 工具名 → Server 名映射 */
    private final Map<String, String> toolToServer = new java.util.concurrent.ConcurrentHashMap<>();

    public McpToolRegistry(McpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    // ==========================================================
    // 工具加载（你来写）
    // ==========================================================

    /**
     * TODO: 从所有已连接的 MCP Server 加载工具列表。
     *
     * <h3>实现步骤：</h3>
     * <ol>
     *   <li>遍历 sessionManager 中的所有 Session</li>
     *   <li>对每个 Session 调用 fetchTools()</li>
     *   <li>为每个工具名加上 serverName 前缀（如 "database:query_database"）</li>
     *   <li>存入 toolDefinitions 和 toolToServer Map</li>
     * </ol>
     *
     * <pre>{@code
     * for (var entry : sessionManager.getAllSessions().entrySet()) {
     *     String serverName = entry.getKey();
     *     McpSession session = entry.getValue();
     *     List<ToolDefinition> tools = session.fetchTools();
     *     for (ToolDefinition td : tools) {
     *         String fullName = serverName + ":" + td.getName();
     *         ToolDefinition prefixed = ToolDefinition.builder()
     *             .name(fullName)
     *             .description(td.getDescription())
     *             .parameters(td.getParameters())
     *             .build();
     *         toolDefinitions.put(fullName, prefixed);
     *         toolToServer.put(fullName, serverName);
     *     }
     * }
     * }</pre>
     */
    public void loadAllTools() {
        toolDefinitions.clear();
        toolToServer.clear();

        for (var entry : sessionManager.getAllSessions().entrySet()) {
            String serverName = entry.getKey();
            McpSession session = entry.getValue();
            try {
                List<ToolDefinition> tools = session.fetchTools();
                for (ToolDefinition td : tools) {
                    String fullName = serverName + ":" + td.getName();
                    ToolDefinition prefixed = ToolDefinition.builder()
                            .name(fullName)
                            .description(td.getDescription())
                            .parameters(td.getParameters())
                            .build();
                    toolDefinitions.put(fullName, prefixed);
                    toolToServer.put(fullName, serverName);
                    log.debug("注册工具: {} (来自 {} )", fullName, serverName);
                }
                log.info("已加载 {} 的工具: {} 个", serverName, tools.size());
            } catch (Exception e) {
                log.error("加载 MCP Server {} 的工具失败", serverName, e);
            }
        }
        log.info("MCP 工具注册中心初始化完成，共 {} 个工具", toolDefinitions.size());
    }

    // ==========================================================
    // 工具执行（你来写）
    // ==========================================================

    /**
     * TODO: 执行工具调用并返回结果。
     *
     * <h3>实现步骤：</h3>
     * <ol>
     *   <li>从 toolToServer 找到工具对应的 Server 名称</li>
     *   <li>去掉工具名的 serverName 前缀，恢复原始工具名</li>
     *   <li>调用对应 Session 的 callTool() 方法</li>
     *   <li>返回执行结果</li>
     * </ol>
     *
     * @param toolName  带前缀的完整工具名（如 "database:query_database"）
     * @param arguments 工具参数 JSON
     * @return 执行结果
     */
    public JsonNode execute(String toolName, JsonNode arguments) {
        // 1. 查找工具所属的 MCP Server
        String serverName = toolToServer.get(toolName);
        if (serverName == null) {
            throw new IllegalArgumentException("未注册的工具: " + toolName);
        }

        // 2. 获取对应的 Session
        McpSession session = sessionManager.getSession(serverName);

        // 3. 去掉 serverName: 前缀，恢复原始工具名
        String prefix = serverName + ":";
        String originalName = toolName.startsWith(prefix)
                ? toolName.substring(prefix.length())
                : toolName;

        // 4. 调用远程工具
        try {
            return session.callTool(originalName, arguments);
        } catch (Exception e) {
            throw new RuntimeException(
                    "执行 MCP 工具失败 [" + toolName + "] on server [" + serverName + "]: " + e.getMessage(), e);
        }
    }

    // ==========================================================
    // 查询方法
    // ==========================================================

    /**
     * 获取所有工具定义（用于发给 LLM 的 tools 数组）。
     */
    public List<ToolDefinition> getToolDefinitions() {
        return List.copyOf(toolDefinitions.values());
    }

    /**
     * 判断工具是否存在。
     */
    public boolean hasTool(String toolName) {
        return toolDefinitions.containsKey(toolName);
    }
}
