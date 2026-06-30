package com.agent.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Server 抽象基类 — 基于 JSON-RPC 2.0 over stdio。
 * <p>
 * 每个 MCP Server 是一个独立的 Java 进程，通过 stdin/stdout 与
 * MCP Client（Agent）通信。stderr 用于日志输出。
 *
 * <h3>通信模型：</h3>
 * <pre>
 * Client (Agent 进程)              Server (独立 Java 进程)
 *       │                                  │
 *       │── JSON-RPC Request ──→ stdin ──→ │
 *       │                                  │
 *       │←── JSON-RPC Response ── stdout ── │
 *       │                                  │
 *       │←── 日志 ── stderr ──              │
 * </pre>
 *
 * <h3>子类使用方式：</h3>
 * <pre>{@code
 * public class MyMcpServer extends McpServer {
 *     public static void main(String[] args) {
 *         MyMcpServer server = new MyMcpServer();
 *         server.registerTool("my_tool", "描述", schema, params -> ...);
 *         server.start();
 *     }
 * }
 * }</pre>
 *
 * <h3>你需要实现的内容：</h3>
 * {@link #start()} — stdio 消息循环
 */
public abstract class McpServer {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final JsonRpcCodec codec = new JsonRpcCodec(objectMapper);

    /** 已注册的工具：name → ToolEntry */
    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<>();

    /**
     * 工具条目：包含描述、参数 Schema 和处理器。
     */
    protected record ToolEntry(
            String name,
            String description,
            JsonNode inputSchema,
            ToolHandler handler
    ) {}

    /**
     * 工具处理器函数式接口。
     */
    @FunctionalInterface
    public interface ToolHandler {
        /**
         * 执行工具。
         * @param arguments 工具参数（JSON）
         * @return 执行结果（将序列化为 JSON 返回给客户端）
         * @throws Exception 执行失败
         */
        JsonNode execute(JsonNode arguments) throws Exception;
    }

    // ==========================================================
    // 子类可调用的注册方法
    // ==========================================================

    /**
     * 注册一个工具到当前 Server。
     *
     * @param name         工具名称（LLM 通过此名称选择工具）
     * @param description  工具功能描述
     * @param inputSchema  参数 JSON Schema（用 {@link SchemaFactory} 构建）
     * @param handler      执行逻辑
     */
    protected void registerTool(String name, String description,
                                 JsonNode inputSchema, ToolHandler handler) {
        tools.put(name, new ToolEntry(name, description, inputSchema, handler));
        log.info("注册工具: {}", name);
    }

    // ==========================================================
    // stdio 消息循环（核心，你来写）
    // ==========================================================

    /**
     * TODO: 启动 stdio 消息循环。
     *
     * <h3>实现步骤：</h3>
     * <ol>
     *   <li>从 {@link System#in} 逐行读取 JSON-RPC 请求</li>
     *   <li>每读一行 → {@link #handleRequest(JsonRpcMessage)} → 得到响应</li>
     *   <li>将响应编码为 JSON 写入 {@link System#out}</li>
     *   <li>{@link System#err} 打日志</li>
     *   <li>循环直到 stdin 关闭（readLine 返回 null）</li>
     * </ol>
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li>每行一个 JSON-RPC 消息（不能跨行）</li>
     *   <li>write 完响应后要 {@code flush()}</li>
     *   <li>异常不退出循环，在 stderr 打日志 + 返回 error 响应</li>
     *   <li>线程安全：单线程顺序处理即可（stdio 天然串行）</li>
     * </ul>
     *
     * <h3>伪代码：</h3>
     * <pre>{@code
     * BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
     * BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out));
     * log.info("MCP Server 启动: {}", getClass().getSimpleName());
     *
     * String line;
     * while ((line = in.readLine()) != null) {
     *     try {
     *         JsonRpcMessage request = codec.decode(line);
     *         JsonRpcMessage response = handleRequest(request);
     *         out.write(codec.encode(response));
     *         out.newLine();
     *         out.flush();
     *     } catch (Exception e) {
     *         log.error("处理消息失败", e);
     *         // 返回 error 响应
     *     }
     * }
     * }</pre>
     */
    public void start() throws IOException {
       BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
       BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out));
        log.info("MCP启动:{}",getClass().getSimpleName());
        String line;
     while ((line = in.readLine()) != null) {
            try {
                JsonRpcMessage request = codec.decode(line);
                JsonRpcMessage response = handleRequest(request);
                out.write(codec.encode(response));
                out.newLine();
                out.flush();
            } catch (Exception e) {
                log.error("处理消息失败",e);
                JsonRpcMessage errorReap = JsonRpcMessage.error(null,JsonRpcMessage.INTERNAL_ERROR, e.getMessage());
                try {
              out.write(codec.encode(errorReap));
                out.newLine();
                out.flush();
            } catch (Exception ex) {
                 log.error("写入错误响应失败", ex);
            }

            }
            
        }
         log.info("MCP Server 退出: stdin 已关闭");
    }

    // ==========================================================
    // 请求路由（已实现，子类通常不需要覆盖）
    // ==========================================================

    /**
     * 根据 JSON-RPC method 字段路由到对应处理器。
     * <p>
     * MCP 协议的核心方法：
     * <ul>
     *   <li>{@code tools/list} — 返回所有已注册的工具列表</li>
     *   <li>{@code tools/call} — 执行指定工具</li>
     * </ul>
     */
    protected JsonRpcMessage handleRequest(JsonRpcMessage request) {
        String method = request.getMethod();
        String id = request.getId();

        if (method == null) {
            return JsonRpcMessage.error(id, JsonRpcMessage.INVALID_REQUEST, "缺少 method 字段");
        }

        return switch (method) {
            case "tools/list" -> handleToolsList(id);
            case "tools/call" -> handleToolsCall(id, request.getParams());
            default -> JsonRpcMessage.error(id, JsonRpcMessage.METHOD_NOT_FOUND,
                    "未知方法: " + method);
        };
    }

    /**
     * 处理 tools/list — 返回所有已注册工具的定义。
     */
    private JsonRpcMessage handleToolsList(String id) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (ToolEntry entry : tools.values()) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("name", entry.name());
            toolNode.put("description", entry.description());
            toolNode.set("inputSchema", entry.inputSchema());
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", toolsArray);
        return JsonRpcMessage.success(id, result);
    }

    /**
     * 处理 tools/call — 执行指定工具。
     */
    private JsonRpcMessage handleToolsCall(String id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpcMessage.error(id, JsonRpcMessage.INVALID_PARAMS, "缺少 tool name");
        }

        String toolName = params.get("name").asText();
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();

        ToolEntry entry = tools.get(toolName);
        if (entry == null) {
            return JsonRpcMessage.error(id, JsonRpcMessage.METHOD_NOT_FOUND,
                    "工具不存在: " + toolName);
        }

        try {
            JsonNode toolResult = entry.handler().execute(arguments);
            ObjectNode result = objectMapper.createObjectNode();
            result.set("content", toolResult);
            result.put("success", true);
            return JsonRpcMessage.success(id, result);
        } catch (Exception e) {
            log.error("工具执行失败: {}", toolName, e);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", false);
            result.put("error", e.getMessage() != null ? e.getMessage() : "未知错误");
            result.put("stacktrace", getStackTraceString(e));
            // 工具执行失败仍返回 success response，在 content 里标记失败
            // 这样 Agent 可以根据 success 字段判断是否重试
            return JsonRpcMessage.success(id, result);
        }
    }

    // ===== 辅助方法 =====

    private String getStackTraceString(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
