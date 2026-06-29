package com.agent.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工作流执行事件——实时推送每一步的执行状态给前端/调用方。
 *
 * 事件序列：
 *   STARTED → STEP_STARTED(step1) → STEP_COMPLETED(step1) → ... → COMPLETED
 *
 * 你要写的内容：无
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowEvent {

    private EventType type;

    /** 当前是第几步（从 1 开始） */
    private int stepIndex;

    /** 总共几步 */
    private int totalSteps;

    /** 当前步骤的工具名 */
    private String toolName;

    /** 步骤执行结果（STEP_COMPLETED 时使用） */
    private Object result;

    /** 最终输出（COMPLETED 时使用） */
    private Map<String, Object> finalOutput;

    /** 错误信息（ERROR 时使用） */
    private String error;

    // ===== 工厂方法 =====

    public static WorkflowEvent started(int totalSteps) {
        return WorkflowEvent.builder()
                .type(EventType.STARTED)
                .totalSteps(totalSteps)
                .build();
    }

    public static WorkflowEvent stepStarted(int index, String toolName, int totalSteps) {
        return WorkflowEvent.builder()
                .type(EventType.STEP_STARTED)
                .stepIndex(index)
                .toolName(toolName)
                .totalSteps(totalSteps)
                .build();
    }

    public static WorkflowEvent stepCompleted(int index, String toolName, Object result, int totalSteps) {
        return WorkflowEvent.builder()
                .type(EventType.STEP_COMPLETED)
                .stepIndex(index)
                .toolName(toolName)
                .result(result)
                .totalSteps(totalSteps)
                .build();
    }

    public static WorkflowEvent completed(Map<String, Object> finalOutput) {
        return WorkflowEvent.builder()
                .type(EventType.COMPLETED)
                .finalOutput(finalOutput)
                .build();
    }

    public static WorkflowEvent error(int stepIndex, String error) {
        return WorkflowEvent.builder()
                .type(EventType.ERROR)
                .stepIndex(stepIndex)
                .error(error)
                .build();
    }

    public enum EventType {
        STARTED, STEP_STARTED, STEP_COMPLETED, COMPLETED, ERROR
    }
}
