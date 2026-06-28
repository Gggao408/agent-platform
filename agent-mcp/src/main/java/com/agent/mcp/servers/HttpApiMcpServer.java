package com.agent.mcp.servers;

import com.agent.mcp.core.McpServer;
import com.agent.mcp.core.SchemaFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * HTTP API MCP Server — 让 Agent 能够调用外部 REST API。
 * <p>
 * 这是一个通用 HTTP 调用工具，Agent 可以动态构造 HTTP 请求。
 * 实际使用中可以限制 domain 白名单以保障安全性。
 *
 * <h3>暴露的工具：</h3>
 * <ul>
 *   <li>{@code http_get} — 发送 GET 请求</li>
 *   <li>{@code http_post} — 发送 POST 请求（JSON body）</li>
 * </ul>
 *
 * <h3>你需要实现的内容：</h3>
 * HTTP GET/POST 的实际调用逻辑。
 */
public class HttpApiMcpServer extends McpServer {

    private final ObjectMapper mapper = new ObjectMapper();

    public HttpApiMcpServer() {
        registerTools();
    }

    private void registerTools() {
        // 工具1：HTTP GET
        registerTool("http_get",
                "发送HTTP GET请求到指定的URL，返回响应内容",
                SchemaFactory.object()
                        .add("url", SchemaFactory.string("完整的URL地址，如 https://api.example.com/data"))
                        .required("url")
                        .build(),
                this::httpGet
        );

        // 工具2：HTTP POST
        registerTool("http_post",
                "发送HTTP POST请求（JSON格式）到指定的URL，返回响应内容",
                SchemaFactory.object()
                        .add("url", SchemaFactory.string("完整的URL地址"))
                        .add("body", SchemaFactory.string("请求体JSON字符串"))
                        .required("url")
                        .build(),
                this::httpPost
        );
    }

    /**
     * TODO: 实现 HTTP GET 请求。
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li>使用 java.net.http.HttpClient（Java 11+ 内置）或 OkHttp</li>
     *   <li>设置合理的超时（连接 10s，读取 30s）</li>
     *   <li>返回响应状态码 + body</li>
     *   <li>HTTP 4xx/5xx 不抛异常——把状态码和错误体返回给 LLM 判断</li>
     * </ul>
     *
     * <h3>返回格式：</h3>
     * <pre>{@code
     * {
     *   "status": 200,
     *   "headers": {"Content-Type": "application/json"},
     *   "body": "{...}"
     * }
     * }</pre>
     */
    private JsonNode httpGet(JsonNode arguments) throws Exception {
        String url = arguments.get("url").asText();
    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(java.time.Duration.ofSeconds(30))
            .GET()
            .build();
    java.net.http.HttpResponse<String> response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

    ObjectNode result = mapper.createObjectNode();
    result.put("status", response.statusCode());
    result.put("body", response.body());
    return result;

    /**
     * TODO: 实现 HTTP POST 请求。
     */
    private JsonNode httpPost(JsonNode arguments) throws Exception {
      String url = arguments.get("url").asText();
    String body = arguments.has("body") ? arguments.get("body").asText() : "{}";

    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
    java.net.http.HttpResponse<String> response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

    ObjectNode result = mapper.createObjectNode();
    result.put("status", response.statusCode());
    result.put("body", response.body());
    return result;
    }

    // ===== 入口 =====

    public static void main(String[] args) {
        new HttpApiMcpServer().start();
    }
}
