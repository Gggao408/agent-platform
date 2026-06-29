package com.agent.llm;

import com.agent.common.model.ChatChunk;
import com.agent.common.model.Message;
import com.agent.common.model.ToolCall;
import com.agent.common.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 阿里云百炼 LLM 服务实现。
 * <p>
 * 百炼 API 兼容 OpenAI 格式，端点：
 * <ul>
 *   <li>Chat:     {@code POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}</li>
 *   <li>Embedding:{@code POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings}</li>
 * </ul>
 *
 * <h3>你需要实现的内容：</h3>
 * <ol>
 *   <li>SSE 流式解析 — 逐行读取 "data: {...}" 并转为 ChatChunk</li>
 *   <li>Tool Call 增量聚合 — 多个 delta chunk 拼接成完整的 JSON 参数</li>
 *   <li>错误处理 — HTTP 4xx/5xx → AgentException</li>
 *   <li>embed() — 调用百炼 Embedding API</li>
 * </ol>
 */
@Slf4j
public class BailianLlmService implements LlmService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String chatModel;       // 默认: qwen-plus
    private final String embeddingModel;  // 默认: text-embedding-v3

    // 百炼 API 基础 URL
    private static final String BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String CHAT_PATH = "/compatible-mode/v1/chat/completions";
    private static final String EMBED_PATH = "/compatible-mode/v1/embeddings";

    public BailianLlmService(String apiKey, String chatModel, String embeddingModel) {
        this.apiKey = apiKey;
        this.chatModel = chatModel != null ? chatModel : "qwen-plus";
        this.embeddingModel = embeddingModel != null ? embeddingModel : "text-embedding-v3";
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ==========================================================
    // chatStream — 流式 FC 调用（核心，你来写）
    // ==========================================================

    /**
     * TODO: 实现流式 FC 调用。
     *
     * <h3>实现步骤：</h3>
     * <ol>
     *   <li>将 messages + toolDefinitions 组装为 OpenAI FC 请求体 JSON</li>
     *   <li>设置 {@code "stream": true}</li>
     *   <li>通过 WebClient 发送 POST，获取 SSE 流</li>
     *   <li>逐行解析 "data: {...}" → ChatChunk</li>
     *   <li>处理 tool_call delta 的增量拼接</li>
     *   <li>检测 {@code finish_reason} 结束流</li>
     * </ol>
     *
     * <h3>请求体格式：</h3>
     * <pre>{@code
     * {
     *   "model": "qwen-plus",
     *   "messages": [...],
     *   "tools": [{"type": "function", "function": {"name": "...", ...}}],
     *   "tool_choice": "auto",
     *   "stream": true
     * }
     * }</pre>
     *
     * <h3>SSE 行格式：</h3>
     * <pre>{@code
     * data: {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"get_weather","arguments":"{\""}}]}}]}
     * data: [DONE]
     * }</pre>
     *
     * @see #buildRequestBody(List, List, boolean)  请求体构建
     * @see #parseSSELine(String)                    SSE 行解析
     */
    @Override
    public Flux<ChatChunk> chatStream(List<Message> messages, List<ToolDefinition> toolDefinitions) {
      ObjectNode body = buildRequestBody(messages, toolDefinitions, true);
      return webClient.post()
      .uri(CHAT_PATH)
      .bodyValue(body)
      .retrieve()
      .bodyToFlux(String.class)
      .filter(line -> !line.isBlank() && !"[DONE]".equals(line.trim()))
      .map(this::parseSSELine2)
      .transform(this::aggregateToolCalls);

    }

    // ==========================================================
    // chatSync — 同步 FC 调用（调试用，你来写）
    // ==========================================================

    /**
     * TODO: 实现同步 FC 调用。
     * <p>
     * 与 chatStream 的区别：设置 {@code "stream": false}，
     * 等完整响应返回后一次解析出 content + tool_calls。
     */
    @Override
    public ChatResponse chatSync(List<Message> messages, List<ToolDefinition> toolDefinitions) {
        // 1. 构建请求体（stream=false，百炼返回完整 JSON）
        ObjectNode body = buildRequestBody(messages, toolDefinitions, false);

        try {
            // 2. 发送 POST，同步阻塞等完整响应
            String responseJson = webClient.post()
                    .uri(CHAT_PATH)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // 3. 解析响应
            // 非流式响应格式: {"choices":[{"message":{"role":"assistant","content":"...","tool_calls":[...]},"finish_reason":"stop"}]}
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode message = root.path("choices").get(0).path("message");

            // 文本内容
            String content = message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText() : null;

            // 工具调用列表
            List<ToolCall> toolCalls = new java.util.ArrayList<>();
            if (message.has("tool_calls") && !message.get("tool_calls").isNull()) {
                for (JsonNode tcNode : message.get("tool_calls")) {
                    ToolCall tc = ToolCall.builder()
                            .id(tcNode.get("id").asText())
                            .name(tcNode.path("function").get("name").asText())
                            .arguments(objectMapper.readTree(
                                    tcNode.path("function").get("arguments").asText()))
                            .build();
                    toolCalls.add(tc);
                }
            }

            return new ChatResponse(content, toolCalls, null);

        } catch (Exception e) {
            log.error("同步调用失败", e);
            throw new RuntimeException("百炼同步调用失败: " + e.getMessage(), e);
        }
    }

    // ==========================================================
    // embed — 文本向量化（你来写）
    // ==========================================================

    /**
     * TODO: 实现文本向量化。
     *
     * <h3>百炼 Embedding API：</h3>
     * <pre>{@code
     * POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings
     * {
     *   "model": "text-embedding-v3",
     *   "input": "今天天气怎么样"
     * }
     *
     * Response:
     * {
     *   "data": [{"embedding": [0.123, -0.456, ...]}],
     *   "usage": {"total_tokens": 5}
     * }
     * }</pre>
     *
     * @param text 待向量化的文本
     * @return 向量 float[]（text-embedding-v3: 1536 维）
     */
    @Override
    public float[] embed(String text){
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",embeddingModel);
        body.put("input",text);
        try {
            String responseJson = webClient.post()
            .uri(EMBED_PATH)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .block();
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode embeddingNode = root.path("data").get(0).path("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            log.error("Embedding 失败",e);
            throw new RuntimeException("文本向量化失败: " + e.getMessage(), e);
        }
    }

    // ==========================================================
    // 以下是辅助方法
    // ==========================================================

    /**
     * 构建 OpenAI FC 兼容的请求体。
     *
     * @param messages  对话历史
     * @param tools     工具定义列表
     * @param stream    是否流式
     * @return JSON 请求体
     */
    private ObjectNode buildRequestBody(List<Message> messages,
                                         List<ToolDefinition> tools,
                                         boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", chatModel);
        body.put("stream", stream);

        // 如果 tools 非空，设置 tool_choice
        if (tools != null && !tools.isEmpty()) {
            body.put("tool_choice", "auto");
        }

        // 构建 messages 数组
        ArrayNode messagesNode = body.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesNode.addObject();
            msgNode.put("role", msg.getRole());

            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }

            // tool_calls（assistant 消息）
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                ArrayNode tcArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.getToolCalls()) {
                    ObjectNode tcNode = tcArray.addObject();
                    tcNode.put("id", tc.getId());
                    tcNode.put("type", "function");
                    ObjectNode funcNode = tcNode.putObject("function");
                    funcNode.put("name", tc.getName());
                    funcNode.put("arguments",
                            tc.getArguments() != null ? tc.getArguments().toString() : "{}");
                }
            }

            // tool_call_id（tool 消息）
            if (msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }
        }

        // 构建 tools 数组
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (ToolDefinition td : tools) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put("type", "function");
                ObjectNode funcNode = toolNode.putObject("function");
                funcNode.put("name", td.getName());
                funcNode.put("description", td.getDescription());
                if (td.getParameters() != null) {
                    funcNode.set("parameters", td.getParameters());
                }
            }
        }

        return body;
    }

    /**
     * 解析百炼 SSE 行（裸 JSON 格式，无 data: 前缀）。
     */
    private ChatChunk parseSSELine2(String jsonLine) {
        try {
            JsonNode root = objectMapper.readTree(jsonLine);
            JsonNode choice = root.path("choices").get(0);
            JsonNode delta = choice.path("delta");
            String finishReason = choice.path("finish_reason").asText(null);

            // 普通文本内容
            if (delta.has("content") && !delta.get("content").isNull()) {
                String content = delta.get("content").asText();
                if (!content.isEmpty()) {
                    return ChatChunk.builder()
                            .type(ChatChunk.ChunkType.CONTENT)
                            .content(content)
                            .finishReason(finishReason)
                            .build();
                }
                // content 为空但有 finish_reason → 结束标记
                if ("stop".equals(finishReason)) {
                    return ChatChunk.builder()
                            .type(ChatChunk.ChunkType.FINISH)
                            .finishReason(finishReason)
                            .build();
                }
                // content 为空且无 finish_reason → 跳过（首帧 role:assistant）
                return ChatChunk.builder().type(ChatChunk.ChunkType.FINISH).build();
            }

            // 工具调用
            if (delta.has("tool_calls")) {
                JsonNode tcNode = delta.get("tool_calls").get(0);
                ChatChunk.ChatChunkBuilder builder = ChatChunk.builder().finishReason(finishReason);
                if (tcNode.has("id") && !tcNode.get("id").isNull()) {
                    builder.toolCallId(tcNode.get("id").asText());
                }
                JsonNode func = tcNode.path("function");
                if (func.has("name") && !func.get("name").isNull()) {
                    builder.toolName(func.get("name").asText());
                }
                if (func.has("arguments") && !func.get("arguments").isNull()) {
                    builder.argumentsDelta(func.get("arguments").asText());
                }
                return builder.build();
            }

            // 纯结束标记
            return ChatChunk.builder()
                    .type(ChatChunk.ChunkType.FINISH)
                    .finishReason(finishReason)
                    .build();

        } catch (Exception e) {
            log.error("SSE 解析失败: {}", jsonLine, e);
            return ChatChunk.builder().type(ChatChunk.ChunkType.FINISH).build();
        }
    }

    /**
     * 解析一行 SSE data（兼容 OpenAI 兼容格式，带 data: 前缀）。
     *
     * @param sseLine 格式: {@code data: {"choices":[...]}}
     * @return ChatChunk（可能为 null，表示应跳过此行）
     */
    private ChatChunk parseSSELine(String sseLine) {
        String json = sseLine.substring(6);
      try {
        JsonNode root = objectMapper.readTree(json);
        JsonNode choice = root.path("choices").get(0);
        JsonNode delta = choice.path("delta");
        String finishReason = choice.path("finish_reason").asText(null);

        if (delta.has("content") && !delta.get("content").isNull()) {
            return ChatChunk.builder()
                    .type(ChatChunk.ChunkType.CONTENT)
                    .content(delta.get("content").asText())
                    .finishReason(finishReason)
                    .build();
        }

        if (delta.has("tool_calls")) {
            JsonNode tcNode = delta.get("tool_calls").get(0);
            int index = tcNode.has("index")?tcNode.get("Index").asInt():0;
            ChatChunk.ChatChunkBuilder builder = ChatChunk.builder().finishReason(finishReason);
            if(tcNode.has("id")&&!tcNode.get("id").isNull()){
                builder.toolCallId(tcNode.get("id").asText());
            }
            JsonNode func = tcNode.path("function");
            if(func.has("name")&&!func.get("name").isNull()){
                builder.argumentsDelta(func.get("arguments").asText());
            }
            if(func.has("arguments")&&!func.get("arguments").isNull()){
                builder.argumentsDelta(func.get("arguments").asText());
            }
            return builder.build();
        }

        return ChatChunk.builder()
                .type(ChatChunk.ChunkType.FINISH)
                .finishReason(finishReason)
                .build();

    } catch (Exception e) {
        log.error("SSE 解析失败: {}", json, e);
        return ChatChunk.builder()
                .type(ChatChunk.ChunkType.FINISH)
                .build();
    }
    }

    /**
     * 聚合工具调用的增量 chunk。
     * <p>
     * 因为 streaming 模式下 tool_call.arguments 可能分多个 chunk 返回：
     * <pre>
     * chunk1: {"city": "
     * chunk2: 北京
     * chunk3: "}
     * </pre>

     */
    private ToolCallBuilder currentBuilder;

