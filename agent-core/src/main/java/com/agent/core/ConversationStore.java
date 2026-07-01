package com.agent.core;

import com.agent.common.model.Message;
import com.agent.common.model.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话持久化存储 — 基于 PostgreSQL。
 * <p>
 * 管理 conversations 和 messages 两张表。
 *
 * <h3>你需要实现的内容：</h3>
 * <ol>
 *   <li>{@link #createConversation(String, String)} — 创建新会话</li>
 *   <li>{@link #load(String)} — 加载会话的全部消息历史</li>
 *   <li>{@link #save(String, List)} — 保存消息历史</li>
 *   <li>{@link #listConversations()} — 列出所有会话</li>
 * </ol>
 *
 * <h3>实现提示：</h3>
 * 使用 Spring JdbcTemplate 或原始 JDBC。
 * tool_calls 字段以 JSONB 格式存储。
 */
@Slf4j
public class ConversationStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public ConversationStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    /**
     * TODO: 创建新会话。
     *
     * <pre>{@code
     * INSERT INTO conversations (id, title, model) VALUES (?, ?, ?)
     * }</pre>
     *
     * @param title 会话标题（初始可为 "新对话"）
     * @param model 使用的模型名称
     * @return 会话 ID
     */
    public String createConversation(String title, String model) {
      String id = UUID.randomUUID().toString();
      String sql = "INSERT INTO conversations(id,title,model) VALUES (?,?,?)";
      try(Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)){
            stmt.setObject(1,UUID.fromString(id));
            stmt.setString(2,title);
            stmt.setString(3,model);
            stmt.executeUpdate();
        }catch(SQLException e){
            throw new RuntimeException("对话创建失败",e);
        }
        return id;
        
     
    }

    /**
     * TODO: 加载会话的全部消息历史。
     *
     * <pre>{@code
     * SELECT role, content, tool_calls, tool_call_id
     * FROM messages WHERE conversation_id = ? ORDER BY created_at ASC
     * }</pre>
     *
     * <p>需要将数据库行转换为 {@link Message} 对象，
     * 特别处理 tool_calls JSONB → List&lt;ToolCall&gt; 的转换。
     *
     * @param conversationId 会话 ID
     * @return 消息列表（按时间升序）
     */
    public List<Message> load(String conversationId) {
      String sql = "SELECT role, content, tool_calls, tool_call_id FROM messages WHERE conversation_id = ? ORDER BY created_at ASC";
        List<Message> messages = new ArrayList<>();
        try(Connection conn = dataSource.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)){
                        stmt.setObject(1, UUID.fromString(conversationId));
                        ResultSet rs = stmt.executeQuery();
                        while(rs.next()){
                            messages.add(Message.builder()
                        .role(rs.getString("role"))
                        .content(rs.getString("content"))
                        .toolCalls(deserializeToolCalls(rs.getString("tool_calls")))
                    .toolCallId(rs.getString("tool_call_id"))
                .build());
                        }
                    }
                    catch(SQLException e){
                        throw new RuntimeException("会话加载失败",e);
                    }
                    return messages;
    }

    /**
     * TODO: 保存会话的全部消息历史。
     *
     * <h3>策略：全量覆盖</h3>
     * 先 DELETE 旧消息，再批量 INSERT 新消息。
     * 简单可靠，适合当前阶段。后续可以改为增量追加。
     *
     * <pre>{@code
     * DELETE FROM messages WHERE conversation_id = ?
     *
     * // 批量插入
     * INSERT INTO messages (conversation_id, role, content, tool_calls, tool_call_id, token_count)
     * VALUES (?, ?, ?, ?, ?, ?)
     * }</pre>
     *
     * @param conversationId 会话 ID
     * @param messages       完整消息列表
     */
    public void save(String conversationId, List<Message> messages) {
        // 先删后插（自动提交模式，无需手动事务）
        UUID convUuid = UUID.fromString(conversationId);
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM messages WHERE conversation_id = ?")) {
                del.setObject(1, convUuid);
                del.executeUpdate();
            }
            String sql = "INSERT INTO messages (conversation_id,role,content,tool_calls,tool_call_id) VALUES (?::uuid,?,?,?::jsonb,?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (Message msg : messages) {
                    stmt.setObject(1, convUuid);
                    stmt.setString(2, msg.getRole());
                    stmt.setString(3, msg.getContent());
                    stmt.setString(4, serializeToolCalls(msg.getToolCalls()));
                    stmt.setString(5, msg.getToolCallId());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        } catch (SQLException e) {
            log.error("保存会话失败, id={}", conversationId, e);
            throw new RuntimeException("保存会话失败: " + e.getMessage(), e);
        }
    }
    /**
     * TODO: 列出所有会话（概要信息）。
     *
     * <pre>{@code
     * SELECT id, title, model, created_at, updated_at
     * FROM conversations ORDER BY updated_at DESC
     * }</pre>
     */
    public List<ConversationSummary> listConversations() {
    String sql = "SELECT id, title, model, created_at, updated_at FROM conversations ORDER BY updated_at DESC";
    List<ConversationSummary> result = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql);
         ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
            result.add(new ConversationSummary(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("model"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            ));
        }
    } catch (SQLException e) {
        throw new RuntimeException("列出会话失败", e);
    }
    return result;
}

    /**
     * TODO: 删除会话。
     *
     * <pre>{@code
     * DELETE FROM conversations WHERE id = ?  -- CASCADE 会删除关联消息
     * }</pre>
     */
    public void deleteConversation(String conversationId) {
        String sql = "DELETE FROM conversations WHERE id = ?";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setObject(1, UUID.fromString(conversationId));
        stmt.executeUpdate();
    } catch (SQLException e) {
        throw new RuntimeException("删除会话失败", e);
    }
    }

    // ===== 辅助方法 =====

    /**
     * 将 ToolCall 列表序列化为 JSONB 字符串。
     */
    private String serializeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (JsonProcessingException e) {
            log.error("序列化 toolCalls 失败", e);
            return null;
        }
    }
    /** 
     * 更新会话摘要
     * 建立新查表语句
     * **/
public void updateSummary(String conversationId, String summary){
    String sql = "UPDATE conversations SET summary = ? WHERE id = ?";
    try (Connection conn = dataSource.getConnection();){
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1,summary);
        stmt.setObject(2, UUID.fromString(conversationId));
        stmt.executeUpdate();

    } catch (SQLException e) {
        log.error("更新摘要失败",e);
    }
}

    /**
     * 将 JSONB 字符串反序列化为 ToolCall 列表。
     */
    private List<ToolCall> deserializeToolCalls(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ToolCall.class));
        } catch (JsonProcessingException e) {
            log.error("反序列化 toolCalls 失败", e);
            return null;
        }
    }

    // ===== 数据类型 =====

    public record ConversationSummary(
            String id,
            String title,
            String model,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
