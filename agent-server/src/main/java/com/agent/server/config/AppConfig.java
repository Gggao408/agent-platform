package com.agent.server.config;

import com.agent.core.ConversationStore;
import com.agent.core.FcAgentEngine;
import com.agent.llm.BailianLlmService;
import com.agent.llm.LlmService;
import com.agent.llm.OllamaLlmService;
import com.agent.mcp.core.McpSessionManager;
import com.agent.mcp.integration.McpToolRegistry;
import com.agent.rag.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Spring 配置类 — 初始化所有 Bean 的依赖关系。
 *
 * <h3>你需要实现的内容：</h3>
 * 根据你的实际配置（API Key、数据库连接、模型选择等）
 * 补全每个 Bean 的构造函数参数。
 * <p>
 * 当前所有 Bean 方法都标记为 TODO，请逐个实现。
 */
@Slf4j
@Configuration
public class AppConfig {

    // ===== 外部配置 =====
    @Value("${agent.llm.provider:bailian}")  // bailian / ollama
    private String llmProvider;

    @Value("${agent.llm.bailian.api-key:}")
    private String bailianApiKey;

    @Value("${agent.llm.bailian.chat-model:qwen-plus}")
    private String bailianChatModel;

    @Value("${agent.llm.bailian.embedding-model:text-embedding-v3}")
    private String bailianEmbeddingModel;

    @Value("${agent.llm.ollama.model:qwen2.5:7b}")
    private String ollamaModel;

    @Value("${agent.llm.bailian.rerank-model:gte-rerank}")
    private String rerankModel;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    // ==========================================================
    // 基础设施 Bean
    // ==========================================================

    /**
     * TODO: 配置数据源（连接 PostgreSQL）。
     */
    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dbUrl);
        ds.setUsername(dbUsername);
        ds.setPassword(dbPassword);
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        return ds;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // ==========================================================
    // LLM 网关
    // ==========================================================

    /**
     * TODO: 根据配置选择 LLM 提供商。
     * <p>
     * 如果 llmProvider = "bailian" 且 apiKey 非空 → BailianLlmService
     * 否则 → OllamaLlmService（本地开发）
     */
    @Bean
    public LlmService llmService() {
        return switch (llmProvider) {
            case "ollama" -> {
                log.info("使用 Ollama 本地模型: {}", ollamaModel);
                yield new OllamaLlmService(ollamaModel);
            }
            case "bailian" -> {
                if (bailianApiKey == null || bailianApiKey.isBlank()) {
                    log.warn("未配置百炼 API Key，回退到 Ollama");
                    yield new OllamaLlmService(ollamaModel);
                }
                log.info("使用阿里百炼模型: chat={}, embedding={}", bailianChatModel, bailianEmbeddingModel);
                yield new BailianLlmService(bailianApiKey, bailianChatModel, bailianEmbeddingModel);
            }
            default -> throw new IllegalStateException("未知 LLM 提供商: " + llmProvider);
        };
    }

    // ==========================================================
    // MCP 协议层
    // ==========================================================

    /**
     * TODO: 初始化 MCP Session Manager，注册所有 MCP Server。
     *
     * <h3>注册示例：</h3>
     * <pre>{@code
     * McpSessionManager manager = new McpSessionManager();
     * manager.registerServer("database",
     *     "java", "-cp", "agent-mcp/target/classes;agent-common/target/classes",
     *     "com.agent.mcp.servers.DatabaseMcpServer");
     * manager.registerServer("http-api",
     *     "java", "-cp", "agent-mcp/target/classes;agent-common/target/classes",
     *     "com.agent.mcp.servers.HttpApiMcpServer");
     * manager.initAll();
     * return manager;
     * }</pre>
     */
    @Bean
    public McpSessionManager mcpSessionManager() {
        McpSessionManager manager = new McpSessionManager();

        // 注册 Database MCP Server
        manager.registerServer("database",
                "java", "-cp",
                "agent-mcp/target/classes;agent-common/target/classes",
                "com.agent.mcp.servers.DatabaseMcpServer");

        // 注册 HTTP API MCP Server
        manager.registerServer("http-api",
                "java", "-cp",
                "agent-mcp/target/classes;agent-common/target/classes",
                "com.agent.mcp.servers.HttpApiMcpServer");

        // 启动所有已注册的 MCP Server
        manager.initAll();
        return manager;
    }

    @Bean
    public McpToolRegistry mcpToolRegistry(McpSessionManager sessionManager) {
        McpToolRegistry registry = new McpToolRegistry(sessionManager);
        registry.loadAllTools();
        log.info("MCP 工具注册完成，共 {} 个工具", registry.getToolDefinitions().size());
        return registry;
    }

    // ==========================================================
    // Agent 核心引擎
    // ==========================================================

    @Bean
    public ConversationStore conversationStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new ConversationStore(dataSource, objectMapper);
    }

    @Bean
    public FcAgentEngine fcAgentEngine(LlmService llmService,
                                        McpToolRegistry toolRegistry,
                                        ConversationStore conversationStore) {
        return new FcAgentEngine(llmService, toolRegistry, conversationStore);
    }

    // ==========================================================
    // Workflow 工作流引擎
    // ==========================================================

    @Bean
    public com.agent.workflow.engine.WorkflowEngine workflowEngine(McpToolRegistry toolRegistry,
                                                                     ObjectMapper objectMapper) {
        return new com.agent.workflow.engine.WorkflowEngine(toolRegistry, objectMapper);
    }

    @Bean
    public com.agent.workflow.engine.WorkflowToolBridge workflowToolBridge(
            com.agent.workflow.engine.WorkflowEngine workflowEngine,
            ObjectMapper objectMapper,
            McpToolRegistry mcpToolRegistry) {
        var bridge = new com.agent.workflow.engine.WorkflowToolBridge(workflowEngine, objectMapper);
        mcpToolRegistry.registerDirectTool("execute_workflow",
                bridge.getToolDefinition(),
                args -> {
                    try {
                        return bridge.execute(args);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        log.info("Workflow 工具已注册到 Agent");
        return bridge;
    }

    // ==========================================================
    // RAG 知识库
    // ==========================================================

    @Bean
    public OcrService ocrService() {
        return new OcrService(bailianApiKey);
    }

    @Bean
    public DocumentParser documentParser(OcrService ocrService) {
        return new DocumentParser(ocrService);
    }

    @Bean
    public ChunkSplitter chunkSplitter() {
        return new ChunkSplitter();  // 默认 chunkSize=512, overlap=128
    }

    @Bean
    public VectorStoreService vectorStoreService(DataSource dataSource) {
        return new VectorStoreService(dataSource);
    }

    @Bean
    public RerankService rerankService() {
        return new RerankService(bailianApiKey, rerankModel);
    }

    @Bean
    public KnowledgeService knowledgeService(DocumentParser documentParser,
                                              ChunkSplitter chunkSplitter,
                                              LlmService llmService,
                                              VectorStoreService vectorStoreService,
                                              RerankService rerankService) {
        return new KnowledgeService(documentParser, chunkSplitter, llmService,
                vectorStoreService, rerankService);
    }
}
