package com.agent.rag;

import com.agent.common.model.ToolDefinition;
import com.agent.mcp.core.SchemaFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG 知识库工具桥——把 KnowledgeService.search() 包装为 MCP 工具，
 * 让 Agent 能主动检索知识库。
 */
@Slf4j
public class KnowledgeToolBridge {

    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;

    public KnowledgeToolBridge(KnowledgeService knowledgeService, ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
    }

    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("search_knowledge")
                .description("在知识库中搜索相关文档。当用户询问文档内容、需要查找已上传的资料时使用此工具。参数: query - 搜索查询文本")
                .parameters(SchemaFactory.object()
                        .add("query", SchemaFactory.string("搜索查询文本，如'高二十一班周一课程'"))
                        .required("query")
                        .build())
                .build();
    }

    public JsonNode execute(JsonNode arguments) {
        String query = arguments.has("query")
                ? arguments.get("query").asText()
                : arguments.toString();
        log.info("RAG 检索: {}", query);
        String result = knowledgeService.search(query, 3);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("query", query);
        response.put("result", result);
        return response;
    }
}
