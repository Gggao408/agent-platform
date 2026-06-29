package com.agent.mcp.servers;

import com.agent.mcp.core.McpServer;
import com.agent.mcp.core.SchemaFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpApiMcpServer extends McpServer {

    private final ObjectMapper mapper = new ObjectMapper();

    public HttpApiMcpServer() {
        registerTools();
    }

    private void registerTools() {
        registerTool("http_get",
                "发送HTTP GET请求到指定的URL，返回响应内容",
                SchemaFactory.object()
                        .add("url", SchemaFactory.string("完整的URL地址"))
                        .required("url")
                        .build(),
                this::httpGet);

        registerTool("http_post",
                "发送HTTP POST请求（JSON格式）到指定的URL，返回响应内容",
                SchemaFactory.object()
                        .add("url", SchemaFactory.string("完整的URL地址"))
                        .add("body", SchemaFactory.string("请求体JSON字符串"))
                        .required("url")
                        .build(),
                this::httpPost);
    }

    private JsonNode httpGet(JsonNode arguments) throws Exception {
        String url = arguments.get("url").asText();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        ObjectNode result = mapper.createObjectNode();
        result.put("status", response.statusCode());
        result.put("body", response.body());
        return result;
    }

    private JsonNode httpPost(JsonNode arguments) throws Exception {
        String url = arguments.get("url").asText();
        String body = arguments.has("body") ? arguments.get("body").asText() : "{}";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        ObjectNode result = mapper.createObjectNode();
        result.put("status", response.statusCode());
        result.put("body", response.body());
        return result;
    }

    public static void main(String[] args) throws Exception {
        new HttpApiMcpServer().start();
    }
}
