package com.agent.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流定义——描述一个可重复执行的自动化流程。
 *
 * 例如："日报生成"工作流包含两步：
 *   1. query_database: SELECT * FROM orders WHERE date = TODAY()
 *   2. http_post: 把查询结果 POST 到报表 API
 *
 * 你要写的内容：无（纯数据类，Lombok 自动生成 getter/setter/builder）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinition {

    /** 工作流唯一 ID */
    private String id;

    /** 工作流名称，如 "日报生成" */
    private String name;

    /** 工作流描述 */
    private String description;

    /** 步骤列表（按顺序执行） */
    private List<WorkflowStep> steps;

    /** 可选：cron 表达式，用于定时触发 */
    private String cronExpression;

    /** 是否启用 */
    @Builder.Default
    private boolean enabled = true;
}
