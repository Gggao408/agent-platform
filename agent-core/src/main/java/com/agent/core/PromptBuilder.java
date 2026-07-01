package com.agent.core;

import com.agent.common.model.ToolDefinition;

import java.util.List;

/**
 * System Prompt 构建器 — 生成 Agent 的系统指令。
 * <p>
 * LLM 通过 System Prompt 理解自己的角色、行为规范和可用工具。
 * 一个好的 System Prompt 对 Agent 的行为质量至关重要。
 *
 * <h3>你需要实现的内容：</h3>
 * 根据工具列表动态生成合适的 System Prompt。
 * 先用我提供的模板跑通流程，后续可以根据实际效果微调措辞。
 */
public class PromptBuilder {

    /**
     * TODO: 构建 System Prompt。
     *
     * <h3>核心要素：</h3>
     * <ol>
     *   <li><b>角色定义</b> — 告诉 LLM 它是一个智能助手</li>
     *   <li><b>工具使用规则</b> — 什么时候用工具、怎么用</li>
     *   <li><b>行为约束</b> — 不准猜测、不准编造、失败后如何处理</li>
     *   <li><b>输出规范</b> — 使用中文、格式要求</li>
     * </ol>
     *
     * <h3>推荐模板：</h3>
     * <pre>{@code
     * """
     * 你是一个智能助手，可以调用工具来获取信息和执行操作。
     *
     * 规则：
     * 1. 当需要获取外部信息（天气、数据库数据、知识库内容等）时，调用相应工具
     * 2. 工具返回结果后，基于结果继续思考和回答
     * 3. 当信息足够回答用户问题时，直接给出答案，不要再调用工具
     * 4. 如果工具调用失败，尝试其他方式或如实告知用户
     * 5. 不要编造数据——所有事实性信息必须来自工具返回结果
     * 6. 所有回答使用中文
     * 7. 回答要简洁、准确、有帮助
     * """
     * }</pre>
     *
     * @param tools 可用工具列表（可在此展示工具摘要帮助 LLM 理解）
     * @return System Prompt 字符串
     */
    public String build(List<ToolDefinition> tools) {
       return"""
               你是一个智能助手,你叫散篓子。你可以调用工具来获取信息和执行操作。

               你有三个专家子 Agent 可以委派任务：
               - expert:search_expert（知识库检索专家）: 搜索文档、提取关键信息
               - expert:data_analyst（数据分析专家）: 分析数据、发现趋势
               - expert:report_writer（报告撰写专家）: 生成结构化报告

               重要规则：
               1. 当用户需要搜索文档或知识库时，调用 expert:search_expert
               2. 当用户需要分析数据时，调用 expert:data_analyst
               3. 当用户需要生成报告时，先收集信息再调用 expert:report_writer
               4. 复杂任务可以依次委派多个专家
               5. 工具返回结果后，基于结果继续思考和回答
               6. 如果工具调用失败，尝试其他方式或如实告知用户
               7. 不要编造数据——所有事实性信息必须来自工具返回结果
               8. 所有回答使用中文，回答要简洁、准确、有帮助
               """;
    }
}
