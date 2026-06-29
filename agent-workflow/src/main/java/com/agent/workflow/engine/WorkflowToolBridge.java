package com.agent.workflow.engine;

import com.agent.common.model.ToolDefinition;
import com.agent.mcp.core.SchemaFactory;
import com.agent.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Workflow 工具桥——把 WorkflowEngine 包装成一个 MCP 工具定义 + 处理器。
 *
 * 不用独立子进程。Agent 通过 McpToolRegistry 直接调这个方法，
 * 等同于调用了 WorkflowEngine。
 *
 * <h3>使用方式</h3>
 * 在 AppConfig 里调用 {@link #registerTo(com.agent.mcp.integration.McpToolRegistry)}
 * 把 execute_workflow 工具注册进去。
 */
@Slf4j
public class WorkflowToolBridge {

    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper;

    public WorkflowToolBridge(WorkflowEngine engine, ObjectMapper objectMapper) {
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    /**
     * 工具定义——告诉 LLM 有这个工具可用、需要什么参数。
     */
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("execute_workflow")
                .description("""
                        执行一个预设的工作流。工作流包含多个步骤（按顺序执行），
                        每个步骤调用一个 MCP 工具，上一步的输出可以传递到下一步。
                        适合需要多步操作的自动化任务。
                        参数：
                        - workflow: 工作流定义（name + steps列表）
                        - input: 初始输入参数（可选）""")
                .parameters(SchemaFactory.object()
                        .add("workflow", SchemaFactory.string(
                                "工作流JSON，包含 name 和 steps。每个 step 有 toolName/params/outputVar"))
                        .add("input", SchemaFactory.string(
                                "初始输入参数的 JSON 字符串，如 {\"userName\":\"张三\"}"))
                        .required("workflow")
                        .build())
                .build();
    }

    /**
     * 工具处理器——Agent 调 execute_workflow 时实际执行的方法。
     *
     * @param arguments LLM 传入的参数 JSON，包含 workflow 字符串和可选的 input 字符串
     * @return 执行结果 JSON（各步骤的输出汇总）
     */
    public JsonNode execute(JsonNode arguments) throws Exception {
        // 1. 解析工作流定义
        String workflowJson = arguments.has("workflow")
                ? arguments.get("workflow").asText()
                : arguments.get("workflow").toString();
        WorkflowDefinition wf = objectMapper.readValue(workflowJson, WorkflowDefinition.class);

        // 2. 解析初始输入
        @SuppressWarnings("unchecked")
        Map<String, Object> input = Map.of();
        if (arguments.has("input") && !arguments.get("input").isNull()) {
            String inputJson = arguments.get("input").asText();
            input = objectMapper.readValue(inputJson, Map.class);
        }

        // 3. 同步执行工作流（收集所有事件，取最终结果）
        var resultRef = new Object() {
            Map<String, Object> finalOutput;
            String error;
        };

        engine.execute(wf, input)
                .doOnNext(event -> {
                    if (event.getType() == com.agent.workflow.model.WorkflowEvent.EventType.COMPLETED) {
                        resultRef.finalOutput = event.getFinalOutput();
                    }
                    if (event.getType() == com.agent.workflow.model.WorkflowEvent.EventType.ERROR) {
                        resultRef.error = event.getError();
                    }
                })
                .collectList()
                .block();  // 阻塞等待所有步骤完成

        // 4. 构建返回 JSON
        if (resultRef.error != null) {
            throw new RuntimeException("工作流执行失败: " + resultRef.error);
        }

        return objectMapper.valueToTree(Map.of(
                "success", true,
                "workflow_name", wf.getName(),
                "steps_completed", wf.getSteps().size(),
                "output", resultRef.finalOutput != null ? resultRef.finalOutput : Map.of()
        ));
    }
}