private static class ToolCallBuilder {
    String id;
    String name;
    StringBuilder arguments = new StringBuilder();
}
    private Flux<ChatChunk> aggregateToolCalls(Flux<ChatChunk> rawChunks) {
        return rawChunks.handle((chunk,sink)->{
            String toolCallId = chunk.getToolCallId();
            String toolName = chunk.getToolName();
            String argsDelta = chunk.getArgumentsDelta();

      if(toolCallId!=null||toolName!=null||argsDelta!=null){
       if (currentBuilder == null) {
                currentBuilder = new ToolCallBuilder();
            }
            if (toolCallId != null) currentBuilder.id = toolCallId;
            if (toolName != null) currentBuilder.name = toolName;
            if (argsDelta != null) currentBuilder.arguments.append(argsDelta);
     if ("tool_calls".equals(chunk.getFinishReason())) {
               try {
        ToolCall complete = ToolCall.builder()
                .id(currentBuilder.id)
                .name(currentBuilder.name)
                .arguments(objectMapper.readTree(currentBuilder.arguments.toString()))
                .build();

        sink.next(ChatChunk.builder()
                .type(ChatChunk.ChunkType.TOOL_CALL_END)
                .allToolCalls(List.of(complete))
                .finishReason(chunk.getFinishReason())
                .build());
    } catch (Exception e) {
        log.error("ToolCall 参数解析失败: {}", currentBuilder.arguments, e);
    }
                currentBuilder = null;  // 清空，准备下一轮
            }
            return;}
    if (chunk.getType() == ChatChunk.ChunkType.FINISH) {
            currentBuilder = null;
            sink.next(chunk);
            return;
        }

        // 3. CONTENT → 直接透传
        sink.next(chunk);
    
    });
}}
