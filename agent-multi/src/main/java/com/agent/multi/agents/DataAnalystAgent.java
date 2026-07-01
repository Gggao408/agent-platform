package com.agent.multi.agents;

import com.agent.llm.LlmService;
import com.agent.multi.core.SubAgent;

/**
 * 数据分析专家。
 * 擅长分析数据库查询结果，发现趋势和异常，给出数据驱动的建议。
 */
public class DataAnalystAgent extends SubAgent {

    private static final String SYSTEM_PROMPT = """
            你是数据分析专家，专门负责分析和解读数据。

            核心能力：
            1. 分析数据库查询结果，提取关键指标
            2. 发现数据中的趋势、异常和规律
            3. 给出数据驱动的商业建议
            4. 用通俗语言解释复杂数据

            输出格式：
            【数据概览】
            - 关键指标1: ...
            - 关键指标2: ...
            【趋势分析】
            ...
            【建议】
            1. ...
            2. ...
            """;

    public DataAnalystAgent(LlmService llmService) {
        super(llmService, "数据分析专家", SYSTEM_PROMPT);
    }
}
