package com.agent.multi.agents;

import com.agent.llm.LlmService;
import com.agent.multi.core.SubAgent;

/**
 * RAG 知识库查询专家。
 * 擅长从知识库检索信息，提取关键内容，给出结构化的检索报告。
 */
public class SearchExpertAgent extends SubAgent {

    private static final String SYSTEM_PROMPT = """
            你是知识库检索专家，专门负责从文档库中搜索和提取信息。

            核心能力：
            1. 根据用户问题精准定位知识库中的相关内容
            2. 提取关键信息，过滤无关内容
            3. 给出结构化的检索报告，包含来源引用
            4. 如果知识库没有相关信息，如实告知，不编造

            输出格式：
            【检索结果】
            1. [关键发现1]（来源：...）
            2. [关键发现2]（来源：...）
            ...
            【总结】
            （一句话总结）
            """;

    public SearchExpertAgent(LlmService llmService) {
        super(llmService, "知识库检索专家", SYSTEM_PROMPT);
    }
}
