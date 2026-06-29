package com.agent.workflow.engine;

import com.agent.mcp.integration.McpToolRegistry;
import com.agent.workflow.model.WorkflowDefinition;
import com.agent.workflow.model.WorkflowEvent;
import com.agent.workflow.model.WorkflowStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流引擎——按预设顺序执行工具调用，上一步输出是下一步输入。
 *
 * <h3>核心逻辑（你要写）</h3>
 * {@link #execute(WorkflowDefinition, Map)} — 递归顺序执行步骤列表
 *
 * <h3>工作原理</h3>
 * <pre>
 * 输入: {"userName": "张三"}
 * Step1: query_database("SELECT * FROM users WHERE name = {{userName}}")
 *        → 输出: [{"id":1, "name":"张三"}]
 *        → 存入 context.step1 + context.users
 * Step2: http_post({body: "{{users}}"})
 *        → 输出: {"status": "ok"}
 *        → 存入 context.step2 + context.result
 * 最终返回: context 里的所有变量
 * </pre>
 */
@Slf4j
public class WorkflowEngine {

    private final McpToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /** 匹配 {{变量名}} 的正则 */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    public WorkflowEngine(McpToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    // ==========================================================
    // 流式执行（你来写）
    // ==========================================================

    /**
     * TODO: 流式执行工作流，每完成一个步骤就推一个事件。
     *
     * <h3>实现步骤</h3>
     * <ol>
     *   <li>推送 STARTED 事件</li>
     *   <li>遍历 steps 列表：</li>
     *     <ul>
     *       <li>推送 STEP_STARTED</li>
     *       <li>调用 {@link #resolveParams(Map, Map)} 替换 {{变量名}}</li>
     *       <li>调用 {@link McpToolRegistry#execute(String, JsonNode)} 执行工具</li>
     *       <li>把结果存入 context（key = step.getOutputVar()）</li>
     *       <li>推送 STEP_COMPLETED</li>
     *     </ul>
     *   <li>推送 COMPLETED（含 context 中所有变量）</li>
     *   <li>某个步骤失败 → 推送 ERROR → 终止后续步骤</li>
     * </ol>
     *
     * <h3>伪代码</h3>
     * <pre>{@code
     * Flux<WorkflowEvent> start = Flux.just(WorkflowEvent.started(steps.size()));
     * return start.concatWith(Flux.defer(() -> executeStep(0, steps, context)));
     *
     * // 递归方法:
     * private Flux<WorkflowEvent> executeStep(int i, List<WorkflowStep> steps, Map<String, Object> ctx) {
     *     if (i >= steps.size()) return Flux.just(WorkflowEvent.completed(ctx));
     *     WorkflowStep step = steps.get(i);
     *     Flux<WorkflowEvent> startEvt = Flux.just(WorkflowEvent.stepStarted(i+1, step.getToolName(), steps.size()));
     *     return startEvt.concatWith(Flux.defer(() -> {
     *         try {
     *             Map<String, Object> resolved = resolveParams(step.getParams(), ctx);
     *             JsonNode result = toolRegistry.execute(step.getToolName(), ...);
     *             ctx.put(step.getOutputVar(), result);
     *             Flux<WorkflowEvent> doneEvt = Flux.just(WorkflowEvent.stepCompleted(i+1, step.getToolName(), result, steps.size()));
     *             return doneEvt.concatWith(Flux.defer(() -> executeStep(i+1, steps, ctx)));
     *         } catch (Exception e) {
     *             return Flux.just(WorkflowEvent.error(i+1, e.getMessage()));
     *         }
     *     }));
     * }
     * }</pre>
     *
     * @param wf       工作流定义
     * @param input    初始输入（如 {"userName": "张三"}）
     * @return 事件流
     */
    public Flux<WorkflowEvent> execute(WorkflowDefinition wf, Map<String, Object> input) {
        List<WorkflowStep> steps = wf.getSteps();
        Map<String,Object> context = new HashMap<>(input);

        //发started
        Flux<WorkflowEvent> start = Flux.just(WorkflowEvent.started(steps.size()));
        //递归
        return start.concatWith(executeStep(0, steps, context));
    }
    private Flux<WorkflowEvent> executeStep(int index,List<WorkflowStep> steps,
                                                Map<String,Object> context
    ){
        if(index >= steps.size()){
            //步骤完成
            return Flux.just(WorkflowEvent.completed(context));
        }
        WorkflowStep step = steps.get(index);
        try{
            //替换模板变量
            Map<String,Object> resolved = resolveParams(step.getParams(),context);
            //执行工具
            JsonNode result = toolRegistry.execute(step.getToolName(),
                    objectMapper.valueToTree(resolved));
            //结果存进上下文
            context.put(step.getOutputVar(),result);
            //递归下一步
            return Flux.concat(
                Flux.just(WorkflowEvent.stepCompleted(
                    index+1,step.getToolName(),result,steps.size()
                )),
                Flux.defer(() -> executeStep(index+1,steps,context))
            );
        }
        catch (Exception e){
            return Flux.just(WorkflowEvent.error(index+1,e.getMessage()));
        }
    }

    // ==========================================================
    // 变量替换（已实现，直接调用）
    // ==========================================================

    /**
     * 把 params 中的 {{变量名}} 替换为 context 中的实际值。
     *
     * <h3>示例</h3>
     * <pre>
     * params  = {"sql": "SELECT * FROM users WHERE name = '{{userName}}'"}
     * context = {"userName": "张三"}
     * 结果     = {"sql": "SELECT * FROM users WHERE name = '张三'"}
     * </pre>
     */
    Map<String, Object> resolveParams(Map<String, Object> params, Map<String, Object> context) {
        Map<String, Object> resolved = new HashMap<>();
        for (var entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str) {
                Matcher m = VAR_PATTERN.matcher(str);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String varName = m.group(1);
                    Object replacement = context.getOrDefault(varName, m.group(0));
                    m.appendReplacement(sb, String.valueOf(replacement));
                }
                m.appendTail(sb);
                resolved.put(entry.getKey(), sb.toString());
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}
