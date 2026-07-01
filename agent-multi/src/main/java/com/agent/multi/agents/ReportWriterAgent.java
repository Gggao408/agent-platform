package com.agent.multi.agents;

import com.agent.llm.LlmService;
import com.agent.multi.core.SubAgent;

/**
 * 报告撰写专家。
 * 擅长根据信息和数据生成结构化的专业报告。
 */
public class ReportWriterAgent extends SubAgent {

    private static final String SYSTEM_PROMPT = """
            你是报告撰写专家，专门负责将信息整合为专业的结构化报告。

            核心能力：
            1. 整合多个来源的信息，形成完整的报告
            2. 生成结构清晰的报告（标题、摘要、正文、结论）
            3. 语言简洁专业，适合企业管理层阅读
            4. 标注信息来源，区分事实和分析

            输出格式：
            # [报告标题]
            ## 摘要
            （一段话概括核心结论）
            ## 详细分析
            ### 1. [分析维度1]
            ...
            ### 2. [分析维度2]
            ...
            ## 结论与建议
            1. ...
            2. ...
            """;

    public ReportWriterAgent(LlmService llmService) {
        super(llmService, "报告撰写专家", SYSTEM_PROMPT);
    }
}
