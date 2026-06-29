package com.agent.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工作流中的一个步骤——调用哪个工具、传什么参数、输出存到哪个变量。
 *
 * 例如：
 *   toolName  = "database:query_database"
 *   params    = {"sql": "SELECT * FROM users"}
 *   outputVar = "users"
 *
 * 执行后，上一步的输出可通过变量名在下一步的 params 里引用：
 *   toolName  = "http-api:http_post"
 *   params    = {"body": "{{users}}"}   ← {{users}} 会被替换为 Step1 的结果
 *
 * 你要写的内容：无（纯数据类）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStep {

    /** 第几步（从 1 开始） */
    private int order;

    /** 工具名称（带 MCP 前缀，如 "database:query_database"） */
    private String toolName;

    /** 工具参数（key-value，值可用 {{变量名}} 引用之前的输出） */
    private Map<String, Object> params;

    /** 执行结果存入 context 时的变量名 */
    private String outputVar;
}
