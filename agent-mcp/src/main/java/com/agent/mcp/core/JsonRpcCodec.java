package com.agent.mcp.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON-RPC 编解码器 — 负责消息的序列化与反序列化。
 *

 * <ol>
 *   <li>{@link #encode(JsonRpcMessage)} — 将消息序列化为 JSON 字符串</li>
 *   <li>{@link #decode(String)} — 将 JSON 字符串反序列化为消息对象</li>
 *   <li>错误处理 — JSON 解析失败时返回 PARSE_ERROR</li>
 * </ol>
 */
public class JsonRpcCodec {

    private final ObjectMapper objectMapper;

    public JsonRpcCodec() {
        this.objectMapper = new ObjectMapper();
    }

    public JsonRpcCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * TODO: 将 JsonRpcMessage 编码为 JSON 字符串。
     *
     * @param message 消息对象
     * @return 单行 JSON 字符串（不含换行符）
     * @throws JsonProcessingException 序列化失败
     */
    public String encode(JsonRpcMessage message) throws JsonProcessingException {
      return objectMapper.writeValueAsString(message);
    }

    /**
     * TODO: 将 JSON 字符串解码为 JsonRpcMessage。
     *
     * <p>如果 JSON 解析失败，返回一个 error 消息：
     * <pre>{@code
     * JsonRpcMessage.error(null, JsonRpcMessage.PARSE_ERROR, "Parse error");
     * }</pre>
     *
     * @param jsonLine 单行 JSON 字符串
     * @return 解码后的消息对象
     */
    public JsonRpcMessage decode(String jsonLine) {
      try {
        return objectMapper.readValue(jsonLine, JsonRpcMessage.class);
      } catch (Exception e) {
    return JsonRpcMessage.error(
        null,
        JsonRpcMessage.PARSE_ERROR,
        "Parse error :" + e.getMessage()
    );
      }
    }
}
