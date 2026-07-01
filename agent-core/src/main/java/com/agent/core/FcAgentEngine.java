package com.agent.core;

import com.agent.common.model.AgentEvent;
import com.agent.common.model.ChatChunk;
import com.agent.common.model.Message;
import com.agent.common.model.ToolCall;
import com.agent.common.model.ToolDefinition;
import com.agent.llm.LlmService;
import com.agent.mcp.integration.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FC 原生 Agent 引擎 —— 整个项目的核心。
 *
 * <h3>核心流程（FC 模式 ReAct 循环）：</h3>
 * <pre>
 * 用户输入
 *   │
 *   ▼
 * ┌─────────────────────────────────────────────┐
 * │ 第 N 轮:                                     │
 * │  1. 组装 FC 请求 (messages + tools schema)    │
 * │  2. 流式调用 LLM                              │
 * │  3. 聚合响应: 文本 → thinking 事件             │
 * │              tool_calls → tool_call 事件      │
 * │  4. 如果有 tool_calls:                        │
 * │     → 执行工具 (MCP 协议)                     │
 * │     → 推送 tool_result 事件                   │
 * │     → 将 assistant + tool 消息写入历史        │
 * │     → 回到第1步                               │
 * │  5. 如果没有 tool_calls:                      │
 * │     → 推送 answer 事件                        │
 * │     → 推送 done 事件                          │
 * │     → 结束                                    │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>你需要实现的内容：</h3>
 * {@link #executeStream(String, String)} — 整个 Agent 循环的流式执行
 *
 * <h3>实现难点提示：</h3>
 * <ul>
 *   <li><b>FC 流式聚合</b>：LLM 的流式响应中 tool_call 的 arguments 是分片到达的，
 *       需要用 Map&lt;Integer, ToolCallBuilder&gt; 跟踪每个 tool_call 的增量构建进度</li>
 *   <li><b>并行工具调用</b>：LLM 可能一次返回多个 tool_call，它们之间无依赖时可以并行执行</li>
 *   <li><b>历史追加时机</b>：每轮结束后把 assistant(tool_calls) + tool(result) 成对写入历史</li>
 *   <li><b>循环终止条件</b>：LLM 不再返回 tool_calls 且 finish_reason="stop"</li>
 * </ul>
 */
@Slf4j
public class FcAgentEngine {

    private final LlmService llmService;
    private final McpToolRegistry toolRegistry;
    private final ConversationStore conversationStore;
    private final PromptBuilder promptBuilder;

    /** 最大循环轮次（防止死循环） */
    private static final int MAX_ROUNDS = 10;

    public FcAgentEngine(LlmService llmService,
                         McpToolRegistry toolRegistry,
                         ConversationStore conversationStore) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.promptBuilder = new PromptBuilder();
    }


    /**
     * TODO: 流式执行 Agent 循环。
     *
     * <h3>这是一个递归 Flux 模式：</h3>
     * <pre>{@code
     * Flux.defer(() -> {
     *     // 1. 加载历史消息
     *     List<Message> history = conversationStore.load(conversationId);
     *     history.add(Message.user(userMessage));
     *
     *     // 2. 调用递归循环
     *     return executeLoop(history, new AtomicInteger(0), conversationId);
     * });
     * }</pre>
     *
     * <h3>{@code executeLoop} 内部逻辑：</h3>
     * <ol>
     *   <li>检查轮次：round > MAX_ROUNDS → 返回 error 事件</li>
     *   <li>获取工具定义列表：toolRegistry.getToolDefinitions()</li>
     *   <li>组装 System Prompt：promptBuilder.build(toolDefinitions)</li>
     *   <li>构建 messages 列表（system prompt + history）</li>
     *   <li>调用 llmService.chatStream(messages, toolDefinitions)</li>
     *   <li>处理流式 chunk：
     *     <ul>
     *       <li>CONTENT → 推送 thinking 事件</li>
     *       <li>TOOL_CALL_DELTA → 增量聚合，不推送</li>
     *       <li>TOOL_CALL_END → 推送 tool_call 事件 + 执行工具</li>
     *       <li>FINISH → 检查 finishReason</li>
     *     </ul>
     *   </li>
     *   <li>工具执行完成后：推送 tool_result → 追加历史 → 递归下一轮</li>
     *   <li>finish_reason="stop" → 推送 answer → 推送 done → 结束</li>
     * </ol>
     *
     * <h3>关于递归 Flux：</h3>
     * 使用 {@code Flux.concat(a, Flux.defer(() -> executeLoop(...)))} 实现：
     * - 先发出当前轮次的所有事件（thinking → tool_call → tool_result）
     * - 然后递归发出下一轮的事件
     * - 当没有更多 tool_calls 时，发出 answer + done 后结束（Flux.empty()）
     *
     * @param userMessage     用户最新输入
     * @param conversationId  会话 ID
     * @return AgentEvent 事件流
     */
    public Flux<AgentEvent> executeStream(String userMessage, String conversationId) {
        return Flux.defer(() -> {
            List<Message> history = conversationStore.load(conversationId);
            history.add(Message.user(userMessage));
            history = summarizeIfNeeded(history, conversationId);
            return executeLoop(history, new AtomicInteger(1), conversationId);
        });
    }
private Flux<AgentEvent> executeLoop(List<Message> history,
                                                AtomicInteger round,
                                                String conversationId){
if(round.get()> MAX_ROUNDS){
    return Flux.just(AgentEvent.error(round.get(),"超出最大轮("+MAX_ROUNDS+")"));
}
List<ToolDefinition> tools = toolRegistry.getToolDefinitions();
List<Message> messages = buildMessagesList(history,tools);
//调用llm收集chunk
return llmService.chatStream(messages, tools)
.collectList().flatMapMany(chunks->{
    //聚合chunk
    String content =aggregateContent(chunks);
    List<ToolCall> toolCalls = aggregateToolCalls(chunks);
    //推送thinking
    Flux<AgentEvent> thinkingEvent = (content != null && !content.isEmpty())
    ?Flux.just(AgentEvent.thinking(round.get(),content)):Flux.empty();
    //工具调用->执行->递归
    if(!toolCalls.isEmpty()){
        return Flux.concat(thinkingEvent,
            Flux.fromIterable(toolCalls).flatMap(tc -> 
                Flux.concat(
                    Flux.just(AgentEvent.toolCall(round.get(), tc.getId(), tc.getName(), tc.getArguments())),
                    executeAndPushResult(tc, round)
                )


            ),
            Flux.defer(()-> {
                history.add(Message.assistantWithToolCalls(toolCalls));
                for(ToolCall tc : toolCalls){
                    history.add(Message.tool(tc.getId(),tc.getName(),
                            toolResults.getOrDefault(tc.getId(),"{}")));
                }
                return executeLoop(history, new AtomicInteger(round.get() + 1), conversationId);
            })
        );
    }
    history.add(Message.assistant(content));
    conversationStore.save(conversationId,history);
    return Flux.concat(
        thinkingEvent,
        Flux.just(AgentEvent.answer(round.get(),content)),
        Flux.just(AgentEvent.done(round.get(),0))
    );
});


}

    // ==========================================================
    // 工具执行（你来写）
    // ==========================================================

    /**
     * TODO: 执行单个工具调用并推送结果事件。
     *
     * <p>调用 {@link McpToolRegistry#execute(String, JsonNode)}，
     * 成功后推送 TOOL_RESULT 事件。
     * 失败时推送 ERROR 事件（不终止循环，让 LLM 自行决定如何处理失败）。
     *
     * @param toolCall 工具调用信息
     * @param round    当前轮次
     * @return TOOL_RESULT 或 ERROR 事件
     */
    private final Map<String,String> toolResults = new java.util.HashMap<>();
    private Flux<AgentEvent> executeAndPushResult(ToolCall toolCall, AtomicInteger round) {
      try {
        JsonNode result = toolRegistry.execute(toolCall.getName(), toolCall.getArguments());
        toolResults.put(toolCall.getId(),result.toString());
        return Flux.just(AgentEvent.toolResult(round.get(),toolCall.getId(),true,result));
      } catch (Exception e) {
        log.error("工具执行失败: {}", toolCall.getName(), e);
        String errorMsg = "工具执行失败: " + e.getMessage();
        toolResults.put(toolCall.getId(), errorMsg);
        return Flux.just(AgentEvent.toolResult(round.get(), toolCall.getId(), false, errorMsg));
      }
    }
    private String aggregateContent(List<ChatChunk> chunks) {
    return chunks.stream()
            .filter(c -> c.getType() == ChatChunk.ChunkType.CONTENT)
            .map(ChatChunk::getContent)
            .filter(c -> c != null)
            .collect(java.util.stream.Collectors.joining());
}

private List<ToolCall> aggregateToolCalls(List<ChatChunk> chunks) {
    return chunks.stream()
            .filter(c -> c.getType() == ChatChunk.ChunkType.TOOL_CALL_END)
            .flatMap(c -> c.getAllToolCalls().stream())
            .collect(java.util.stream.Collectors.toList());
}

    // ==========================================================
    // 对话摘要（长期记忆）
    // ==========================================================

    /**
     * 估算对话历史的大致 token 数。
     * 中英文混合场景 1 token ≈ 2 字符。
     */
    private int estimateTokens(List<Message> history) {
        int totalChars = 0;
        for (Message m : history) {
            if (m.getContent() != null) totalChars += m.getContent().length();
        }
        return totalChars / 2;
    }

    /**
     * 如果历史消息过长，用 LLM 把早期消息压缩为一段摘要。
     * 保留最近 6 条消息不变，压缩更早的消息。
     */
    private List<Message> summarizeIfNeeded(List<Message> history, String conversationId) {
        if (estimateTokens(history) < 15000) {
            return history;
        }

        int keepCount = Math.min(6, history.size());
        List<Message> recent = new ArrayList<>(history.subList(history.size() - keepCount, history.size()));
        List<Message> oldMessages = history.subList(0, history.size() - keepCount);

        // 拼成文本给 LLM 做摘要
        StringBuilder oldText = new StringBuilder();
        for (Message m : oldMessages) {
            if (m.getContent() != null) {
                oldText.append("[").append(m.getRole()).append("]: ")
                       .append(m.getContent()).append("\n");
            }
        }

        try {
            LlmService.ChatResponse resp = llmService.chatSync(
                List.of(Message.system("请用一段话（不超过200字）总结以下对话的关键信息，只输出摘要内容："),
                        Message.user(oldText.toString())),
                List.of()
            );
            String summary = resp.content() != null ? resp.content() : "（摘要生成失败）";

            List<Message> compressed = new ArrayList<>();
            compressed.add(Message.system("【之前对话摘要】" + summary));
            compressed.addAll(recent);

            // 异步存摘要到数据库
            conversationStore.updateSummary(conversationId, summary);

            log.info("对话摘要完成，压缩前 {} 条 → 压缩后 {} 条 ({} tokens → {} tokens)",
                    history.size(), compressed.size(),
                    estimateTokens(history), estimateTokens(compressed));
            return compressed;
        } catch (Exception e) {
            log.error("摘要生成失败，降级为保留最近消息", e);
            return recent;
        }
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    /**
     * 构建发送给 LLM 的 messages 列表（system prompt + 历史 + 工具定义）。
     */
    private List<Message> buildMessagesList(List<Message> history, List<ToolDefinition> tools) {
        List<Message> messages = new ArrayList<>();
        // 第一条：system prompt（包含工具使用规则）
        messages.add(Message.system(promptBuilder.build(tools)));
        // 后续：完整的对话历史
        messages.addAll(history);
        return messages;
    }
}
